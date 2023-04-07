(ns jepsen.jgroups.workload.client
  (:require
    [clojure.tools.logging :refer :all]
    [slingshot.slingshot :refer [throw+ try+]]))

(defmacro remap-error
  "Evaluates the body, converting to proper errors we can handle."
  [& body]
  `(try+
     ~@body

     ; Timeout can have different causes, and as such, we are not sure
     ; if an operation was applied or not.
     (catch java.util.concurrent.TimeoutException e#
       (throw+
         {:definite? false, :type :timeout, :description (.getMessage e#)}))

     ; Connection exception means we were unable to connect to the remote,
     ; so we can be sure we failed. There is no way we might have applied
     ; operation.
     (catch java.net.ConnectException e#
       (throw+
         {:definite? true, :type :connect, :description (.getMessage e#)}))

     ; SocketException must be after ConnectException.
     ; As this is a broad exception, we can not tell if a change was applied or not.
     (catch java.net.SocketException e#
       (throw+
         {:definite? false, :type :socket, :description (.getMessage e#)}))

     ; This means we were able to send the message, but since no leader
     ; exists to handle the operation, we receive the exception.
     ; We are sure this is a failure.
     (catch org.jgroups.protocols.raft.RaftLeaderException e#
       (throw+
         {:definite? true, :type :no-leader, :description (.getMessage e#)}))

     ; If this was thrown because the node was not the leader, we know it failed.
     ; Otherwise, we are not sure if the operation was applied or not.
     (catch java.lang.IllegalStateException e#
       (throw+
         (condp re-find (.getMessage e#)
           #"I'm not the leader" {:definite? true, :type :no-leader, :description (.getMessage e#)}
           e#)))))

(defn client-error?
  "Check if the exception is a client error."
  [e]
  (and (map? e)
       (contains? e :definite?)))

(defmacro with-errors
  "Takes an operation, a set of idempotent operations and evaluates the body."
  [op idempotent & body]
  `(try+ (remap-error ~@body)
         (catch client-error? e#
           (error "Client error:" e#)
           (assoc ~op
             :type (if (or (:definite? e#)
                           (~idempotent (:f ~op)))
                     :fail
                     :info)
             :error [(:type e#) (:description e#)]))))
