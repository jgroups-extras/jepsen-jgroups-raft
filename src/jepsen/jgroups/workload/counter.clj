(ns jepsen.jgroups.workload.counter
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [client :as client])
    [jepsen.checker :as checker]
    [jepsen.checker.timeline :as timeline]
    [jepsen.generator :as gen]
    [jepsen.jgroups.workload.client :as c]
    [knossos.model :as model])
  (:import (java.net InetAddress)
           (knossos.model Model)
           (org.jgroups.raft.client SyncReplicatedCounterClient)))

(defn get'
  "A get operation that returns the current value of the counter."
  [_ _]
  {:type :invoke, :f :read, :value nil})

(defn add
  "A add operation that adds a delta to the counter."
  [_ _]
  {:type :invoke, :f :add, :value (rand-int 5)})

(defn add-and-get
  "An add-and-get operation that adds a given value to the counter and returns the new value."
  [_ _]
  {:type :invoke, :f :add-and-get, :value (rand-int 5)})

(defn decr
  "Decrement a delta from the counter."
  [_ _]
  {:type :invoke, :f :decr, :value (rand-int 5)})

(defn decr-and-get
  "Decrement a delta from the counter and return the new value."
  [_ _]
  {:type :invoke, :f :decr-and-get, :value (rand-int 5)})


(defn get!
  "Get the counter current value."
  [conn]
  (.get conn))

(defn add!
  "Add a delta to the counter."
  [conn delta]
  (.add conn delta))

(defn add-and-get!
  "Add a delta to the counter and return the new value."
  [conn delta]
  (.addAndGet conn delta))

(defn neg!
  "Turns a number negative by multiplying by -1."
  [x]
  (if (neg? x) x (* -1 x)))

(defrecord CounterClient [conn]
  client/Client

  (open! [this test node]
    (info "Starting counter client connecting to" node)
    (let [c (doto (SyncReplicatedCounterClient. node)
              ; A small timeout can cause a lot of pressure for the model checker.
              ; Small here would be less than or equal to the nemesis interval.
              ; We are using the double of the nemesis interval or 10s.
              (.withTimeout (long (* 1000 (:operation-timeout test))))
              (.withTargetAddress (InetAddress/getByName node))
              (.withTargetPort 9000))]
      (.start c)
      (assoc this :conn c)))

  (setup! [this test])

  (invoke! [this test op]
    (let [v (:value op)]
      (c/with-errors op #{:read}
        (case (:f op)
          :read (let [res (get! conn)]
                 (assoc op :type :ok, :value res))

          :add (do (add! conn v)
                   (assoc op :type :ok))

          :decr (do (add! conn (neg! v))
                    (assoc op :type :ok))

          :add-and-get (assoc op :type :ok, :value [v (add-and-get! conn v)])

          :decr-and-get (assoc op :type :ok, :value [v (add-and-get! conn (neg! v))])))))

  (teardown! [this test])

  (close! [this test]
    (.close conn)))

(defrecord CounterModel [value]
  Model
  (step [r op]
    (condp = (:f op)
      :add (CounterModel. (+ value (:value op)))

      :decr (CounterModel. (- value (:value op)))

      :read (if (or (nil? (:value op))
                   (= value (:value op)))
             r
             (model/inconsistent (str "can't read " (:value op) " from counter " value)))

      :add-and-get (if (vector? (:value op))
                     (let [[delta new] (:value op)]
                       (if (= (+ value delta) new)
                         (CounterModel. new)
                         (model/inconsistent (str "adding " delta " to " value " should result in " new))))
                     ; Happened in case of :info. We don't know if the operation was applied or not.
                     (CounterModel. (+ value (:value op))))

      :decr-and-get (if (vector? (:value op))
                      (let [[delta new] (:value op)]
                        (if (= (- value delta) new)
                          (CounterModel. new)
                          (model/inconsistent (str "decreasing " delta " to " value " should result in " new))))
                      ; Happened in case of :info. We don't know if the operation was applied or not.
                      (CounterModel. (- value (:value op)))))))

(defn workload
  "Create a workload for testing a counter."
  [opts]
  {:client (CounterClient. nil)
   :checker (checker/compose
              {:timeline (timeline/html)
               :linear   (checker/linearizable
                           {:model     (CounterModel. 0)
                            :algorithm :linear})})
   :generator (->> (gen/mix [get' add decr add-and-get decr-and-get]))})
