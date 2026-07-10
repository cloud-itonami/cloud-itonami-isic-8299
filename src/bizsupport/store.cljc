(ns bizsupport.store
  "SSoT for the business-support (virtual-assistant/BPO task-matching)
  actor, behind a `Store` protocol so the backend is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/bizsupport/store_contract_test.clj) — the actor, the
  RoutingGovernor and the audit ledger never know which SSoT they run on.

  Entity shapes (ADR-0001): a task (client work request — title, required
  certifications, estimated hours, value tier, status — NEVER a raw
  personal/financial identifier of the client), an operator (contracted
  human worker — certifications, weekly capacity, committed hours), an
  assignment (task↔operator edge), and a client subscription contract.
  There is NO field anywhere in this schema for a client's SSN, payment
  card, home address or financial account — the scope boundary is
  structural, not a runtime filter someone could forget to call.

  The ledger stays append-only on every backend — 'who assigned what to
  whom, on what certification/capacity basis' is always a query over an
  immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (task [s id])
  (all-tasks [s])
  (operator [s id])
  (all-operators [s])
  (assignment [s id])
  (assignments-of-operator [s operator-id])
  (screening-of [s operator-id] "most recent sanctions/PEP screening verdict on file for this operator, or nil if never screened")
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-tasks [s tasks]             "replace/seed tasks (map id→task)")
  (with-operators [s operators]     "replace/seed operators (map id→operator)")
  (with-assignments [s assignments] "replace/seed assignments (map id→assignment)")
  (with-screenings [s screenings]   "replace/seed operator screening verdicts (map operator-id→screening)")
  (with-contracts [s contracts]     "replace/seed subscriber contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real people) ──────

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline
  and no real client/operator is ever named in this repository. `op-300`'s
  name deliberately matches `cloud-itonami-isic-8291`'s own demo sanctions-
  flagged official (`of-2`, org `co-200`, see `dossier.store/demo-data`) —
  it exists purely so `bizsupport.screening`'s real (unmocked) 8291
  integration has a genuine, reproducible hit to catch in tests, the same
  cross-repo-name-sharing convention `cloud-itonami-isic-6910`'s
  `formation.store/demo-data` uses for its own `o-4`."
  []
  {:tasks
   {"tk-100" {:id "tk-100" :client-id "cl-100" :title "Patient intake data entry (demo)"
              :required-certifications #{:hipaa} :estimated-hours 5
              :value-tier :standard :status :open}
    "tk-200" {:id "tk-200" :client-id "cl-100" :title "Executive calendar management (demo)"
              :required-certifications #{} :estimated-hours 3
              :value-tier :high-value :status :open}
    "tk-300" {:id "tk-300" :client-id "cl-200" :title "General research brief (demo)"
              :required-certifications #{} :estimated-hours 25
              :value-tier :standard :status :open}}
   :operators
   {"op-100" {:id "op-100" :name "Alice (demo)" :certifications #{:hipaa :soc2}
              :weekly-capacity-hours 40 :committed-hours 30}
    "op-200" {:id "op-200" :name "Bob (demo)" :certifications #{:soc2}
              :weekly-capacity-hours 20 :committed-hours 18}
    "op-300" {:id "op-300" :name "Jane Smith (demo)" :certifications #{:soc2}
              :weekly-capacity-hours 40 :committed-hours 0}}
   :contracts
   {"tenant-acme"  {:tenant "tenant-acme" :tier :tier/pro :active? true :purpose :bpo-client}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :bpo-client}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (task [_ id] (get-in @a [:tasks id]))
  (all-tasks [_] (sort-by :id (vals (:tasks @a))))
  (operator [_ id] (get-in @a [:operators id]))
  (all-operators [_] (sort-by :id (vals (:operators @a))))
  (assignment [_ id] (get-in @a [:assignments id]))
  (assignments-of-operator [_ operator-id]
    (->> (vals (:assignments @a)) (filter #(= operator-id (:operator-id %))) (sort-by :id)))
  (screening-of [_ operator-id] (get-in @a [:screenings operator-id]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :task-upsert            (swap! a update-in [:tasks (:id value)] merge value)
      :assignment-upsert      (do (swap! a update-in [:assignments (:id value)] merge value)
                                   (let [t (task s (:task-id value))]
                                     (swap! a update-in [:operators (:operator-id value) :committed-hours]
                                            (fnil + 0) (:estimated-hours t 0))))
      :screening-verdict-set  (swap! a assoc-in [:screenings (:operator-id value)] value)
      :dispute-apply          (swap! a update-in [:assignments (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-tasks [s ts]       (when (seq ts) (swap! a assoc :tasks ts)) s)
  (with-operators [s os]   (when (seq os) (swap! a assoc :operators os)) s)
  (with-assignments [s as] (when (seq as) (swap! a assoc :assignments as)) s)
  (with-screenings [s scs] (when (seq scs) (swap! a assoc :screenings scs)) s)
  (with-contracts [s cts]  (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :assignments {} :screenings {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (required-certifications, source citations) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities."
  {:task/id              {:db/unique :db.unique/identity}
   :operator/id          {:db/unique :db.unique/identity}
   :assignment/id        {:db/unique :db.unique/identity}
   :screening/operator-id {:db/unique :db.unique/identity}
   :contract/tenant      {:db/unique :db.unique/identity}
   :ledger/seq           {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- task->tx [{:keys [id client-id title required-certifications estimated-hours value-tier status]}]
  (cond-> {:task/id id}
    client-id                 (assoc :task/client-id client-id)
    title                     (assoc :task/title title)
    true                      (assoc :task/required-certifications (enc (or required-certifications #{})))
    estimated-hours           (assoc :task/estimated-hours estimated-hours)
    value-tier                (assoc :task/value-tier value-tier)
    status                    (assoc :task/status status)))

(defn- pull->task [m]
  (when (:task/id m)
    {:id (:task/id m) :client-id (:task/client-id m) :title (:task/title m)
     :required-certifications (or (dec* (:task/required-certifications m)) #{})
     :estimated-hours (:task/estimated-hours m) :value-tier (:task/value-tier m)
     :status (:task/status m)}))

(def ^:private task-pull
  [:task/id :task/client-id :task/title :task/required-certifications
   :task/estimated-hours :task/value-tier :task/status])

(defn- operator->tx [{:keys [id name certifications weekly-capacity-hours committed-hours]}]
  (cond-> {:operator/id id}
    name                    (assoc :operator/name name)
    true                    (assoc :operator/certifications (enc (or certifications #{})))
    weekly-capacity-hours   (assoc :operator/weekly-capacity-hours weekly-capacity-hours)
    true                    (assoc :operator/committed-hours (or committed-hours 0))))

(defn- pull->operator [m]
  (when (:operator/id m)
    {:id (:operator/id m) :name (:operator/name m)
     :certifications (or (dec* (:operator/certifications m)) #{})
     :weekly-capacity-hours (:operator/weekly-capacity-hours m)
     :committed-hours (or (:operator/committed-hours m) 0)}))

(def ^:private operator-pull
  [:operator/id :operator/name :operator/certifications
   :operator/weekly-capacity-hours :operator/committed-hours])

(defn- screening->tx [{:keys [operator-id verdict]}]
  {:screening/operator-id operator-id :screening/verdict verdict})

(defn- pull->screening [m]
  (when (:screening/operator-id m)
    {:operator-id (:screening/operator-id m) :verdict (:screening/verdict m)}))

(def ^:private screening-pull
  [:screening/operator-id :screening/verdict])

(defn- assignment->tx [{:keys [id task-id operator-id status]}]
  {:assignment/id id :assignment/task-id task-id
   :assignment/operator-id operator-id :assignment/status status})

(defn- pull->assignment [m]
  (when (:assignment/id m)
    {:id (:assignment/id m) :task-id (:assignment/task-id m)
     :operator-id (:assignment/operator-id m) :status (:assignment/status m)}))

(def ^:private assignment-pull
  [:assignment/id :assignment/task-id :assignment/operator-id :assignment/status])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (task [_ id] (pull->task (d/pull (d/db conn) task-pull [:task/id id])))
  (all-tasks [_]
    (->> (d/q '[:find [?id ...] :where [?e :task/id ?id]] (d/db conn))
         (map #(pull->task (d/pull (d/db conn) task-pull [:task/id %])))
         (sort-by :id)))
  (operator [_ id] (pull->operator (d/pull (d/db conn) operator-pull [:operator/id id])))
  (all-operators [_]
    (->> (d/q '[:find [?id ...] :where [?e :operator/id ?id]] (d/db conn))
         (map #(pull->operator (d/pull (d/db conn) operator-pull [:operator/id %])))
         (sort-by :id)))
  (assignment [_ id] (pull->assignment (d/pull (d/db conn) assignment-pull [:assignment/id id])))
  (assignments-of-operator [_ operator-id]
    (->> (d/q '[:find [?id ...] :in $ ?op
                :where [?a :assignment/operator-id ?op] [?a :assignment/id ?id]]
              (d/db conn) operator-id)
         (map #(pull->assignment (d/pull (d/db conn) assignment-pull [:assignment/id %])))
         (sort-by :id)))
  (screening-of [_ operator-id]
    (pull->screening (d/pull (d/db conn) screening-pull [:screening/operator-id operator-id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :task-upsert            (d/transact! conn [(task->tx value)])
      :assignment-upsert      (do (d/transact! conn [(assignment->tx value)])
                                   (let [t (task s (:task-id value))
                                         op (operator s (:operator-id value))]
                                     (d/transact! conn [(operator->tx (update op :committed-hours
                                                                               (fnil + 0) (:estimated-hours t 0)))])))
      :screening-verdict-set  (d/transact! conn [(screening->tx value)])
      :dispute-apply
      (d/transact! conn [(assignment->tx (merge (assignment s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-tasks [s ts]
    (when (seq ts) (d/transact! conn (mapv task->tx (vals ts)))) s)
  (with-operators [s os]
    (when (seq os) (d/transact! conn (mapv operator->tx (vals os)))) s)
  (with-assignments [s as]
    (when (seq as) (d/transact! conn (mapv assignment->tx (vals as)))) s)
  (with-screenings [s scs]
    (when (seq scs) (d/transact! conn (mapv screening->tx (vals scs)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [tasks operators assignments screenings contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-tasks tasks) (with-operators operators)
         (with-assignments assignments) (with-screenings screenings)
         (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
