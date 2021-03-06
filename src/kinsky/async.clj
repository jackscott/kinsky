(ns kinsky.async
  "Clojure core.async support in kinsky.
   See https://github.com/pyr/kinsky for example usage."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as impl]
            [kinsky.client      :as client])
  (:import [java.lang IllegalStateException]))

(defn duplex
  ([up down] (duplex up down [up down]))
  ([up down indexed]
   (reify
     impl/ReadPort
     (take! [_ fn-handler]
       (impl/take! down fn-handler))
     impl/WritePort
     (put! [_ val fn-handler]
       (impl/put! up val fn-handler))
     impl/Channel
     (close! [_]
       (impl/close! up)
       (impl/close! down))
     (closed? [_]
       (and (impl/closed? up)
            (impl/closed? down)))
     clojure.lang.Indexed
     (nth [_ idx]
       (nth indexed idx))
     (nth [_ idx not-found]
       (nth indexed idx not-found))
     clojure.lang.ILookup
     (valAt [this key]
       (.valAt this key nil))
     (valAt [_ key not-found]
       (case key
         [:sink :up]     up
         [:source :down] down
                         not-found)))))

(def default-input-buffer
  "Default amount of messages buffered on control channels."
  10)

(def default-output-buffer
  "Default amount of messages buffered on the record channel."
  100)

(def default-timeout
  "Default timeout, by default we poll at 100ms intervals."
  100)

(defn exception?
  "Test if a value is a subclass of Exception"
  [e]
  (instance? Exception e))

(defn make-consumer
  "Build a consumer, with or without deserializers"
  [config kd vd]
  (let [opts (dissoc config :input-buffer :output-buffer :timeout :topic)]
    (cond
      (and kd vd)      (client/consumer opts kd vd)
      :else            (client/consumer opts))))

(defn channel-listener
  "A rebalance-listener compatible call back which produces all
   events onto a channel."
  [ch]
  (fn [event]
    (a/put! ch (assoc event :type :rebalance))))

