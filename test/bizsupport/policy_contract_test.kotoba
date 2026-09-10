(ns bizsupport.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`'s policy_contract_test. The single invariant
  under test:

    TaskRouter-LLM never assigns/discloses/resolves a record the
    RoutingGovernor would reject, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [bizsupport.llm :as llm]
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

;; ───────────────── operator-pool recruitment governor contract ─────────────
;; The invariant: this actor can PREPARE, GOVERN and RECORD who joins the
;; operator pool, but never decides it. Every admission/decline reaches a
;; human at every phase, and the applicant-side checks are HARD.

(defn- apply-request [overrides]
  (merge {:op :operator/apply :subject "cand-900" :candidate-id "cand-900"
          :handle "test-applicant" :claimed-certifications #{:soc2}
          :weekly-capacity-hours 10 :remote? true
          :contact-ref "gh-issue:example/repo#1" :referral-source :public-board}
         overrides))

(deftest application-is-recorded-but-never-lands-in-the-operator-pool
  (let [[db actor] (fresh)
        res (exec-op actor "r1" (apply-request {}) dispatcher)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :candidate (:status (store/candidate db "cand-900"))))
    (is (nil? (store/operator db "cand-900"))
        "recording an application must not make the person assignable")))

(deftest admission-always-reaches-a-human-even-at-phase-3-and-full-confidence
  (let [[db actor] (fresh)
        res (exec-op actor "r2" {:op :operator/admit :subject "cand-100"
                                 :candidate-id "cand-100"} manager)]
    (is (= :interrupted (:status res)) "admission pauses for a human")
    (is (nil? (store/operator db "cand-100")) "nobody joins the pool before sign-off")
    (let [resumed (g/run* actor {:approval {:status :approved :by "om-1"}}
                          {:thread-id "r2" :resume? true})]
      (is (= :commit (get-in resumed [:state :disposition])))
      (is (some? (store/operator db "cand-100")) "admitted only after the human approved")
      (is (= 0 (:committed-hours (store/operator db "cand-100")))))))

(deftest decline-also-always-reaches-a-human
  (let [[db actor] (fresh)
        res (exec-op actor "r3" {:op :operator/decline :subject "cand-100"
                                 :candidate-id "cand-100" :reason :out-of-scope} manager)]
    (is (= :interrupted (:status res)))
    (is (= :candidate (:status (store/candidate db "cand-100")))
        "no state change before the human decides")))

(deftest rejected-admission-leaves-the-application-open
  (let [[db actor] (fresh)]
    (exec-op actor "r4" {:op :operator/admit :subject "cand-100" :candidate-id "cand-100"} manager)
    (let [resumed (g/run* actor {:approval {:status :rejected :by "om-1"}}
                          {:thread-id "r4" :resume? true})]
      (is (= :hold (get-in resumed [:state :disposition])))
      (is (nil? (store/operator db "cand-100")))
      (is (= :candidate (:status (store/candidate db "cand-100")))))))

(deftest out-of-catalog-certification-claim-is-a-hard-hold
  (let [[db actor] (fresh)
        res (exec-op actor "r5" (apply-request {:candidate-id "cand-901" :subject "cand-901"
                                                :claimed-certifications #{:self-declared}})
                     dispatcher)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #(= :certification-claim-gate (:rule %))
              (get-in res [:state :verdict :violations])))
    (is (nil? (store/candidate db "cand-901")))))

(deftest impossible-capacity-claim-is-a-hard-hold
  (let [[_ actor] (fresh)]
    (doseq [[label hours] [["zero" 0] ["negative" -5] ["more hours than a week has" 200]
                           ["not a number" "ろくじかん"]]]
      (testing label
        (let [res (exec-op actor (str "r6-" label)
                           (apply-request {:candidate-id (str "cand-902-" label)
                                           :subject (str "cand-902-" label)
                                           :weekly-capacity-hours hours})
                           dispatcher)]
          (is (= :hold (get-in res [:state :disposition])))
          (is (some #(= :capacity-claim-gate (:rule %))
                    (get-in res [:state :verdict :violations]))))))))

(deftest applicant-pii-in-a-proposal-is-a-hard-hold
  ;; The schema has no field for a legal name; this proves the governor
  ;; also refuses one an advisor tries to smuggle in anyway.
  (let [pii-advisor (reify llm/Advisor
                      (-advise [_ _ _]
                        {:summary "x" :rationale "y" :cites [] :effect :candidate-upsert
                         :value {:id "cand-903" :handle "h" :claimed-certifications #{:soc2}
                                 :weekly-capacity-hours 10
                                 :applicant-legal-name "実名 太郎"}
                         :confidence 0.95}))
        db (store/seed-db)
        actor (op/build db {:advisor pii-advisor})
        res (exec-op actor "r7" (apply-request {:candidate-id "cand-903" :subject "cand-903"})
                     dispatcher)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #(= :scope-gate (:rule %)) (get-in res [:state :verdict :violations])))
    (is (nil? (store/candidate db "cand-903")))))

(deftest duplicate-application-and-double-admission-are-hard-holds
  (let [[db actor] (fresh)]
    (testing "an id already in the pool cannot be re-applied"
      (let [res (exec-op actor "r8" (apply-request {:candidate-id "op-100" :subject "op-100"})
                         dispatcher)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #(= :candidate-lifecycle (:rule %)) (get-in res [:state :verdict :violations])))
        (is (= 30 (:committed-hours (store/operator db "op-100")))
            "the pool member's own record was not merged over")))
    (testing "an application already on file cannot be re-applied"
      (let [res (exec-op actor "r9" (apply-request {:candidate-id "cand-100" :subject "cand-100"})
                         dispatcher)]
        (is (= :hold (get-in res [:state :disposition])))))
    (testing "an already-admitted application cannot be admitted again"
      (store/commit-record! db {:effect :candidate-admit
                                :value {:candidate-id "cand-100" :certifications #{:soc2}
                                        :weekly-capacity-hours 12}})
      (let [res (exec-op actor "r10" {:op :operator/admit :subject "cand-100"
                                      :candidate-id "cand-100"} manager)]
        (is (= :hold (get-in res [:state :disposition])))))))

(deftest admission-of-a-sanctions-hit-is-a-hard-hold-no-human-override
  (let [db (store/seed-db)
        _  (store/commit-record! db {:effect :screening-verdict-set
                                     :value {:operator-id "cand-100" :verdict :hit}})
        actor (op/build db)
        res (exec-op actor "r11" {:op :operator/admit :subject "cand-100"
                                  :candidate-id "cand-100"} manager)]
    (is (= :hold (get-in res [:state :disposition]))
        "a screening hit holds BEFORE any human is asked -- not escalated for approval")
    (is (some #(= :sanctions-screening-gate (:rule %)) (get-in res [:state :verdict :violations])))
    (is (nil? (store/operator db "cand-100")))))

(deftest dispatcher-cannot-admit
  (let [[_ actor] (fresh)
        res (exec-op actor "r12" {:op :operator/admit :subject "cand-100"
                                  :candidate-id "cand-100"} dispatcher)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #(= :rbac (:rule %)) (get-in res [:state :verdict :violations])))))

(deftest assignment-to-an-unadmitted-applicant-is-a-hard-hold
  (let [db (store/seed-db)
        ;; a zero-hour task would otherwise slip past the capacity gate
        _ (store/commit-record! db {:effect :task-upsert
                                    :value {:id "tk-zero" :client-id "cl-1" :title "0h"
                                            :required-certifications #{} :estimated-hours 0
                                            :value-tier :standard :status :open}})
        actor (op/build db)
        res (exec-op actor "r13" {:op :task/assign :subject "tk-zero"
                                  :task-id "tk-zero" :operator-id "cand-100"} dispatcher)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #(= :unknown-operator (:rule %)) (get-in res [:state :verdict :violations])))
    (is (empty? (store/assignments-of-operator db "cand-100")))))
