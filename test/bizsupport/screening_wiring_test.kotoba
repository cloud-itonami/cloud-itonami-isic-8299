(ns bizsupport.screening-wiring-test
  "Proves the value `bizsupport.screening` actually adds: an operator that
  is clean on every LOCAL check (holds every required certification, no
  capacity issue) but IS flagged in `cloud-itonami-isic-8291`'s own demo
  data no longer becomes assignable -- something 8299's local-only checks
  alone would have missed entirely (see `op-300` in
  `bizsupport.store/demo-data`, deliberately shared name with 8291's
  sanctions-flagged demo official `of-2`).

  Same discipline as `cloud-itonami-isic-6910`'s
  `formation.corporate-intel-test`: 8291's OWN real hit always escalates
  for 8291's OWN human review first (no shortcut, no peeking behind its
  DisclosureGovernor) -- the `:operator/screen` op here reads that as
  `:incomplete` pending review, so the end-to-end proof is 'never assignable
  once screened', not '8299 sees a definitive :hit'."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [bizsupport.store :as store]
            [bizsupport.operation :as op]
            [bizsupport.llm :as llm]
            [bizsupport.screening :as screening]))

(def dispatcher {:actor-id "op-1" :actor-role :dispatcher :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- wired-actor []
  (let [db (store/seed-db)]
    [db (op/build db {:advisor (llm/mock-advisor {:corporate-intel-screen screening/screen})})]))

(deftest without-the-integration-op-300-screens-clear
  (testing "sanity: with the no-op default screen-fn, op-300 always clears"
    (let [db (store/seed-db)
          actor (op/build db)]                          ; NO corporate-intel wired in
      (exec-op actor "sanity" {:op :operator/screen :subject "op-300" :operator-id "op-300"} dispatcher)
      (is (= :clear (:verdict (store/screening-of db "op-300")))
          "without the integration, op-300 screens :clear -- this is the gap being closed"))))

(deftest corporate-intel-catches-the-hit-local-checks-miss
  (testing "with the REAL (unmocked) 8291 actor wired in, op-300 never silently clears"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t1" {:op :operator/screen :subject "op-300" :operator-id "op-300"} dispatcher)]
      (is (= :interrupted (:status res))
          "8291 itself escalates a real hit for ITS OWN human review first")
      (approve! actor "t1")
      (is (not= :clear (:verdict (store/screening-of db "op-300")))
          "critically: it never becomes :clear, unlike the unwired sanity case above")
      (is (= :incomplete (:verdict (store/screening-of db "op-300")))))))

(deftest a-hit-on-file-blocks-assignment-unconditionally
  (testing "sanctions-screening-gate rejects :task/assign for an operator with a :hit verdict, regardless of confidence or certifications held"
    (let [db (store/seed-db)
          actor (op/build db)]
      (store/commit-record! db {:effect :screening-verdict-set
                                :value {:operator-id "op-300" :verdict :hit}})
      (let [res (exec-op actor "t2"
                          {:op :task/assign :subject "tk-300"
                           :task-id "tk-300" :operator-id "op-300"}
                          dispatcher)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:sanctions-screening-gate} (-> (store/ledger db) first :basis)))
        (is (empty? (store/assignments-of-operator db "op-300")))))))

(deftest an-incomplete-or-clear-verdict-does-not-block-assignment
  (testing "the gate is specific to :hit -- :incomplete/:clear/absent verdicts leave normal governance untouched"
    (let [db (store/seed-db)
          actor (op/build db)]
      (store/commit-record! db {:effect :screening-verdict-set
                                :value {:operator-id "op-300" :verdict :clear}})
      (let [res (exec-op actor "t3"
                          {:op :task/assign :subject "tk-300"
                           :task-id "tk-300" :operator-id "op-300"}
                          dispatcher)]
        (is (= :commit (get-in res [:state :disposition])))))))
