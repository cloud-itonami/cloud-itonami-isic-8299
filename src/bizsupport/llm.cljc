(ns bizsupport.llm
  "TaskRouter-LLM client — the *contained intelligence node*.

  It normalizes client work requests into task records, drafts operator↔
  task assignment proposals, proposes disclosure column sets for a
  licensed client query, and drafts dispute resolutions. CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale +
  the fields it cited), never a committed or disclosed record. Every
  output is censored downstream by `bizsupport.policy` (the
  RoutingGovernor) before anything touches the SSoT or leaves the actor.

  Like `cloud-itonami-isic-6311`'s MarketData-LLM, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm)
  with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by scope-gate
     :cites      [kw|str ..]    ; fields the LLM used
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the record/assignment patch
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defn- propose-decompose
  "Task decomposition/normalization — the LLM only structures the client's
  work request into the task schema (adds no PII). `:leaky?` injects the
  failure mode we must defend against: a raw client identifier (SSN,
  payment card, home address) sneaking into the record — the
  RoutingGovernor's scope-gate must reject this outright."
  [{:keys [task-id client-id title required-certifications estimated-hours value-tier leaky?]}]
  (let [patch (cond-> {:id task-id :client-id client-id :title title
                       :required-certifications (or required-certifications #{})
                       :estimated-hours estimated-hours :value-tier (or value-tier :standard)
                       :status :open}
                leaky? (assoc :client-ssn "000-00-0000(デモ・スキーマ外)"))]
    {:summary   (str "task 分解: " title)
     :rationale "クライアント依頼内容の構造化のみ。個人識別情報の生成なし。"
     :cites     (vec (keys (dissoc patch :client-ssn)))
     :effect    :task-upsert
     :value     patch
     :confidence 0.95}))

(defn- propose-assign
  "Operator↔task assignment proposal. The LLM only proposes a pairing; it
  does not itself verify clearance or capacity — that is the
  RoutingGovernor's job (clearance-tier-gate/capacity-gate), which is why
  this stays deliberately high-confidence even for an assignment the
  governor will reject: proving those gates cannot rely on confidence as a
  proxy for correctness."
  [{:keys [task-id operator-id]}]
  {:summary   (str task-id " → " operator-id " への割当提案")
   :rationale "operator の空き状況/専門性に基づく提案。証明区分・稼働上限の検証は governor が行う。"
   :cites     [:task-id :operator-id]
   :effect    :assignment-upsert
   :value     {:id (str task-id "-" operator-id) :task-id task-id :operator-id operator-id :status :assigned}
   :confidence 0.95})

(defn- propose-disclosure
  "Disclosure column-set proposal for a licensed client query. `:greedy?`
  injects over-disclosure (pulls operator-name/raw-source columns beyond a
  basic-tier contract) — the RoutingGovernor's licensed-disclosure gate
  must reject the excess columns."
  [{:keys [greedy?]}]
  (let [base [:task-id :title :status :assigned-operator-id]
        greedy-extra [:operator-name :raw-source]]
    {:summary   "開示列提案"
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-dispute
  "Dispute resolution draft. The LLM may draft a proposed resolution but
  this NEVER auto-applies — `bizsupport.policy` and `bizsupport.phase` both
  structurally force every `:dispute/request` to human review, independent
  of confidence."
  [{:keys [disputed-field claim]}]
  {:summary   (str "assignment の " disputed-field " について紛争解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :effect    :dispute-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [{:keys [op] :as request}]
  (case op
    :task/decompose      (propose-decompose request)
    :task/assign          (propose-assign request)
    :disclosure/query     (propose-disclosure request)
    :dispute/request       (propose-dispute request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a
;; real LLM in production. Either way its output is a PROPOSAL the
;; RoutingGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ _st req] (infer req))))

(def ^:private system-prompt
  (str "あなたは業務委託タスクの分解・割当アドバイザーです。"
       "与えられた事実のみに基づき、提案を1つだけ EDN マップで返します。"
       "説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:task-upsert|:assignment-upsert|:disclosure-serve|:dispute-apply) "
       ":value(該当マップ) :confidence(0..1)。\n"
       "重要: クライアントの個人識別情報(SSN・決済カード・住所・口座)に"
       "関する情報は一切扱ってはいけません(スキーマにそのフィールドは存在しません)。"
       "operator の証明区分/稼働上限の妥当性判断はあなたの責務ではありません"
       "(governor が判定します)。"))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the RoutingGovernor escalates/holds — an
  LLM hiccup can never auto-commit or auto-serve."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ _st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req) "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (dissoc req :op :subject)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :taskrouterllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
