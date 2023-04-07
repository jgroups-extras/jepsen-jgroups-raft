(ns jepsen.jgroups.workload.register
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [client :as client])
    [jepsen.checker :as checker]
    [jepsen.checker.timeline :as timeline]
    [jepsen.generator :as gen]
    [jepsen.independent :as independent]
    [jepsen.jgroups.workload.client :as c]
    [knossos.model :as model])
  (:import
    (java.net InetAddress)
    (org.jgroups.raft.client SyncReplicatedStateMachineClient)))

(defn parse-response
  "Parse response from String to long."
  [res]
  (when res (parse-long res)))

(defn r
  "Read operations using the JGroups-RAFT client."
  [_ _]
  {:type :invoke, :f :read, :value nil})

(defn w
  "Write operation using the JGroups-RAFT client."
  [_ _]
  {:type :invoke, :f :write, :value (rand-int 5)})

(defn cas
  "Compare-and-swap using the JGroups-RAFT client."
  [_ _]
  {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn raft-read
  "Reads the given key from the given state machine."
  ([conn key]
   (raft-read conn key {}))
  ([conn key opts]
   (parse-response (.get conn key (:quorum? opts)))))

(defn raft-write
  "Writes the given value to the key using the state machine."
  [conn key value]
  (.put conn key value))

(defn raft-cas
  "Tries a compare-and-set operation using the state machine."
  [conn key old new]
  (.compareAndSet conn key old new))

(defrecord ReplicatedStateMachineClient [conn]
  client/Client

  (open! [this test node]
    (info "Starting RSM client connecting to" node)
    (let [c (doto (SyncReplicatedStateMachineClient. node)
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
    (let [[k v] (:value op)]
      (c/with-errors op #{:read}
        (case (:f op)
         :read (let [value (raft-read conn k {:quorum? (:quorum-reads test)})]
                 (assoc op :type :ok, :value (independent/tuple k value)))

         :write (do (raft-write conn k v)
                    (assoc op :type :ok))

         :cas (let [[old new] v
                    succeeded (raft-cas conn k old new)]
                (if succeeded
                  (assoc op :type :ok, :value (independent/tuple k [old new]))
                  (assoc op :type :fail, :error :cas-fail)))))))

  (teardown! [this test])

  (close! [_ test]
    (.close conn)))

(defn maybe-limit
  "Maybe limit the operation per key. If the initial configuration has a negative value,
  we do not limit the operations."
  [opts gen]
  (if (= (:workload :multi-register))
    (gen/limit (:ops-per-key opts) gen)
    (identity gen)))


(defn workload
  "Create a workload for registers. This tests linearizable reads, writes, and
  compare-and-set operations on independent keys."
  [keys opts]
  (let [n (count (:nodes opts))]
    {:client    (ReplicatedStateMachineClient. nil)
     :checker   (independent/checker
                  (checker/compose
                    {:timeline (timeline/html)
                     :linear   (checker/linearizable
                                 {:model     (model/cas-register)
                                  :algorithm :linear})}))
     :generator (independent/concurrent-generator
                  (min (* 2 n) (:concurrency opts))
                  keys
                  (fn [_]
                    (->> (gen/mix [r w cas])
                         (maybe-limit opts))))}))
