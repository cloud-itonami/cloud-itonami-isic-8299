(ns bizsupport.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300
  Wave5 rollout ledger): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`bizsupport.operation` -> `bizsupport.policy` -> `bizsupport.store`)
  through a scenario authored directly against `bizsupport.operation/
  build`'s graph and `bizsupport.policy`'s real, distinct HARD-violation
  rules (`bizsupport.sim`, this repo's own demo driver, was checked
  first — `clojure -M:dev:run` was run and its dispositions/ids were
  cross-verified line-by-line against `bizsupport.store/demo-data`
  before deciding whether to reuse it; it turned out to be correct and
  consistent with this file's own manual derivation, unlike
  `cloud-itonami-isic-851`'s broken `schoolops.sim`). The scenario below
  covers two auto-commits, two escalate→approve round-trips, and three
  DISTINCT HARD-hold reasons that never reach a human
  (`:clearance-tier-gate`, `:capacity-gate`, `:scope-gate`) — every id,
  disposition and hold reason here is real governor/store output from
  actually running the graph, not a hand-typed copy. Rendered
  deterministically: no timestamps or randomness in the page content,
  byte-identical across reruns against the same seed (verified by
  diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [bizsupport.store :as store]
            [bizsupport.operation :as op]
            [langgraph.graph :as g]))

(def ^:private dispatcher
  {:actor-id "di-1" :actor-role :dispatcher :phase 3})

(def ^:private manager
  {:actor-id "om-1" :actor-role :ops-manager :phase 3})

(defn- exec! [actor tid context request]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "ops-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach:

    tk-400  decompose (no PII, no capital risk)                → auto-commit
    tk-400  assign -> op-100 (clean clearance + capacity)       → auto-commit
    tk-200  assign -> op-100 (:high-value task)                 → ALWAYS
            escalates regardless of clean clearance/capacity — approved
    tk-400-op-100 dispute/request (quality claim on the above
            assignment)                                         → ALWAYS
            escalates, any phase/confidence — approved
    tk-100  assign -> op-300 (op-300 lacks the HIPAA cert
            tk-100 requires; capacity headroom is otherwise fine) → HARD
            hold: clearance-tier-gate (pure, no other violation)
    tk-300  assign -> op-200 (op-200 needs no cert for tk-300,
            but the 25h task pushes it 43h against a 20h cap)     → HARD
            hold: capacity-gate (pure)
    tk-500  decompose with `:leaky?` (a raw client SSN sneaks
            into the proposal, outside the schema)                → HARD
            hold: scope-gate (pure; tk-500 is therefore NEVER
            created in the store — a HOLD commits nothing)

  Every HARD hold never reaches a human. Returns the resulting store —
  every field read by `render` below is real governor/store output, not
  a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "tk400-decompose" dispatcher
           {:op :task/decompose :subject "tk-400" :task-id "tk-400" :client-id "cl-100"
            :title "General correspondence drafting (demo)" :required-certifications #{}
            :estimated-hours 4 :value-tier :standard})

    (exec! actor "tk400-assign" dispatcher
           {:op :task/assign :subject "tk-400" :task-id "tk-400" :operator-id "op-100"})

    (exec! actor "tk200-assign" dispatcher
           {:op :task/assign :subject "tk-200" :task-id "tk-200" :operator-id "op-100"})
    (approve! actor "tk200-assign")

    (exec! actor "tk400op100-dispute" manager
           {:op :dispute/request :subject "tk-400-op-100" :disputed-field :status :claim :disputed})
    (approve! actor "tk400op100-dispute")

    (exec! actor "tk100-assign-op300" dispatcher
           {:op :task/assign :subject "tk-100" :task-id "tk-100" :operator-id "op-300"})

    (exec! actor "tk300-assign-op200" dispatcher
           {:op :task/assign :subject "tk-300" :task-id "tk-300" :operator-id "op-200"})

    (exec! actor "tk500-decompose-leaky" dispatcher
           {:op :task/decompose :subject "tk-500" :task-id "tk-500" :client-id "cl-200"
            :title "Sensitive intake (demo)" :required-certifications #{} :estimated-hours 2
            :value-tier :standard :leaky? true})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger task-id]
  (last (filter #(= (:subject %) task-id) ledger)))

(defn- status-cell [ledger task-id]
  (let [f (last-fact-for ledger task-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :policy-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-rejected (:t f)) "<span class=\"critical\">approval rejected</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- task-row [ledger {:keys [id title value-tier status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc title) (esc (name (or value-tier :n-a))) (esc (name (or status :n-a)))
          (status-cell ledger id)))

(defn- operator-row [{op-name :name :keys [id certifications weekly-capacity-hours committed-hours]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s / %s h</td></tr>"
          (esc id) (esc op-name)
          (esc (str/join ", " (map name (sort certifications))))
          (esc committed-hours) (esc weekly-capacity-hours)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`bizsupport.policy`'s 9 checks, `bizsupport.phase`'s rollout gate) —
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:task/decompose</code></td><td><span class=\"ok\">auto-commit when clean (no PII, no capital risk)</span></td></tr>"
   "        <tr><td><code>:task/assign</code></td><td><span class=\"ok\">auto-commit when clearance-tier + capacity clean</span> &middot; <span class=\"critical\">HARD reject on missing certification, over-capacity, or sanctions-screening hit</span></td></tr>"
   "        <tr><td><code>:disclosure/query</code></td><td><span class=\"critical\">HARD reject on missing/inactive contract or over-tier columns</span></td></tr>"
   "        <tr><td><code>:dispute/request</code></td><td><span class=\"warn\">ALWAYS human review — never auto-resolves, at any phase or confidence</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        tasks (store/all-tasks db)
        operators (store/all-operators db)
        task-rows (str/join "\n" (map (partial task-row ledger) tasks))
        operator-rows (str/join "\n" (map operator-row operators))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-8299 &middot; other-business-support-services</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Business support services (ISIC 8299) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · task/operator pairing always clearance+capacity checked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Tasks</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>bizsupport.store</code> via <code>bizsupport.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Task</th><th>Title</th><th>Value tier</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     task-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Contracted operators</h2>\n"
     "    <p class=\"muted\">Committed / weekly-capacity hours after this run — a HARD capacity-gate hold never adds committed hours.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Operator</th><th>Name</th><th>Certifications</th><th>Committed / capacity</th></tr></thead>\n"
     "      <tbody>\n"
     operator-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (RoutingGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. A required certification the operator does not hold, or an assignment that would exceed capacity, is rejected outright — never trusted from the TaskRouter-LLM's proposal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and HARD hold this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/all-tasks db)) "tasks,"
             (count (store/all-operators db)) "operators )")))
