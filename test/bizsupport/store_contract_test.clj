(ns bizsupport.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [bizsupport.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Patient intake data entry (demo)" (:title (store/task s "tk-100"))))
      (is (= #{:hipaa} (:required-certifications (store/task s "tk-100"))))
      (is (= :high-value (:value-tier (store/task s "tk-200"))))
      (is (= "Alice (demo)" (:name (store/operator s "op-100"))))
      (is (= #{:hipaa :soc2} (:certifications (store/operator s "op-100"))))
      (is (= 30 (:committed-hours (store/operator s "op-100"))))
      (is (= 3 (count (store/all-tasks s))))
      (is (= 3 (count (store/all-operators s))))
      (is (nil? (store/screening-of s "op-100")) "unscreened operator has no verdict on file"))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "task upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :task-upsert :value {:id "tk-100" :status :in-progress}})
        (is (= :in-progress (:status (store/task s "tk-100"))))
        (is (= "Patient intake data entry (demo)" (:title (store/task s "tk-100"))) "title preserved"))
      (testing "assignment upsert commits and bumps operator committed-hours"
        (store/commit-record! s {:effect :assignment-upsert
                                 :value {:id "tk-100-op-100" :task-id "tk-100" :operator-id "op-100" :status :assigned}})
        (is (= :assigned (:status (store/assignment s "tk-100-op-100"))))
        (is (= 35 (:committed-hours (store/operator s "op-100"))) "30 + tk-100's 5h"))
      (testing "screening-verdict-set commits and reads back via screening-of"
        (store/commit-record! s {:effect :screening-verdict-set
                                 :value {:operator-id "op-300" :verdict :hit}})
        (is (= :hit (:verdict (store/screening-of s "op-300")))))
      (testing "dispute-apply patches the target assignment"
        (store/commit-record! s {:effect :dispute-apply
                                 :value {:patch {:status :disputed}}
                                 :path ["tk-100-op-100"]})
        (is (= :disputed (:status (store/assignment s "tk-100-op-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/pro (:tier (store/contract s "tenant-acme"))))
      (is (true? (:active? (store/contract s "tenant-acme"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/task s "nope")))
    (is (= [] (store/all-tasks s)))
    (is (= [] (store/ledger s)))
    (store/with-tasks s {"x" {:id "x" :title "X"}})
    (is (= "X" (:title (store/task s "x"))))))
