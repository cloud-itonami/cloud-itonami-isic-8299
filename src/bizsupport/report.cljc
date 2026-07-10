(ns bizsupport.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the RoutingGovernor's licensed-disclosure
  gate approved for the caller's contract tier (see `:disclosure/query`).
  This namespace only renders the approved columns, so a disclosure can
  never exceed the licensed tier."
  (:require [bizsupport.store :as store]))

(defn render-task
  "Render one task's status over exactly `columns` (already governor-
  approved). `:operator-name`/`:raw-source` are only ever rendered when
  the caller's tier included them."
  [db task-id columns]
  (let [t (store/task db task-id)
        assignments (filter #(= task-id (:task-id %))
                             (mapcat #(store/assignments-of-operator db (:id %))
                                     (store/all-operators db)))
        a (first assignments)
        cell (fn [col]
               (case col
                 :task-id (:id t)
                 :title (:title t)
                 :status (:status t)
                 :assigned-operator-id (:operator-id a)
                 :estimated-hours (:estimated-hours t)
                 :required-certifications (:required-certifications t)
                 :operator-name (:name (store/operator db (:operator-id a)))
                 :raw-source (pr-str a)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
