(ns bizsupport.policy
  "RoutingGovernor — the independent compliance layer that earns the
  TaskRouter-LLM the right to assign, decompose or resolve a dispute. The
  LLM has no notion of operator clearance entitlement, capacity limits, PII
  scope boundaries or a client's disclosure tier, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD
  (assign/disclose nothing) — this actor's analog of `cloud-itonami-
  isic-6311`'s MarketDataGovernor and robotaxi's Minimal Risk Condition.

  Fourteen checks, in priority order. The first ten are HARD violations: a
  human approver CANNOT override them. The last four are SOFT/always-
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
    7. unknown-operator         — is the assignment's operator actually in
                                  the pool? (also what keeps an assignment
                                  away from someone who merely applied)
    8. certification-claim-gate — does an application/admission claim only
                                  certification classes in the R0 catalog?
    9. capacity-claim-gate      — is the claimed weekly capacity a positive
                                  number of hours that fits in a week?
   10. candidate-lifecycle      — application state machine: candidate →
                                  admitted | declined, exactly once; no
                                  duplicate application, no re-admission,
                                  no id collision with a pool member.
   11. confidence floor         — LLM confidence below threshold → escalate.
   12. high-value-task gate     — the task is flagged :high-value → always
                                  escalate.
   13. dispute requests         — a dispute NEVER auto-resolves, at any
                                  confidence, any phase.
   14. admission ops            — `:operator/admit`/`:operator/decline`
                                  NEVER auto-commit, at any confidence,
                                  any phase. Whether a real person joins
                                  this pool (or is turned down) is a human
                                  decision that the actor only prepares,
                                  governs and records — the same boundary
                                  every cloud-itonami actor's README
                                  states about recruiting real people."
  (:require [clojure.set :as set]
            [bizsupport.facts :as facts]
            [bizsupport.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def private-fields
  "Fields that must NEVER appear in a proposal's value/patch. There is no
  corresponding field in `bizsupport.store`'s schema at all — this check
  exists as defense in depth against an LLM (or a future schema change)
  smuggling one in, not as the primary control.

  The applicant-side fields matter for the same reason the client-side
  ones do, and are the likelier accident: a recruitment intake is exactly
  where someone's legal name, address, phone number, date of birth,
  national id or bank account would arrive if this actor accepted them. A
  candidate carries a self-chosen `:handle` and an opaque `:contact-ref`
  pointing at the public conversation, nothing else (see
  `bizsupport.store`'s ns docstring)."
  #{:client-ssn :client-payment-card :client-home-address :client-financial-account
    :applicant-legal-name :applicant-home-address :applicant-phone :applicant-email
    :applicant-date-of-birth :applicant-national-id :applicant-bank-account})

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform. Recording an
  application (`:operator/apply`) is dispatcher-level; deciding whether
  someone joins the pool (`:operator/admit`) or does not
  (`:operator/decline`) is ops-manager-only — and additionally always
  routed to a human by `check` below, at every phase."
  {:dispatcher   #{:task/decompose :task/assign :operator/screen :operator/apply}
   :ops-manager  #{:task/decompose :task/assign :operator/screen :dispute/request
                   :operator/apply :operator/admit :operator/decline}
   :client       #{:disclosure/query}})

(def max-plausible-weekly-hours
  "A weekly capacity claim above this is not a judgment call about how
  hard someone should work — it is arithmetically impossible (168 hours
  exist in a week), so it is a data error or an attempt to defeat the
  capacity-gate, and is rejected outright. Anything below it is the
  operator's own business; nothing here invents a labour-law limit this
  actor has no basis to enforce (these are 業務委託 contractors, not
  employees — see `cloud-itonami-isic-7820` for the employer-of-record
  model where working-time law actually binds)."
  168)

(def admission-ops
  "The two ops that decide a real person's relationship to this pool.
  Never auto-committable at any phase and never auto-approvable by
  confidence: a human signs off on who is admitted, and on who is
  turned down."
  #{:operator/admit :operator/decline})

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

(defn- unknown-operator-violations
  "`:task/assign` against an operator id the SSoT does not know is a HARD
  rejection. Without this, a task whose `:estimated-hours` is 0 (or
  missing) slips past the capacity-gate's arithmetic — 0 > 0 is false —
  and commits an assignment to a person who does not exist in the pool.
  Someone who applied but has not been admitted is deliberately NOT in
  `:operators` (see `bizsupport.store`), so this is also what stops an
  assignment from reaching an un-admitted applicant."
  [{:keys [op]} proposal st]
  (when (= op :task/assign)
    (let [operator-id (get-in proposal [:value :operator-id])]
      (when (nil? (store/operator st operator-id))
        [{:rule :unknown-operator
          :detail (str "プールに存在しない operator への割当: " (pr-str operator-id)
                       (when (store/candidate st operator-id)
                         " (応募者として在籍。admit 前は割当不可)"))}]))))

(defn- sanctions-screening-violations
  "`:task/assign` proposes an operator↔task pairing and `:operator/admit`
  proposes letting someone into the pool at all. If the store already
  holds a `:hit` screening verdict for that person (from a prior
  `:operator/screen` commit — see `bizsupport.screening`), both are a HARD
  rejection regardless of certifications held or confidence. Optional-
  integration: when no screening has ever been run, `screening-of`
  returns nil and this check is silent — it does not require the
  cloud-itonami-isic-8291 wiring to be present."
  [{:keys [op] :as request} proposal st]
  (when (#{:task/assign :operator/admit} op)
    (let [subject-id (or (get-in proposal [:value :operator-id])
                         (get-in proposal [:value :candidate-id])
                         (:subject request))
          sc (store/screening-of st subject-id)]
      (when (= :hit (:verdict sc))
        [{:rule :sanctions-screening-gate
          :detail (str subject-id " は制裁/PEPスクリーニングで hit 判定済み")}]))))

(defn- certification-claim-violations
  "A candidate may only claim certification classes that exist in the R0
  catalog — the same closed set the clearance-tier-gate enforces on
  assignment. Accepting `:self-declared` here and rejecting it at
  assignment time would admit someone on a basis the pool can never
  actually use."
  [{:keys [op]} proposal]
  (when (#{:operator/apply :operator/admit} op)
    (let [claimed (or (get-in proposal [:value :claimed-certifications])
                      (get-in proposal [:value :certifications])
                      #{})
          unknown (remove facts/class-allowed? claimed)]
      (when (seq unknown)
        [{:rule :certification-claim-gate
          :detail (str "カタログ外の証明区分を申告: " (vec unknown))}]))))

(defn- capacity-claim-violations
  "A capacity claim must be a positive number of hours that fits in a
  week. Not a labour-law judgment (see `max-plausible-weekly-hours`) —
  an impossible number would silently disable the capacity-gate."
  [{:keys [op]} proposal]
  (when (#{:operator/apply :operator/admit} op)
    (let [h (or (get-in proposal [:value :weekly-capacity-hours])
                (get-in proposal [:value :capacity-hours]))]
      (cond
        (not (number? h))
        [{:rule :capacity-claim-gate :detail (str "週間キャパシティの申告が数値でない: " (pr-str h))}]

        (or (<= h 0) (> h max-plausible-weekly-hours))
        [{:rule :capacity-claim-gate
          :detail (str "週間キャパシティの申告が1週間に収まらない: " h
                       " (0 < h <= " max-plausible-weekly-hours ")")}]))))

(defn- candidate-lifecycle-violations
  "The application state machine, enforced by the governor rather than
  trusted to the advisor: candidate → admitted | declined, once.

  - `:operator/apply` for an id already in the pool, or an id that
    already has an application on file, is a HARD rejection (a duplicate
    would otherwise silently `merge` over a pool member's record).
  - `:operator/admit` / `:operator/decline` for an unknown id, or for an
    application already resolved, is a HARD rejection — re-admitting
    would reset committed hours to 0, and re-declining an admitted
    person would leave them assignable while marked declined."
  [{:keys [op] :as request} proposal st]
  (let [id (or (get-in proposal [:value :id])
               (get-in proposal [:value :candidate-id])
               (:subject request))
        c  (when id (store/candidate st id))]
    (case op
      :operator/apply
      (cond
        (nil? id) [{:rule :candidate-lifecycle :detail "応募に id が無い"}]
        (some? (store/operator st id))
        [{:rule :candidate-lifecycle :detail (str id " は既にプール在籍の operator")}]
        (some? c)
        [{:rule :candidate-lifecycle
          :detail (str id " の応募は既に受理済み (status=" (:status c) ")")}])

      (:operator/admit :operator/decline)
      (cond
        (nil? c) [{:rule :candidate-lifecycle :detail (str "応募が存在しない: " (pr-str id))}]
        (not= :candidate (:status c))
        [{:rule :candidate-lifecycle
          :detail (str id " の応募は既に " (:status c) " 済み — 再処理しない")}])

      nil)))

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
                              (unknown-operator-violations request proposal st)
                              (sanctions-screening-violations request proposal st)
                              (capacity-violations request proposal st)
                              (certification-claim-violations request proposal)
                              (capacity-claim-violations request proposal)
                              (candidate-lifecycle-violations request proposal st)
                              (scope-violations proposal)
                              (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        high-value? (high-value-task? st (:subject request))
        dispute?    (= :dispute/request (:op request))
        admission?  (contains? admission-ops (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not high-value?) (not dispute?) (not admission?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? high-value? dispute? admission?))
     :high-value?  high-value?
     :dispute?     dispute?
     :admission?   admission?}))

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
