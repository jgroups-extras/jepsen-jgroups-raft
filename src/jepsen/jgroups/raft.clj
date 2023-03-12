(ns jepsen.jgroups.raft
  (:require
    [clojure.tools.logging :refer :all]
    (jepsen
      [cli :as cli]
      [tests :as tests])
    [jepsen.checker :as checker]
    [jepsen.checker.timeline :as timeline]
    [jepsen.generator :as gen]
    [jepsen.jgroups.client :as client]
    [jepsen.jgroups.server :as server]
    [jepsen.os.debian :as debian]
    [knossos.model :as model]))


(defn raft-tests
  "Given an options map from the command line runner, constructs a test map. Special options:

    :rate         Approximate number of requests per second.
    :ops-per-key  Maximum number of operations allowed on any given key.
    :workload     Name of the workload to run."
  [opts]
  (merge tests/noop-test
         opts
         {:pure-generators true
          :name            "jgroups-raft"
          :os              debian/os
          :db              (server/db)
          :client          (client/->ReplicatedStateMachineClient nil)
          :checker         (checker/compose
                             {:perf     (checker/perf)
                              :linear   (checker/linearizable
                                          {:model     (model/cas-register)
                                           :algorithm :linear})
                              :timeline (timeline/html)
                              :exceptions (checker/unhandled-exceptions)})
          :generator       (->> (gen/mix [client/r client/w client/cas])
                                (gen/stagger 1)
                                (gen/nemesis nil)
                                (gen/time-limit 15))}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn raft-tests
                                         :opts args})
                   (cli/serve-cmd))
            args))
