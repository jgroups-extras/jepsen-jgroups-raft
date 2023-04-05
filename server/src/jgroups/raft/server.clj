(ns jgroups.raft.server
  "A wrapper around the server. This receives the options and initializes the state machine."
  (:gen-class)
  (:require
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :refer :all])
  (:import (java.net InetAddress)
           (org.jgroups.raft.server Server)))

(def opt-spec
  [["-m" "--members MEMBER-LIST" "Comma-separated list of peers to connect to"
    :parse-fn (fn [m] m)]
   ["-n" "--name NAME" "String with the node's name"
    :parse-fn (fn [n] n)]
   ["-p" "--props PATH" "Path to the configuration file"
    :parse-fn (fn [p] p)]])

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
    (.start s (InetAddress/getByName name) 9000)))
