(ns bizsupport.facts-test
  "The R0 certification catalog is the whole ground truth for the
  clearance-tier gate — these tests guard its own internal honesty (every
  class it advertises is actually backed by a catalog entry, no duplicate/
  aspirational entries)."
  (:require [clojure.test :refer [deftest is testing]]
            [bizsupport.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name domain issuing-body]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? domain))
      (is (string? issuing-body)))))

(deftest allowed-certification-classes-matches-catalog
  (is (= (into #{} (map :id facts/catalog)) facts/allowed-certification-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :hipaa))
  (is (facts/class-allowed? :pci-dss))
  (is (not (facts/class-allowed? :self-declared)))
  (is (not (facts/class-allowed? :trust-me)))
  (is (not (facts/class-allowed? nil))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:certification-count c)))
    (is (<= (:certification-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:domains c) :healthcare-data))))
