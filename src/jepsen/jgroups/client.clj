(ns jepsen.jgroups.client
  "The state machine client. This client executes the operations on a single register."
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [client :as client])
    [slingshot.slingshot :refer [try+]])
  (:import
    (java.net InetAddress)
    (org.jgroups.raft.client SyncReplicatedStateMachineClient)
    (org.jgroups.raft.exception KeyNotFoundException)))

(def register-name "foo")
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
  [conn key]
  (parse-response (.get conn key)))

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
    (info "Starting client" node)
    (let [c (doto (SyncReplicatedStateMachineClient. node)
              (.withTimeout (long 30000))
              (.withTargetAddress (InetAddress/getByName node))
              (.withTargetPort 9000))]
      (.start c)
      (assoc this :conn c)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :read (assoc op :type :ok, :value (raft-read conn register-name))
      :write (do (raft-write conn register-name (:value op))
                 (assoc op :type :ok))
      :cas (try+
             (let [[old new] (:value op)]
               (assoc op :type (if (raft-cas conn register-name old new)
                                 :ok
                                 :fail)))
             (catch KeyNotFoundException ex
               (assoc op :type :fail, :error (.getCode ex))))))

  (teardown! [this test])

  (close! [_ test]
    (.close conn)))