(def record-xform
  "Rely on the standard transducer but indicate that this is a record."
  (comp client/record-xform (map #(assoc % :type :record))))

(defn get-within
  [ctl tm]
  (let [max-wait (a/timeout tm)]
    (a/alt!!
      max-wait ([_] nil)
      ctl      ([v] v))))

(defn poller-ctl
  [ctl out driver timeout]
  (if-let [payload (get-within ctl timeout)]
    (let [{:keys [op]}  payload
          topic-offsets (:topic-offsets payload)
          topic         (or (:topics payload) (:topic payload))]
      (cond
        (= op :callback)
        (let [f (:callback payload)]
          (or (f driver out)
              (not (:process-result? payload))))

        (= op :stop)
        false

        :else
        (or
         (cond
           (= op :subscribe)
           (client/subscribe! driver topic (channel-listener out))

           (= op :unsubscribe)
           (client/unsubscribe! driver)

           (and (= op :commit) topic-offsets)
           (client/commit! driver topic-offsets)

           (= op :commit)
           (client/commit! driver)

           (= op :pause)
           (client/pause! driver (:topic-partitions payload))

           (= op :resume)
           (client/resume! driver (:topic-partitions payload))

           (= op :partitions-for)
           (do
             (let [parts (client/partitions-for driver topic)]
               (a/put! (or (:response payload) out)
                       {:type       :partitions
                        :partitions parts}))))
         true)))
    true))

(defn close-poller
  [ctl out driver]
  (a/put! out {:type :eof})
  (a/close! ctl)
  (client/close! driver)
  (a/close! out))

(defn safe-poll
  [ctl recs out driver timeout]
  (try
    (let [records (client/poll! driver timeout)]
      (a/>!! recs records)
      true)
    (catch org.apache.kafka.common.errors.WakeupException _
      (poller-ctl ctl out driver timeout))
    (catch IllegalStateException _
      (poller-ctl ctl out driver timeout))
    (catch Exception e
      (a/put! out {:type :exception :exception e})
      false)))

(defn poller-thread
  "Poll for next messages, catching exceptions and yielding them."
  [driver inbuf outbuf timeout]
  (let [ctl      (a/chan inbuf)
        mult     (a/mult ctl)
        c1       (a/chan inbuf)
        c2       (a/chan inbuf)
        out      (a/chan outbuf)
        recs     (a/chan outbuf record-xform (fn [e] (throw e)))]
    (a/tap mult c1)
    (a/tap mult c2)
    (a/pipe recs out)
    (future
      (.setName (Thread/currentThread) "kafka-control-poller")
      (loop []
        (when-let [cr (a/<!! c2)]
          (client/wake-up! driver)
          (recur))))
    (future
      (.setName (Thread/currentThread) "kafka-consumer-poller")
      (loop []
        (let [recur? (safe-poll c1 recs out driver timeout)]
          (if recur?
            (recur)
            (close-poller ctl out driver)))))
    [ctl out]))

(defn consumer
  "Build an async consumer. Yields a vector of record and control
   channels.

   Arguments config ks and vs work as for kinsky.client/consumer.
   The config map must be a valid consumer configuration map and may contain
   the following additional keys:

   - `:input-buffer`: Maximum backlog of control channel messages.
   - `:output-buffer`: Maximum queued consumed messages.
   - `:timeout`: Poll interval
   - `:topic` : Automatically subscribe to this topic before launching loop

   The resulting control channel is used to interact with the consumer driver
   and expects map payloads, whose operation is determined by their
   `:op` key. The following commands are handled:

   - `:subscribe`: `{:op :subscribe :topic \"foo\"}` subscribe to a topic.
   - `:unsubscribe`: `{:op :unsubscribe}`, unsubscribe from all topics.
   - `:partitions-for`: `{:op :partitions-for :topic \"foo\"}`, yield partition
      info for the given topic. If a `:response` key is
      present, produce the response there instead of on
      the record channel.
   - `commit`: `{:op :commit}` commit offsets, an optional `:topic-offsets` key
      may be present for specific offset committing.
   - `:pause`: `{:op :pause}` pause consumption.
   - `:resume`: `{:op :resume}` resume consumption.
   - `:calllback`: `{:op :callback :callback (fn [d ch])}` Execute a function
      of 2 arguments, the consumer driver and output channel, on a woken up
      driver.
   - `:stop`: `{:op :stop}` stop and close consumer.
   - `:duplex?`: yields a duplex channel instead of a vector of two chans


   The resulting output channel will emit payloads with as maps containing a
   `:type` key where `:type` may be:

   - `:record`: A consumed record.
   - `:exception`: An exception raised
   - `:rebalance`: A rebalance event.
   - `:eof`: The end of this stream.
   - `:partitions`: The result of a `:partitions-for` operation.
   - `:woken-up`: A notification that the consumer was woken up.
"
  ([config]
   (consumer config nil nil))
  ([config kd vd]
   (let [topic           (:topic config)
         duplex? (:duplex? config)
         inbuf           (or (:input-buffer config) default-input-buffer)
         outbuf          (or (:output-buffer config) default-output-buffer)
         timeout         (or (:timeout config) default-timeout)
         driver          (make-consumer (dissoc config :duplex? :topic) kd vd)
         [ctl out]       (poller-thread driver inbuf outbuf timeout)]
     (when topic
       (client/subscribe! driver topic))
     (if duplex?
       (duplex ctl out [out ctl])
       [out ctl]))))

(defn make-producer
  "Build a producer, with or without serializers"
  [config ks vs]
  (cond
    (and ks vs) (client/producer config ks vs)
    :else       (client/producer config)))

(defn producer
  "Build a producer, reading records to send from a channel.

   Arguments config ks and vs work as for kinsky.client/producer.
   The config map must be a valid consumer configuration map and may contain
   the following additional keys:

   - `:input-buffer`: Maximum backlog of control channel messages.
   - `:output-buffer`: Maximum queued consumed messages.

   Yields a vector of two values `[in out]`, an input channel and
   an output channel.

   The resulting input channel is used to interact with the producer driver
   and expects map payloads, whose operation is determined by their
   `:op` key. The following commands are handled:

   - `:record`: `{:op :record :topic \"foo\"}` send a record out, also
      performed when no `:op` key is present.
   - `:flush`: `{:op :flush}`, flush unsent messages.
   - `:partitions-for`: `{:op :partitions-for :topic \"foo\"}`, yield partition
      info for the given topic. If a `:response` key is
      present, produce the response there instead of on
      the record channel.
   - `:close`: `{:op :close}`, close the producer.

   The resulting output channel will emit payloads with as maps containing a
   `:type` key where `:type` may be:

   - `:exception`: An exception raised
   - `:partitions`: The result of a `:partitions-for` operation.
   - `:eof`: The producer is closed.
"
  ([config]
   (producer config nil nil))
  ([config ks]
   (producer config ks ks))
  ([config ks vs]
   (let [inbuf   (or (:input-buffer config) default-input-buffer)
         outbuf  (or (:output-buffer config) default-output-buffer)
         opts    (dissoc config :input-buffer :output-buffer)
         driver  (make-producer opts ks vs)
         in      (a/chan inbuf)]
     (future
       (loop [{:keys [op timeout callback response topic] :as record} (a/<!! in)]
         (cond
           (= op :close)
           (client/close! driver timeout)

           (= op :flush)
           (client/flush! driver)

           (= op :callback)
           (callback driver)

           (= op :partitions-for)
           (when response
             (a/put! response
                     {:type       :partitions
                      :partitions (client/partitions-for driver (name topic))}))

           :else
           (client/send! driver (dissoc record :op)))
         (recur (a/<!! in))))
     in)))
