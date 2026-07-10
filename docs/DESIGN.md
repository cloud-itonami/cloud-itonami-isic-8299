# Business-Support Actor Design — TaskRouter-LLM as a contained intelligence node

汎用 VA(virtual assistant)/BPO タスクマッチング業を、governed dispatch-only
(証明区分検証・稼働上限管理・PII非保持)の運用で、SaaS課金に依存せず OSS の
actor として自前運用するための設計。`cloud-itonami-isic-6311`
(MarketData-LLM を MarketDataGovernor で封じ込めた構図)を、業務委託タスク
ドメインへ写像している。

## 1. 前提: なぜ actor 層が要るのか、そしてなぜスコープを絞るのか

タスク分解・operator マッチング候補の提案は LLM で加速できる。しかし LLM
は次の理由で**割当・開示・紛争確定の最終権限を持てない**:

| LLM が起こしうる失敗 | この業態での帰結 |
|---|---|
| 証明区分を持たない operator へ規制対象タスクを割当 | コンプライアンス違反(HIPAA/PCI-DSS等) |
| operator の稼働上限を超えて割当を積む | SLA違反・operator の過重労働 |
| 顧客の個人識別情報をタスク記録に紛れ込ませる | プライバシー規制違反・情報漏洩 |
| 契約 tier を超えた列を開示 | 過剰開示・契約違反 |

したがって設計課題は「LLM でタスク割当を回す」ことではなく、**「LLM を
信頼境界の内側に封じ込め、証明区分・稼働上限・PIIスコープ・人間レビューの
層をどう被せるか」**である。ISIC 8299(その他の事業支援サービス, n.e.c.)
という広いコードは、`cloud-itonami-isic-6311` が narrow したのと同じ discipline
で、**VA/BPO タスクマッチング**という具体的な一業態へ narrow されている。

## 2. アクター・トポロジ(監督ツリー)

```
BizSupportSystem (root supervisor)
│
├── IntakeActor ……… クライアント依頼のタスク化(:task/decompose)
├── DispatchActor ……… operator への割当投影(:task/assign)
│
├── OperationActor[op] … ★ 1操作 = 1 actor run; TaskRouter-LLM 封じ込め ★
│     ├── TaskRouter-LLM (sealed)  proposal only(src/bizsupport/llm.cljc)
│     ├── RoutingGovernor          INDEPENDENT ゲート(src/bizsupport/policy.cljc)
│     ├── Committer                SSoT/台帳への書き込み(src/bizsupport/store.cljc)
│     └── Recorder                  監査台帳(append-only)
│
├── ReviewActor ……… 人間レビュー(高額タスク割当・紛争申立ての interrupt を受ける)
└── DisclosureActor ……… governed read(report.cljc、契約 tier 列のみ)
```

原則:

1. **TaskRouter-LLM は最下層ノードで、台帳・開示経路に直接触れない。** 出力は
   常に RoutingGovernor で検閲される。
2. **監督。** 子の失敗は親へ escalate し、最終的に **hold(割当/開示しない)**
   に倒す。robotaxi の MRC(安全停止)に相当する既定。
3. **すべてが台帳に積まれる。** 「誰が・何を・どの証明区分/契約で割当/開示
   したか」は監査台帳への Datalog クエリ — 監査・紛争が同一ファクトログから
   出る。

## 3. OperationActor 内部(TaskRouter-LLM ラッパー)

`src/bizsupport/operation.cljc` の langgraph-clj StateGraph として実装。
**1 run = 1 操作** — 有界で監査可能、無限内部ループを持たない。

```
intake → advise → govern → decide ─┬─ commit ───────────────────▶ commit → END
                                   ├─ escalate ─▶ request-approval ┐ [interrupt-before]
                                   │                               │ 承認/却下で resume
                                   │              approved ─▶ commit┘ / rejected ─▶ hold
                                   └─ hold ─────────────────────────────────────▶ hold → END
```

