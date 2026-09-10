(ns bizsupport.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-isic-6311`'s rollout phases: start narrow (read-only),
  widen as trust grows. Where the RoutingGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have
  *yet*?'. It can only ever make the actor MORE conservative than the
  governor: it downgrades a governor-clean commit to approval or hold,
  never the reverse.

    Phase 0  read-only        — no writes at all. `:disclosure/query` only
                                (still governor-gated).
    Phase 1  assisted-intake  — `:task/decompose` and `:operator/apply`
                                (recording that someone applied) allowed,
                                every write needs human approval.
    Phase 2  + assignment     — adds `:task/assign`, `:dispute/request`
                                and the admission decisions
                                `:operator/admit`/`:operator/decline`
                                (still approval-only).
    Phase 3  supervised auto  — governor-clean, high-confidence
                                `:task/decompose`/`:task/assign`/
                                `:operator/apply` may auto-commit.

  `:dispute/request`, `:operator/admit` and `:operator/decline` are
  deliberately NEVER members of any phase's `:auto` set, at any phase — a
  client/operator dispute always reaches a human, and so does every
  decision about whether a real person joins this operator pool or is
  turned down, independent of the RoutingGovernor's own always-escalate
  checks on the same ops. `:operator/apply` is auto-eligible at phase 3
  because recording that someone applied is not a decision about them.

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted
  disposition plus a reason when the phase changed it.")

(def read-ops  #{:disclosure/query})
(def write-ops #{:task/decompose :task/assign :operator/screen :dispute/request
                 :operator/apply :operator/admit :operator/decline})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:dispute/request`,
  `:operator/admit` and `:operator/decline` are intentionally absent from
  every phase's `:auto` set."
  {0 {:label "read-only"       :writes #{}
                                :auto #{}}
   1 {:label "assisted-intake" :writes #{:task/decompose :operator/apply}
                                :auto #{}}
   2 {:label "assisted-assign" :writes #{:task/decompose :task/assign :operator/screen :dispute/request
                                         :operator/apply :operator/admit :operator/decline}
                                :auto #{}}
   3 {:label "supervised-auto" :writes #{:task/decompose :task/assign :operator/screen :dispute/request
                                         :operator/apply :operator/admit :operator/decline}
                                :auto #{:task/decompose :task/assign :operator/screen :operator/apply}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (bizsupport.operation: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase NUMBER. This is
  directly reachable by any ordinary caller that simply omits :phase --
  not just malformed/malicious input -- so it must be the MOST
  CONSERVATIVE phase, never the most permissive (the same accidental
  fail-open shape found and fixed this session in `cloud-itonami-isic-6311`
  and the shared `talent.phase` template — this actor is written correctly
  from the start rather than needing a later fix). `:dispute/request`
  remains unaffected either way (never in any phase's `:auto` set — a
  dispute always reaches a human)."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads (`:disclosure/query`) pass through unchanged (phase restricts
    write autonomy, not governed reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean. `:dispute/request` is never
    auto-eligible at any phase, so it always lands here once phase ≥ 2."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a RoutingGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
