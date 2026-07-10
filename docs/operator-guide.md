# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-8299`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-8299
cd cloud-itonami-isic-8299
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious clients/operators. Production
task/operator records must stay outside the repository and be injected
through a store adapter, and every operator certification claim must
resolve to a real, verifiable standard.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one organization owns infrastructure and data |
| Managed tenant | an operator hosts for a customer |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo tasks/operators with real records (extend
  `bizsupport.facts/catalog` only with real, citable certification
  standards — never fabricate one)
- verify operator certifications against the real issuing body before
  registering them in the store
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define client contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the dispute-handling SLA
- get written legal review for the jurisdictions you serve (data-
  processing/PII regulations vary by jurisdiction)

## 4. Sales Motion

Start with a narrow offer:

1. onboard a small pool of certified operators for one certification class
2. prove governed, tier-scoped disclosure end to end
3. run one assignment workflow in assisted mode (human-approved)
4. export the audit ledger for review
5. convert to a metered or subscription contract

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (client request → governor → assignment →
  disclosure)
- backup/restore evidence
- incident contact and response window
- proof that production assignment/disclosure goes through
  RoutingGovernor
- proof that real client/operator personal data is not stored in Git
- proof that a dispute channel exists and is human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- verifying operator certifications against real issuing bodies
- local privacy/data-processing law review (GDPR, CCPA and equivalents)
- secure infrastructure and tenant isolation
- honest certification-catalog maintenance
- human review workflow for high-value-task and dispute operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator compliant by itself.