### 3.1 注入される3つの依存(すべて swap)

- **Store**(`bizsupport.store/Store` プロトコル): `MemStore`(既定)/
  `DatomicStore`(`langchain.db` = Datomic-API 互換 EAV)。両者は同一契約
  テストで等価性を保証。
- **Advisor**(`bizsupport.llm/Advisor` プロトコル): `mock-advisor`(既定)/
  `llm-advisor`(`langchain.model` の ChatModel)。応答破損時は confidence 0
  の noop に落ち、LLM 不調が auto-commit/開示にならない。
- **Phase**(`bizsupport.phase`、context の `:phase 0..3`): 段階導入。既定は
  `1`(assisted、auto-commit 無し)— governor より保守的にしか働かない。
  **`:dispute/request` はどの phase の `:auto` にも入らない**(恒久ゲート)。

## 4. RoutingGovernor(独立検閲層)

`src/bizsupport/policy.cljc`。LLM とは別経路で、提案を可決/拒否/escalate に
判定する。優先順位(上が強い、HARD は人間承認でも上書き不可):

1. **RBAC** — `permissions` 表で `actor-role × operation` を引く。
2. **clearance-tier-gate** — `:task/assign` の operator が、タスクの必須
   証明区分をすべて保持しているか。未知の証明区分要求も HARD 拒否。
3. **capacity-gate** — 割当により operator の週間コミット時間が上限を
   超えないか。
4. **scope-gate** — 提案の value が顧客個人情報系フィールド
   (`private-fields`)を含んだら HARD violation。スキーマ自体にこれらの
   フィールドは無い。
5. **licensed-disclosure** — `:disclosure/query` は Store 登録済みの有効な
   契約(tenant×tier)を要求し、提案列が契約 tier を超えたら HARD violation。
6. **確信度フロア** — `:confidence < 0.6` → escalate(soft)。
7. **high-value-task gate** — タスクが `:high-value` フラグ付き → 必ず
   人間承認(soft)。
8. **dispute-request** — `:dispute/request` は常に escalate(soft だが
   confidence に関わらず無条件)。

## 5. SSoT と監査台帳

`src/bizsupport/store.cljc`。entities: `tasks`(operator PII非保持)
`operators`(証明区分・稼働上限/コミット時間) `assignments`(task↔operator
edge) `contracts`(client licensing)。`:assignment-upsert` commit 時に
operator の `committed-hours` を自動加算する。

## 6. 開示(governed read)

`src/bizsupport/report.cljc`。`render-task` は RoutingGovernor が承認した
列のみを出力する。

## 7. デモ(`clojure -M:dev:run`)

`src/bizsupport/sim.cljc` が8操作を actor に通す(§sim.cljc docstring 参照)。

## 8. テスト(`clojure -M:dev:test`)

`test/bizsupport/policy_contract_test.clj` が**ガバナンス契約を実行可能**に
する。`test/bizsupport/phase_test.clj` が段階導入と「紛争は恒久的に人間専用」
を保証。`test/bizsupport/facts_test.clj` が証明区分カタログ自体の正直さ
(捏造禁止)を保証。

## 9. 実装と業態の対応

| 実在業態の機能 | bizsupport actor での実体 |
|---|---|
| クライアント依頼のタスク化 | `store` tasks + `:task/decompose` |
| operator プール管理 | `store` operators(証明区分・稼働上限) |
| タスク↔operator マッチング | `store` assignments + `:task/assign` |
| 規制対象タスクの証明区分確認 | clearance-tier-gate + `facts/catalog` |
| operator 過重労働の防止 | capacity-gate |
| 顧客PIIの非保持 | scope-gate(構造的除外) |
| 紛争解決(品質異議等) | `:dispute/request`(恒久 human-only) |
| アクセス権限・契約 | RoutingGovernor RBAC 表 + `contracts` |
| 監査台帳 | `store` append-only ledger |
