(ns jepsen.jgroups.nemesis.membership
  (:require [clojure.tools.logging :refer :all]
            (jepsen
                        [control :as c]
                        [db :as db]
                        [generator :as gen]
                        [nemesis :as n]
                        [util :as util])
            [jepsen.jgroups.server :as server]
            [slingshot.slingshot :refer [throw+ try+]]))

(defmacro membership-operation!
  [& body]
  `(try+
     ~@body
     (catch (#{:jepsen.control/nonzero-exit
               :jepsen.util/nonzero-exit}
             (:type ~'%)) e#
       (error "Membership operation failed" e#)
       (throw+ {:type ::membership-operation-failed}))))

(def base-client-command
  ["java" "-cp" server/remote-jar "org.jgroups.raft.client.Client"])

(defn add-node-command
  "Command to add a node."
  [host node]
  (when (not= host node)
    (concat base-client-command ["-add" node])))

(defn remove-node-command
  "Command to remove a node."
  [host node]
  (when (not= host node)
    (concat base-client-command ["-remove" node])))

(defn majority!
  "Calculate the majority size of the cluster."
  [test]
  (+ (/ (count (:nodes test)) 2) 1))

(defn addable-nodes
  "What nodes could be added to the cluster"
  [test]
  (remove @(:members test) (:nodes test)))

(defn grow!
  "Adds a random node from the test to the cluster, if possible."
  [test]
  (util/timeout
    15000 (throw+ {:type ::grow-timed-out})
    (if-let [addable-nodes (seq (addable-nodes test))]
      (let [new-node (rand-nth addable-nodes)]
        (info :adding new-node "to" @(:members test))

        ; Mixing member and kill nemesis, the node might not be running.
        (membership-operation!
          (c/on (rand-nth (vec @(:members test)))
                (->> (add-node-command (first @(:members test)) new-node)
                     (apply c/exec*))))

        ; Update the test map to include the new node.
        (swap! (:members test) conj new-node)

        (c/on-nodes test [new-node]
                    (fn [test node]
                      (info "Growing!" :start! (class (:db test)) (pr-str (:db test)))
                      (db/start! (:db test) test node)))
        new-node)
      [:no-addable-nodes @(:members test)])))

(defn shrink!
  "Removes a random node from the cluster, if possible."
  [test]
  (util/timeout
    15000 (throw+ {:type ::shrink-timed-out})

    ; We can't shrink bellow the majority, otherwise,
    ; we will not be able to elect a leader and restore the cluster.
    (if (<= (count @(:members test)) (majority! test))
      [:too-few-members-to-shrink @(:members test)]

      (let [node (rand-nth (vec @(:members test)))
            host (rand-nth (vec (clojure.set/difference @(:members test) [node])))]
        (info :removing node "from" @(:members test))

        ; Stop running before removing from the cluster.
        ; Otherwise, a node might restart and the last command it applies
        ; is removing itself. This will cause the node to fail to start.
        (c/on-nodes test [node]
                    (fn [test node]
                      (db/kill! (:db test) test node)))

        ; Mixing member and kill nemesis, the node might not be running.
        (membership-operation!
          (c/on host
                (->> (remove-node-command host node)
                     (apply c/exec*))))

        ; Finally, remove the node from the list.
        (swap! (:members test) disj node)
        (info "After removal, membership is" @(:members test))
        node))))

(defn member-generator
  "A generator for membership operations."
  [opts]
  (let [grow (fn grow* [_ _] {:type :info, :f :grow})
        shrink (fn shrink* [_ _] {:type :info, :f :shrink})]
    (->> (gen/flip-flop shrink (repeat grow))
         (gen/stagger (:interval opts)))))

; A nemesis for adding and removing nodes from the cluster.
(defrecord MemberNemesis [opts]
  n/Nemesis
  (setup! [this test] this)

  (invoke! [_ test op]
    (assoc op :value
              (try+
                (case (:f op)
                  :grow (grow! test)
                  :shrink (shrink! test))

                ; This can happen for different reasons. For example, the node might not be running
                ; because it was stopped by the kill nemesis.
                (catch (#{::grow-timed-out
                          ::shrink-timed-out}
                        (:type %)) e#
                  (keyword (str (:f op) "-timed-out")))

                ; Failed executing the operation in the remote node.
                ; The CLI command returned a non-zero exit code.
                (catch [:type ::membership-operation-failed] e#
                  :membership-operation-failed))))

  (teardown! [this test])

  n/Reflection
  (fs [_] #{:grow :shrink}))

(defn member-final-generator
  "Emits add events until the cluster is full again"
  [test context]
  (when (seq (addable-nodes test))
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
              :start #{:grow}
              :stop #{:shrink}
              :color "#E9A0E6"}}}))
