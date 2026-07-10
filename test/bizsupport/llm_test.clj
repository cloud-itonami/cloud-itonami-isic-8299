(ns bizsupport.llm-test
  "TaskRouter-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [bizsupport.llm :as llm]))

(deftest decompose-proposal-carries-structured-fields
  (let [p (llm/infer {:op :task/decompose :subject "tk-9" :task-id "tk-9" :client-id "cl-1"
                      :title "X" :required-certifications #{} :estimated-hours 2 :value-tier :standard})]
    (is (= :task-upsert (:effect p)))
    (is (= "tk-9" (get-in p [:value :id])))
    (is (>= (:confidence p) 0.9))))

(deftest leaky-decompose-proposal-contains-the-excluded-field
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [p (llm/infer {:op :task/decompose :subject "tk-9" :task-id "tk-9" :client-id "cl-1"
                        :title "X" :required-certifications #{} :estimated-hours 2 :value-tier :standard
                        :leaky? true})]
      (is (contains? (:value p) :client-ssn)))))

(deftest assign-proposal-carries-task-and-operator
  (let [p (llm/infer {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-200"})]
    (is (= :assignment-upsert (:effect p)))
    (is (= "op-200" (get-in p [:value :operator-id])))
    (is (>= (:confidence p) 0.9) "high confidence even for a pairing the governor will reject — proves clearance/capacity gates cannot rely on confidence")))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [clean (llm/infer {:op :disclosure/query :subject "tk-100"})
        greedy (llm/infer {:op :disclosure/query :subject "tk-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:operator-name :raw-source} (:columns greedy)))))

(deftest dispute-proposal-never-marks-high-confidence
  (let [p (llm/infer {:op :dispute/request :subject "tk-100-op-100" :disputed-field :status :claim :disputed})]
    (is (= :dispute-apply (:effect p)))
    (is (< (:confidence p) 0.9) "disputes are claims pending human verification, never auto-confident")))
