(ns jepsen.jgroups.workload.workload
  (:require
    [jepsen.jgroups.workload.register :as register]
    [jepsen.jgroups.workload.counter :as counter]))

(def all-workloads
  #{:single-register :multi-register :counter})

(def workloads
  "A map of workloads to the corresponding constructor."
  {:single-register (partial register/workload (range 1))
   :multi-register  (partial register/workload (range))
   :counter         counter/workload})
