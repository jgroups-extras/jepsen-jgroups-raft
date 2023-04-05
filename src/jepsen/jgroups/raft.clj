(ns jepsen.jgroups.raft
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [cli :as cli]
      [tests :as tests])
    [jepsen.checker :as checker]
    [jepsen.generator :as gen]
    [jepsen.jgroups.nemesis.nemesis :as ln]
    [jepsen.jgroups.server :as server]
    [jepsen.jgroups.workload.workload :as lw]
    [jepsen.os.debian :as debian]))

(def cli-opts
  "Additional command line options."
  [[nil "--stale-reads" "Accept stale reads when retrieving values."
    :default false]

   ["-r" "--rate HZ" "Approximate number of requests per second per thread."
    :default 10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number."]]

   [nil "--ops-per-key NUM" "Maximum number of operations on any given key."
    :default 100
    :parse-fn parse-long]

   [nil "--nemesis FAULTS" "A comma-separated list of nemesis"
    :default {}
    :parse-fn ln/parse-nemesis-spec
    :validate [(partial every? (fn [n]
                                 (or (ln/nemeses n)
                                     (ln/special-nemeses n))))
               (cli/one-of (concat ln/nemeses (keys ln/special-nemeses)))]]

   [nil "--workload NAME" "Name of the workload to run."
    :default :single-register
    :parse-fn keyword
    :validate [(partial contains? lw/all-workloads)
               (cli/one-of lw/all-workloads)]]

   ["-i" "--interval SECONDS" "How long between nemesis operations for each class of fault."
    :default 5
    :parse-fn read-string
    :validate [pos? "Must be positive"]]

   [nil "--operation-timeout SECONDS" "How long to wait for an operation to complete."
    :default 10
    :parse-fn read-string
    :validate [pos? "Must be positive"]]])


(defn raft-tests
  "Given an options map from the command line runner, constructs a test map. Special options:

    :rate         Approximate number of requests per second.
    :ops-per-key  Maximum number of operations allowed on any given key.
    :workload     Name of the workload to run."
  [opts]
  (let [db (server/db opts)
        nemesis (ln/setup-nemesis opts db)
        workload ((lw/workloads (:workload opts)) opts)]
    (merge tests/noop-test
           opts
           {:pure-generators true
            :name            "jgroups-raft"
            :os              debian/os
            :db              db
            :members         (atom (into (sorted-set) (:nodes opts)))
            :nemesis         (:nemesis nemesis)
            :client          (:client workload)
            :checker         (checker/compose
                               {:perf       (checker/perf {:nemeses (:perf nemesis)})
                                :exceptions (checker/unhandled-exceptions)
                                :stats      (checker/stats)
                                :workload   (:checker workload)})
            :generator       (gen/phases
                               (->> (:generator workload)
                                    (gen/stagger (/ (:rate opts)))
                                    (gen/nemesis
                                      (gen/phases
                                        (gen/sleep (:interval opts))
                                        (:generator nemesis)))
                                    (gen/time-limit (:time-limit opts)))
                               (gen/log "Healing cluster")
                               (gen/nemesis (:final-generator nemesis))
                               (gen/log "Waiting for recovery")
                               (gen/sleep 10)
                               (gen/clients (:final-generator workload)))
            :quorum-reads    (not (:stale-reads opts))})))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn  raft-tests
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
