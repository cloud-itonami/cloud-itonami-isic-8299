(ns bizsupport.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [bizsupport.store :as store]
            [bizsupport.operation :as op]))

(def dispatcher {:actor-id "di-1" :actor-role :dispatcher})
(def manager    {:actor-id "om-1" :actor-role :ops-manager})

(def clean-decompose
  {:op :task/decompose :subject "tk-400" :task-id "tk-400" :client-id "cl-100"
   :title "General correspondence drafting (demo)" :required-certifications #{}
   :estimated-hours 4 :value-tier :standard})

(def clean-assign
  {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-100"})

(def clean-disclosure
  {:op :disclosure/query :subject "tk-100"})

(def dispute-req
  {:op :dispute/request :subject "tk-100-op-100" :disputed-field :status :claim :disputed})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-decompose dispatcher)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (nil? (store/task s "tk-400")) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "disclosure/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-disclosure {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-decompose
  (testing "a clean decompose that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-decompose dispatcher)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-assign-under-approval
  (let [[_ res] (run 2 clean-assign dispatcher)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest phase3-auto-commits-clean-decompose
  (let [[s res] (run 3 clean-decompose dispatcher)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "General correspondence drafting (demo)" (:title (store/task s "tk-400"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (unmet clearance) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-200"}
                       dispatcher)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest missing-phase-context-does-not-grant-max-autonomy
  (testing "omitting :phase entirely must fall back to the conservative default (1), not phase 3"
    (let [s (store/seed-db)
          actor (op/build s)
          res (g/run* actor {:request clean-decompose :context dispatcher} {:thread-id "no-phase"})]
      (is (= :interrupted (:status res)) "phase 1 forces approval, unlike phase 3's auto-commit"))))

(deftest dispute-request-never-auto-commits-at-any-phase
  (testing "a labor/quality dispute never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph dispute-req manager)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a dispute"))))))
