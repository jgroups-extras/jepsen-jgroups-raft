(ns jepsen.jgroups.nemesis.membership
  (:require [jepsen
             [nemesis :as n]
             [generator :as gen]]
            [slingshot.slingshot :refer [try+]]
            [jepsen.jgroups.server :as db]))

(defn member-generator
  "A generator for membership operations."
  [opts]
  (->> (gen/mix [(repeat {:type :info, :f :grow})
                 (repeat {:type :info, :f :shrink})])
       (gen/stagger (:interval opts))))

; A nemesis for adding and removing nodes from the cluster.
(defrecord MemberNemesis [opts]
  n/Nemesis
  (setup! [this test] this)

  (invoke! [this test op]
    (assoc op :value
              (try+
                (case (:f op)
                  :grow (db/grow! test)
                  :shrink (db/shrink! test))
                (catch [:type :jepsen.jgroups.server/blank-member-name] e
                  :blank-member-name)
                (catch [:type :jepsen.jgroups.server/no-members] e
                  :no-members))))

  (teardown! [this test])

  n/Reflection
  (fs [_] #{:grow :shrink}))

(defn member-final-generator
  "Emits add events until the cluster is full again"
  [test context]
  (when (seq (db/addable-nodes test))
    {:type :info, :f :grow}))

(defn member-package
  "Combines the package for the membership nemesis. The membership nemesis applies operations
  to add and remove members. The cluster start full, we apply operations"
  [opts]
  (when ((:faults opts) :member)
    {:nemesis         (->MemberNemesis opts)
     :generator       (member-generator opts)
     :final-generator (->> member-final-generator
                           (gen/delay 1)
                           (gen/time-limit 60))
     :perf #{{:name "grow"
              :fs [:grow]
              :color "#E9A0E6"}
             {:name "shrink"
              :fs [:shrink]
              :color "#ACA0E9"}}}))
