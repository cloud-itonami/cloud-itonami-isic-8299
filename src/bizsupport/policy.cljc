(ns bizsupport.policy
  "RoutingGovernor — the independent compliance layer that earns the
  TaskRouter-LLM the right to assign, decompose or resolve a dispute. The
  LLM has no notion of operator clearance entitlement, capacity limits, PII
  scope boundaries or a client's disclosure tier, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD
  (assign/disclose nothing) — this actor's analog of `cloud-itonami-
  isic-6311`'s MarketDataGovernor and robotaxi's Minimal Risk Condition.

  Nine checks, in priority order. The first six are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                    — does actor-role have permission for op?
    2. clearance-tier-gate     — does the proposed operator hold every
                                  certification the task requires? (this
                                  actor's analog of source-basis: an
                                  operator or task citing a certification
                                  class outside `bizsupport.facts/allowed-
                                  certification-classes` is rejected
                                  outright)
    3. sanctions-screening-gate — is the proposed operator's on-file
                                  cloud-itonami-isic-8291 screening verdict
                                  `:hit`? If so, this operator can NEVER be
                                  assigned, at any confidence, regardless of
                                  which certifications they hold — no
                                  analog in any sibling actor (optional
                                  integration, see `bizsupport.screening`).
    4. capacity-gate            — would this assignment push the operator's
                                  committed hours past their weekly
                                  capacity?
    5. scope-gate               — does the proposal touch a schema-excluded
                                  (raw client PII) field?
    6. licensed-disclosure      — is there an active, scoped contract, and
                                  does the proposed column set stay within
                                  its tier?
    7. confidence floor         — LLM confidence below threshold → escalate.
    8. high-value-task gate     — the task is flagged :high-value → always
                                  escalate.
    9. dispute requests         — a dispute NEVER auto-resolves, at any
                                  confidence, any phase."
  (:require [clojure.set :as set]
            [bizsupport.facts :as facts]
            [bizsupport.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def private-fields
  "Fields that must NEVER appear in a proposal's value/patch. There is no
  corresponding field in `bizsupport.store`'s schema at all — this check
  exists as defense in depth against an LLM (or a future schema change)
  smuggling one in, not as the primary control."
  #{:client-ssn :client-payment-card :client-home-address :client-financial-account})

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:dispatcher   #{:task/decompose :task/assign :operator/screen}
   :ops-manager  #{:task/decompose :task/assign :operator/screen :dispute/request}
   :client       #{:disclosure/query}})

(def tier-columns
  "For `:disclosure/query` — the columns each licensed client tier may see.
  Anything beyond this is over-disclosure, the market-data/dossier-style
  disclosure-minimization tiers mapped to this domain."
  (let [base #{:task-id :title :status :assigned-operator-id}
        pro-extra #{:estimated-hours :required-certifications}
        inst-extra #{:operator-name :raw-source}]
    {:tier/basic         base
     :tier/pro           (into base pro-extra)
     :tier/institutional (into base (into pro-extra inst-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- clearance-tier-violations
  "Only `:task/assign` proposes an operator↔task pairing. A required
  certification outside the R0 catalog, or one the operator does not hold,
  is a HARD rejection regardless of the LLM's stated confidence."
  [{:keys [op]} proposal st]
  (when (= op :task/assign)
    (let [{:keys [task-id operator-id]} (:value proposal)
          t   (store/task st task-id)
          op* (store/operator st operator-id)
          required (:required-certifications t #{})
          unknown  (remove facts/class-allowed? required)
          missing  (set/difference required (:certifications op* #{}))]
      (cond
        (seq unknown)
        [{:rule :clearance-tier-gate
          :detail (str "未知の証明区分を要求: " (vec unknown))}]

        (seq missing)
        [{:rule :clearance-tier-gate
          :detail (str "operator が保持しない必須証明: " (vec missing))}]))))

(defn- sanctions-screening-violations
  "Only `:task/assign` proposes an operator↔task pairing. If the store
  already holds a `:hit` screening verdict for the proposed operator (from
  a prior `:operator/screen` commit — see `bizsupport.screening`), the
  assignment is a HARD rejection regardless of certifications held or
  confidence. Optional-integration: when no screening has ever been run
  for this operator, `screening-of` returns nil and this check is silent —
  it does not require the cloud-itonami-isic-8291 wiring to be present."
  [{:keys [op]} proposal st]
  (when (= op :task/assign)
    (let [operator-id (get-in proposal [:value :operator-id])
          sc (store/screening-of st operator-id)]
      (when (= :hit (:verdict sc))
        [{:rule :sanctions-screening-gate
          :detail (str "operator " operator-id " は制裁/PEPスクリーニングで hit 判定済み")}]))))

(defn- capacity-violations
  "Only `:task/assign` commits operator hours. Pushing an operator past
  their weekly capacity is a HARD rejection — overcommitment risk cannot
  be waived by confidence."
  [{:keys [op]} proposal st]
  (when (= op :task/assign)
    (let [{:keys [task-id operator-id]} (:value proposal)
          t   (store/task st task-id)
          op* (store/operator st operator-id)
          projected (+ (:committed-hours op* 0) (:estimated-hours t 0))]
      (when (> projected (:weekly-capacity-hours op* 0))
        [{:rule :capacity-gate
          :detail (str "operator の週間キャパシティを超過: "
                       projected " > " (:weekly-capacity-hours op* 0))}]))))

(defn- scope-violations [proposal]
  (let [ks  (set (keys (:value proposal)))
        bad (set/intersection ks private-fields)]
    (when (seq bad)
      [{:rule :scope-gate :detail (str "スキーマ外(顧客個人情報)フィールドを含む: " (vec bad))}])))

(defn- licensed-disclosure-violations
  "`:disclosure/query` is only ever served against a Store-registered,
  active contract — never against caller-asserted context. Over-disclosure
  (columns beyond the contract's tier) is checked the same pass."
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :disclosure/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- high-value-task?
  [st task-id]
  (when task-id
    (= :high-value (:value-tier (store/task st task-id)))))

(defn check
  "Censors a TaskRouter-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-value?
    bool :hard? bool :dispute? bool}.

   - :hard?       — at least one HARD violation (clearance-tier/sanctions-
                    screening/capacity/scope/licensed-disclosure). Forces
                    HOLD; a human cannot override.
   - :escalate?   — soft: low confidence, high-value task, OR a dispute
                    request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-serve."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (clearance-tier-violations request proposal st)
                              (sanctions-screening-violations request proposal st)
                              (capacity-violations request proposal st)
                              (scope-violations proposal)
                              (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        high-value? (high-value-task? st (:subject request))
        dispute?    (= :dispute/request (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not high-value?) (not dispute?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? high-value? dispute?))
     :high-value?  high-value?
     :dispute?     dispute?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
