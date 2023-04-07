(ns jepsen.jgroups.raft-test
  (:require [clojure.test :refer :all]
            [jepsen.jgroups.workload.counter :as counter]
            [knossos.competition :as competition]))

(deftest test-counter-model-valid
  (testing
    "Checks the counter model can handle info messages."
    (let [history [; Observe these operations interleave. Process 1 reads 0's write before returned.
                   {:process 0, :index 0, :time 1, :type :invoke, :f :add, :value 1}
                   {:process 1, :index 1, :time 2, :type :invoke, :f :read, :value nil}
                   {:process 1, :index 2, :time 3, :type :ok, :f :read, :value 1}
                   {:process 0, :index 3, :time 4, :type :ok, :f :add, :value 1}

                   ; This info operation was never applied.
                   {:process 1, :index 4, :time 5, :type :invoke, :f :add-and-get, :value 1}
                   {:process 1, :index 5, :time 6, :type :info, :f :add-and-get, :value 1}

                   ; Process 0 still reads 0 as the operation was never applied.
                   {:process 0, :index 6, :time 7, :type :invoke, :f :read, :value nil}
                   {:process 0, :index 7, :time 8, :type :ok, :f :read, :value 1}

                   ; Process 2 reads 1 as the operation was applied.
                   {:process 2, :index 8, :time 9, :type :invoke, :f :add-and-get, :value 1}
                   {:process 2, :index 9, :time 10, :type :ok, :f :add-and-get, :value [1 2]}]
          result   (competition/analysis (counter/->CounterModel 0) history)]
      (is (:valid? result)))))

(deftest test-counter-model-invalid
  (testing
    "Checks the counter model identify an invalid serializable history."
    (let [history [{:process 0, :index 0, :time 1, :type :invoke, :f :add, :value 1}
                   {:process 0, :index 1, :time 2, :type :ok, :f :add, :value 1}

                   {:process 0, :index 2, :time 3, :type :invoke, :f :read, :value nil}
                   {:process 0, :index 3, :time 4, :type :ok, :f :read, :value 1}

                   ; Process 1 should have read 1, too.
                   {:process 1, :index 4, :time 5, :type :invoke, :f :read, :value nil}
                   {:process 1, :index 5, :time 6, :type :ok, :f :read, :value 0}]
          result   (competition/analysis (counter/->CounterModel 0) history)]
      (is (not (:valid? result))))))

(deftest test-counter-model-invalid-2
  (testing
    "Checks the counter model identify an invalid state even with info operations."
    (let [history [; Observe these operations interleave. Process 1 reads 0's write before returned.
                   {:process 0, :index 0, :time 1, :type :invoke, :f :add, :value 1}
                   {:process 1, :index 1, :time 2, :type :invoke, :f :read, :value nil}
                   {:process 1, :index 2, :time 3, :type :ok, :f :read, :value 1}
                   {:process 0, :index 3, :time 4, :type :ok, :f :add, :value 1}

                   ; This info operation was applied.
                   {:process 1, :index 4, :time 5, :type :invoke, :f :add-and-get, :value 1}
                   {:process 1, :index 5, :time 6, :type :info, :f :add-and-get, :value 1}

                   ; Process 0 reads 2 as the operation was applied.
                   {:process 0, :index 6, :time 7, :type :invoke, :f :read, :value nil}
                   {:process 0, :index 7, :time 8, :type :ok, :f :read, :value 2}

                   ; Process 2 reads 1, creating an invalid history.
                   {:process 2, :index 8, :time 9, :type :invoke, :f :add-and-get, :value 1}
                   {:process 2, :index 9, :time 10, :type :ok, :f :add-and-get, :value [1 2]}]
          result   (competition/analysis (counter/->CounterModel 0) history)]
      (is (not (:valid? result))))))
