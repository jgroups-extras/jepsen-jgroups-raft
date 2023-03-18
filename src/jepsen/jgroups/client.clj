(ns jepsen.jgroups.client
  "The state machine client. This client executes the operations on a single register."
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [client :as client])
    [jepsen.independent :as independent]
    [slingshot.slingshot :refer [try+]])
  (:import
    (java.net ConnectException InetAddress SocketException)
    (java.util.concurrent TimeoutException)
    (org.jgroups.protocols.raft RaftLeaderException)
    (org.jgroups.raft.client SyncReplicatedStateMachineClient)
    (org.jgroups.raft.exception KeyNotFoundException)))

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
    (info "Starting client connecting to" node)
    (let [c (doto (SyncReplicatedStateMachineClient. node)
              ; A small timeout can cause a lot of pressure for the model checker.
              ; Small here would be less than or equal to the nemesis interval.
              (.withTimeout (long (:interval test 5000) + 1000))
              (.withTargetAddress (InetAddress/getByName node))
              (.withTargetPort 9000))]
      (.start c)
      (assoc this :conn c)))

  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)]
      (try+
        (case (:f op)
          :read (let [value (raft-read conn k {:quorum? (:quorum-reads test)})]
                  (assoc op :type :ok, :value (independent/tuple k value)))

          :write (do (raft-write conn k v)
                     (assoc op :type :ok))

          :cas (let [[old new] v]
                 (assoc op :type (if (raft-cas conn k old new)
                                   :ok
                                   :fail))))

        ; Thrown by the CAS operation.
        (catch KeyNotFoundException ex
          (assoc op :type :fail, :error :key-not-found))

        ; Timeout can have different causes, and as such, we are not sure
        ; if an operation was applied or not.
        ; We only set :fail for :read operations, since they do not change
        ; the state machine. For all else, we use :info.
        (catch TimeoutException ex
          (assoc op
            :type (if (= :read (:f op)) :fail :info) :error :timeout))

        ; We can not tell if a change was applied or not. We only set :fail
        ; for :read operations, since they do not change the state machine.
        ; For all else, we use :info.
        (catch SocketException ex
          (assoc op
            :type (if (= :read (:f op)) :fail :info) :error :socket))

        ; Connection exception means we were unable to connect to the remote,
        ; so we can be sure we failed. There is no way we might have applied
        ; operation.
        (catch ConnectException ex
          (assoc op :type :fail, :error :connect))

        ; This means we were able to send the message, but since no leader
        ; exists to handle the operation, we receive the exception.
        ; We are sure this is a failure.
        (catch RaftLeaderException ex
          (assoc op :type :fail, :error :no-leader))

        ; If this was thrown because the node was not the leader, or it was a :read operation,
        ; we know it failed. Otherwise, we are not sure if the operation was applied or not.
        (catch IllegalStateException ex
          (if (or (re-find #"I'm not the leader" (.getMessage ex)) (= :read (:f op)))
            (assoc op :type :fail, :error :no-leader)
            (assoc op :type :info, :error :illegal-state))))))

  (teardown! [this test])

  (close! [_ test]
    (.close conn)))
