(ns bizsupport.sim
  "Demo runner: push eight representative operations through one
  OperationActor and watch the RoutingGovernor + approval workflow earn
  the TaskRouter-LLM the right to assign, disclose or resolve a dispute.

    op1  タスク分解(個人情報なし・正当)                    → commit
    op2  資格保持済み operator への割当(正当)              → commit
    op3  必須証明を保持しない operator への割当             → clearance-tier-gate REJECT → hold
    op4  週間キャパシティを超過する割当                     → capacity-gate REJECT → hold
    op5  タスク分解に顧客個人情報が混入(スキーマ外)         → scope-gate REJECT → hold
    op6  開示クエリが tier/basic 契約なのに過剰列を要求      → licensed-disclosure REJECT → hold
    op6a 開示クエリが未契約 tenant から                     → licensed-disclosure REJECT → hold
    op7  高額(:high-value)タスクへの割当(他は正常)          → 人間承認へ escalate → approve → commit
    op8  紛争申立て(どの phase でも常に人間レビュー)        → escalate → approve → commit

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [bizsupport.store :as store]
            [bizsupport.operation :as op]
            [bizsupport.facts :as facts]
            [bizsupport.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, an ops manager 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "ops-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        ;; :phase 3 (supervised-auto) explicitly -- default-phase is 1
        ;; (assisted, no auto-commit) so this demo can showcase the full
        ;; governed contract end to end.
        dispatcher {:actor-id "di-1" :actor-role :dispatcher :phase 3}
        manager    {:actor-id "om-1" :actor-role :ops-manager :phase 3}]

    (line "── R0 証明区分カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (TaskRouter-LLM sealed; RoutingGovernor active) ──")

    (line "\nop1  タスク分解(個人情報なし・正当)")
    (run-op! actor "op1"
             {:op :task/decompose :subject "tk-400" :task-id "tk-400" :client-id "cl-100"
              :title "General correspondence drafting (demo)" :required-certifications #{}
              :estimated-hours 4 :value-tier :standard}
             dispatcher true)

    (line "\nop2  資格保持済み operator への割当(正当)")
    (run-op! actor "op2"
             {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-100"}
             dispatcher true)

    (line "\nop3  必須証明(HIPAA)を保持しない operator への割当")
    (run-op! actor "op3"
             {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-200"}
             dispatcher true)

    (line "\nop4  週間キャパシティを超過する割当(25h タスク → 残18hのoperator、上限20h)")
    (run-op! actor "op4"
             {:op :task/assign :subject "tk-300" :task-id "tk-300" :operator-id "op-200"}
             dispatcher true)

    (line "\nop5  タスク分解 — TaskRouter-LLM が顧客個人情報を紛れ込ませる(スキーマ外)")
    (run-op! actor "op5"
             {:op :task/decompose :subject "tk-500" :task-id "tk-500" :client-id "cl-200"
              :title "Sensitive intake (demo)" :required-certifications #{} :estimated-hours 2
              :value-tier :standard :leaky? true}
             dispatcher true)

    (line "\nop6  開示クエリ(tier/basic 契約なのに operator-name/raw-source まで要求)")
    (run-op! actor "op6"
             {:op :disclosure/query :subject "tk-100" :greedy? true}
             {:actor-id "cl-1" :actor-role :client :tenant "tenant-basic"} true)

    (line "\nop6a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op6a"
             {:op :disclosure/query :subject "tk-100"}
             {:actor-id "cl-2" :actor-role :client :tenant "tenant-ghost"} true)

    (line "\nop7  高額(:high-value)タスクへの割当(証明・キャパシティは正常でも人間承認)")
    (run-op! actor "op7"
             {:op :task/assign :subject "tk-200" :task-id "tk-200" :operator-id "op-100"}
             dispatcher true)

    (line "\nop8  紛争申立て — 成果物品質への異議(どの phase でも常に人間レビュー)")
    (run-op! actor "op8"
             {:op :dispute/request :subject "tk-100-op-100" :disputed-field :status :claim :disputed}
             manager true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-task db "tk-100" [:task-id :title :status :assigned-operator-id])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの契約/証明で割当/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
