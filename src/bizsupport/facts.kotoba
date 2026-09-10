(ns bizsupport.facts
  "R0 certification catalog — the ONLY compliance/certification classes the
  RoutingGovernor will accept as evidence of an operator's clearance to
  handle a client task requiring that domain's data-handling discipline
  (mirrors `cloud-itonami-isic-8291`'s `dossier.facts` / `cloud-itonami-
  isic-6311`'s `marketdata.facts` discipline: honesty over coverage). Every
  entry here is a real, citable, well-known compliance/data-protection
  standard — never a fabricated certification class. An operator (or a
  task) citing a class not in this catalog is rejected outright by
  `bizsupport.policy`'s clearance-tier-gate, regardless of confidence.")

(def catalog
  "Each entry: {:id :name :domain :issuing-body}. `:id` is the value that
  must appear in an operator's `:certifications` set and a task's
  `:required-certifications` set for the clearance-tier-gate to treat it as
  real."
  [{:id :hipaa
    :name "HIPAA (Health Insurance Portability and Accountability Act) compliance"
    :domain :healthcare-data
    :issuing-body "U.S. Department of Health & Human Services"}
   {:id :pci-dss
    :name "PCI DSS (Payment Card Industry Data Security Standard)"
    :domain :payment-data
    :issuing-body "PCI Security Standards Council"}
   {:id :soc2
    :name "SOC 2 (System and Organization Controls 2)"
    :domain :general-data-handling
    :issuing-body "American Institute of CPAs (AICPA)"}
   {:id :gdpr-data-processor
    :name "GDPR Data Processor obligations (Art. 28)"
    :domain :personal-data
    :issuing-body "EU General Data Protection Regulation"}
   {:id :iso-27001
    :name "ISO/IEC 27001 (Information Security Management)"
    :domain :general-data-handling
    :issuing-body "International Organization for Standardization"}])

(def allowed-certification-classes
  "The closed set of `:id` values the clearance-tier-gate will accept
  anywhere — on an operator's `:certifications` or a task's `:required-
  certifications`. A class not in `catalog` (e.g. :self-declared,
  :trust-me) must be rejected, not silently accepted because it looks like
  a keyword."
  (into #{} (map :id catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全業界標準' in prose, 5 real named standards in fact)."
  []
  {:certification-count (count catalog)
   :domains (into (sorted-set) (map :domain catalog))
   :note "R0 scope: 5 real, named compliance/data-handling standards. Extend only by appending a real, citable standard -- never fabricate one."})

(defn class-allowed? [cert-class]
  (contains? allowed-certification-classes cert-class))
