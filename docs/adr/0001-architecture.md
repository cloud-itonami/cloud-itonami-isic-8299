# ADR-0001: cloud-itonami-isic-8299 — TaskRouter-LLM を封じ込めた知能ノードとする業務委託タスクマッチング・アクター設計

- Status: Accepted (2026-07-10)
- 関連: `cloud-itonami-isic-6311`(MarketData-LLM を MarketDataGovernor で
  封じ込める構図の直接の手本)、`cloud-itonami-isic-8291`(Dossier-LLM を
  DisclosureGovernor で封じ込めるパターンの原型)、robotaxi-actor ADR-0001
  (研究モデルを信頼境界に封じ込める actor 設計)、langgraph ADR-0001
  (Pregel superstep + interrupt + Datomic checkpoint)
- 文脈: com-junkawasaki/root superproject ADR(本 ADR の対)

## 課題

`kotoba-lang/industry` registry の未着手 `:spec` スロットから、ISIC Rev.4
8299「Other business support service activities n.e.c.」を選定した。この
コードは事業支援サービス一般を指す広い n.e.c.(not elsewhere classified)
コードであり、`cloud-itonami-isic-6311`(6311 narrowing)・
`cloud-itonami-isic-4610`(4610 narrowing)の前例に倣い、具体的な一業態へ
narrow する必要がある。**virtual-assistant/BPO タスクマッチング**(クライ
アント依頼の分解・証明区分/稼働上限を検証した operator への割当)を選定
した。

一方、タスク分解・operator マッチング候補の提案には LLM が有効だが、
**LLM に割当・開示・紛争確定を直接行わせるのは危険**である(未証明
operator への規制対象タスク割当=コンプライアンス違反、稼働上限超過=SLA
違反・過重労働、顧客個人情報のタスク記録への混入=プライバシー規制違反)。
したがって設計課題は「LLM でタスク割当を回す」ことではなく、**「LLM を
信頼境界の内側に封じ込め、証明区分・稼働上限・PIIスコープ・人間レビューの
層をどう被せるか」**である。

## 決定

### 1. TaskRouter-LLM は最下層の1ノードに封じ込め、直接割当/開示/紛争確定させない

OperationActor 内で TaskRouter-LLM は *proposal*(タスク分解案・割当案・
開示列案・紛争解決案 ＋ 根拠トレース)のみを返す**助言者**として扱う。
出力は必ず独立した `RoutingGovernor` を通してから台帳に commit する。
**単一の不変条件**:

> **TaskRouter-LLM は、RoutingGovernor が拒否する割当の確定・開示・
> 紛争確定を決して行わない。**

### 2. RoutingGovernor は8チェック(5 HARD + 3 SOFT)

`cloud-itonami-isic-6311` の MarketDataGovernor(RBAC + 4 HARD + 3 SOFT)を
写像しつつ、このドメイン固有の HARD チェックを2つ新設した:

| # | チェック | 種別 | 内容 |
|---|---|---|---|
| 1 | rbac | HARD | actor-role が operation の権限を持つか |
| 2 | **clearance-tier-gate**(新規、業務委託固有) | HARD | `:task/assign` の operator が、タスクの必須証明区分をすべて保持しているか。未知の証明区分要求(R0カタログ外)も無条件拒否 — dossier の source-basis gate の写像 |
| 3 | **capacity-gate**(新規、業務委託固有) | HARD | 割当により operator の週間コミット時間が上限を超えないか。fat-finger 耐性としての tolerance-gate(isic-6311)の労働力版 |
| 4 | scope-gate | HARD | 提案が顧客個人情報系フィールド(SSN・決済カード・住所・口座)を含むか。スキーマ自体にこれらのフィールドは存在しない |
| 5 | licensed-disclosure | HARD | 有効な契約(tenant×tier)が無い、または開示列が tier を超えたら拒否 |
| 6 | 確信度フロア | SOFT | `:confidence < 0.6` → escalate |
| 7 | high-value-task gate | SOFT | タスクが `:high-value` フラグ付き → 必ず人間承認(dossierのhigh-stakes gateの写像) |
| 8 | dispute-request | SOFT(無条件) | 紛争申立ては確信度に関わらず常に人間レビュー、どの phase でも auto 化しない |

**意図的に無い項目**: 雇用主責任(給与支払・労務管理)に相当するチェックは
存在しない — この actor は operator との業務委託契約下のタスク仲介のみを
行い、`cloud-itonami-isic-7820`(労働者派遣、雇用主責任あり)とは法的責任
構造が根本的に異なるため(安易な流用を避けた証跡として ADR に明記)。

### 3. Phase 0→3 + 恒久人間ゲート、default-phase=1 を最初から採用

`cloud-itonami-isic-6311` の兄弟テンプレートで発見された「`:phase` を
省略した呼び出し元が黙って最大自律性を得る」fail-open バグの修正を、
本 actor は初期実装時点から適用済み(`default-phase` は `1`)。
`:dispute/request` はどの phase の `:auto` 集合にも入らない構造的恒久
ゲート。

### 4. R0 の正直なスコープ(捏造禁止)

出典カタログ(`src/bizsupport/facts.cljc`)は実在する5つの実定・業界標準
のみ(HIPAA・PCI DSS・SOC 2・GDPR データ処理者義務・ISO/IEC 27001)。
`facts/coverage` が常に正直に現状を報告し、拡張は実在する標準の追記でのみ
行う(捏造禁止)。

### 5. Robotics premise: false

業務委託タスクの分解・マッチングは書面/システム上の処理であり、actor の
境界の外に物理的な作動(実際の作業自体)は存在しない。

## Consequences

- (+) `kotoba-lang/industry` registry の 8299 スロットが実装へ昇格。
- (+) clearance-tier-gate・capacity-gate という、他の cloud-itonami
  actor に存在しない業務委託業固有の HARD チェックを新設し、`isic-7820`
  (雇用主責任あり)との構造的差異(雇用主責任の有無)を反映した。
- (-) Datomic/kotoba-server backend は次のシーム(未接続)。
- superproject への反映: 本 ADR + `kotoba-lang/industry` pin 前進のみ。
  `cloud-itonami-isic-8299` は既存の `cloud-itonami-{ISIC}` blueprint 群
  と同じ慣例により `manifest/repos.edn` には登録しない(standalone、
  plain-git 子リポ)。

## 代替案と不採用理由

- **7810(あっせん業)/7820(労働者派遣)のスロットを流用**: 両者とも人材の
  雇用/紹介という異なる法的責任構造を持ち、業務委託タスクマッチング
  (operator は独立契約者、雇用関係なし)とは本質的に異なる。
- **LLM に割当・開示権限を直接付与(エージェント自律)**: 速いが、未証明
  operator への規制対象タスク割当・稼働上限超過・PII漏洩を構造的に防げ
  ない。単一不変条件(決定1)に反する。
- **clearance-tier-gate/capacity-gate を SOFT にとどめる**: 規制対象
  データの未証明operatorへの到達・稼働上限超過は実害を伴う構造的リスク
  であり、人間承認で事後的に許容できる性質のものではない。HARD が必須と
  判断した。
