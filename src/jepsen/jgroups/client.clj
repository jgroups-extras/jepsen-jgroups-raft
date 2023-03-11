(ns jepsen.jgroups.client
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [client :as client]))
  (:import
    (java.net InetAddress)
    (org.jgroups.raft.client SyncReplicatedStateMachineClient)))

(defn r
  "Read operations using the JGroups-RAFT client."
  [_ _]
  {:type :invoke, :f :read, :value nil})

(defn w
  "Write operation using the JGroups-RAFT client."
  [_ _]
  {:type :invoke, :f :write, :value (rand-int 5)})

(defn raft-read
  "Reads the given key from the given state machine."
  [conn key]
  (.get conn key))

(defn raft-write
  "Writes the given value to the key using the state machine."
  [conn key value]
  (.put conn key value))

(defrecord ReplicatedStateMachineClient [conn]
  client/Client

  (open! [this test node]
    (let [c (doto (SyncReplicatedStateMachineClient.)
              (.timeout (long 30000)))]
      (.start c (InetAddress/getByName node) 9000)
      (assoc this :conn c)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :read (assoc op :type :ok, :value (raft-read conn "foo"))
      :write (do (raft-write conn "foo" (:value op))
                 (assoc op :type :ok))))

  (teardown! [this test])

  (close! [_ test]
    (.close conn)))
