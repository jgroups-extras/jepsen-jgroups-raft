(ns jgroups.raft.server
  "A wrapper around the server. This receives the options and initializes the state machine."
  (:gen-class)
  (:require
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :refer :all]
    [slingshot.slingshot :refer [try+]])
  (:import (java.lang ProcessHandle)
           (java.net InetAddress)
           (org.jgroups.raft.server Server)))

(def opt-spec
  [["-m" "--members MEMBER-LIST" "Comma-separated list of peers to connect to"
    :parse-fn identity]
   ["-n" "--name NAME" "String with the node's name"
    :parse-fn identity]
   ["-p" "--props PATH" "Path to the configuration file"
    :parse-fn identity]
   ["-s" "--state-machine NAME" "State machine to execute."
    :default :register
    :parse-fn keyword]])

(defn -main
  "Run and configure the server."
  [& args]
  (let [{:keys [options
                arguments
                summary
                errors]} (cli/parse-opts args opt-spec)
        members (:members options)
        name (:name options)
        props (:props options)
        s (doto (Server.)
            (.withName name)
            (.withMembers members)
            (.withProps props)
            (.withTimeout (long 30000)))]
    (case (:state-machine options)
      :register (.prepareReplicatedMapStateMachine s)
      :counter  (.prepareCounterStateMachine s)
      :election (.prepareElectionInspection s))
    (try+
      (.start s (InetAddress/getByName name) 9000)
      (catch Throwable t
        (error "Failed starting" (.pid (ProcessHandle/current)) "and exiting..." t)
        (System/exit 1)))))
