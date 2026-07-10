(ns bizsupport.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`'s policy_contract_test. The single invariant
  under test:

    TaskRouter-LLM never assigns/discloses/resolves a record the
    RoutingGovernor would reject, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [bizsupport.store :as store]
            [bizsupport.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def dispatcher {:actor-id "di-1" :actor-role :dispatcher :phase 3})
(def manager    {:actor-id "om-1" :actor-role :ops-manager :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-decompose-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :task/decompose :subject "tk-9" :task-id "tk-9" :client-id "cl-1"
                   :title "X" :required-certifications #{} :estimated-hours 2 :value-tier :standard}
                  dispatcher)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "X" (:title (store/task db "tk-9"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :client role has no decompose permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :task/decompose :subject "tk-9" :task-id "tk-9" :client-id "cl-1"
                     :title "X" :required-certifications #{} :estimated-hours 2 :value-tier :standard}
                    {:actor-id "cl-1" :actor-role :client :phase 3})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/task db "tk-9")) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest unmet-clearance-is-held
  (testing "an operator lacking a task's required certification → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-200"}
                    dispatcher)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:clearance-tier-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assignment db "tk-100-op-200"))))))

(deftest capacity-overcommit-is-held
  (testing "an assignment that would push an operator past weekly capacity → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :task/assign :subject "tk-300" :task-id "tk-300" :operator-id "op-200"}
                    dispatcher)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:capacity-gate} (-> (store/ledger db) first :basis)))
      (is (= 18 (:committed-hours (store/operator db "op-200"))) "unchanged"))))

(deftest leaky-decompose-with-client-pii-is-held
  (testing "a proposal smuggling a schema-excluded client PII field → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :task/decompose :subject "tk-9" :task-id "tk-9" :client-id "cl-1"
                     :title "X" :required-certifications #{} :estimated-hours 2 :value-tier :standard
                     :leaky? true}
                    dispatcher)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:scope-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/task db "tk-9"))))))

(deftest uncontracted-disclosure-is-held
  (testing "a disclosure query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :disclosure/query :subject "tk-100"}
                    {:actor-id "cl-2" :actor-role :client :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-disclosure-beyond-tier-is-held
  (testing "a disclosure query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :disclosure/query :subject "tk-100" :greedy? true}
                    {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest clean-disclosure-within-tier-commits-directly
  (testing "a clean, in-tier disclosure query auto-serves (it's a governed read)"
    (let [[_db actor] (fresh)
          res (exec-op actor "t7b"
                    {:op :disclosure/query :subject "tk-100"}
                    {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest high-value-task-escalates-then-human-decides
  (testing "an otherwise-clean assignment on a :high-value task interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t8"
                   {:op :task/assign :subject "tk-200" :task-id "tk-200" :operator-id "op-100"}
                   dispatcher)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :high-value-task (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "ops-1"}}
                         {:thread-id "t8" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :assigned (:status (store/assignment db "tk-200-op-100"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t9"
                  {:op :task/assign :subject "tk-200" :task-id "tk-200" :operator-id "op-100"}
                  dispatcher)
          r2 (g/run* actor {:approval {:status :rejected :by "ops-1"}}
                     {:thread-id "t9" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (nil? (store/assignment db "tk-200-op-100"))))))

(deftest dispute-request-always-escalates-regardless-of-confidence
  (testing "a dispute request always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t10"
                   {:op :dispute/request :subject "tk-100-op-100" :disputed-field :status :claim :disputed}
                   manager)]
      (is (= :interrupted (:status r1)))
      (is (= :dispute (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the resolution"
        (let [r2 (g/run* actor {:approval {:status :approved :by "ops-1"}}
                         {:thread-id "t10" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :disputed (:status (store/assignment db "tk-100-op-100")))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :task/decompose :subject "tk-9" :task-id "tk-9" :client-id "cl-1"
                          :title "X" :required-certifications #{} :estimated-hours 2 :value-tier :standard}
               dispatcher)
      (exec-op actor "b" {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-200"}
               dispatcher)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
