(ns jepsen.jgroups.workload.leader
  (:require
    [clojure.tools.logging :refer :all]
    [jepsen.checker :as checker]
    [jepsen.checker.timeline :as timeline]
    [jepsen.generator :as gen]
    [jepsen.jgroups.workload.client :as c]
    [knossos.model :as model])
  (:import
    (java.net InetAddress)
    (knossos.model Model)
    (org.jgroups.raft.client SyncLeaderInspectionClient)))

(defn inspect
  "An inspection operation that return the current leader and term."
  [_ _]
  {:type :invoke, :f :inspect, :value [nil 0]})

(defn leader-inspect
  "Inspect who the client thinks is the current leader and term."
  [conn]
  (.inspect conn))

(defrecord LeaderInspectionClient [conn]
  jepsen.client/Client

  (open! [this test node]
    (info "Starting leader inspection connected to" node)
    (let [c (doto (SyncLeaderInspectionClient. node)
              (.withTimeout (long (* 1000 (:operation-timeout test))))
              (.withTargetAddress (InetAddress/getByName node))
              (.withTargetPort 9000))]
      (.start c)
      (assoc this :conn c)))

  (setup! [this test])

  (invoke! [this test op]
    (c/with-errors op #{:inspect}
                   (assoc op :type :ok, :value (leader-inspect conn))))

  (teardown! [this test])

  (close! [_ test]
    (.close conn)))

(defn highest-term
  "Returns the highest term in the given state."
  [state]
  (->> (keys state)
       (reduce max)))

(defn serialize-leader
  "Serialize the leader name."
  [addr]
  (if (nil? addr) "null" addr))

;; Create the model to verify leader and terms.
;; The model verifies that one term has only one leader. If there are different leaders mapping to the same term,
;; an inconsistent error is raised.
;; Observe that this not verifies for majority or anything like that. It could be improved to keep track of the
;; node who replied and assert that a majority of members sees the same leader.
(defrecord LeaderModel [state]
  Model

  (step [r op]
    (condp = (:f op)
      :inspect (let [[inspected t _] (:value op)
                     l (serialize-leader inspected)]
                 (cond
                   (empty? state) (LeaderModel. {t l})
                   (contains? state t) (if (= (state t) l)
                                         r
                                         (model/inconsistent (str "leader at " t " was " (state t) " but received " l)))
                   :else (LeaderModel. (assoc state t l)))))))

(defn workload
  "A workload to verify the leader election correctness."
  [opts]
  {:client    (LeaderInspectionClient. nil)
   :checker   (checker/compose
                {:timeline (timeline/html)
                 :linear   (checker/linearizable
                             {:model     (LeaderModel. {})
                              :algorithm :linear})})
   :generator (->> (gen/mix [inspect]))})