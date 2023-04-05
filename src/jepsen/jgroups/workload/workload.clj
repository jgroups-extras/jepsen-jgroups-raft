(ns jepsen.jgroups.workload.workload
  (:require
    [jepsen.jgroups.workload.register :as register]))

(def all-workloads
  #{:single-register :multi-register :counter})

(def workloads
  "A map of workloads to the corresponding constructor."
  {:single-register register/workload
   :multi-register  register/workload
   :counter         nil})
