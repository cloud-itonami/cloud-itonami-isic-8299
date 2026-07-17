# Operator Quickstart — cloud-itonami-isic-8299

Shortest path from clone to a verified local dry-run for **ISIC 8299** (`cloud-itonami-isic-8299`).

## Who this is for

- Operators and developers building or forking governed task-dispatch services
- Teams that need proven PII/certification/capacity safeguards before going live
- Anyone evaluating the ISIC 8299 open business blueprint

## Prerequisites

- Clojure 1.12+ (`clojure --version`)
- Java 17+
- Git

No invented metrics; this is a governed OSS blueprint, not a hosted SaaS demo.

## 1. Clone

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-8299.git
cd cloud-itonami-isic-8299
```

## 2. Run tests

```bash
clojure -M:dev:test
```

Verify governor contract, store parity, phases, and facts. Fix failures before operating.

## 3. Open the product face

```bash
open docs/index.html   # or: python3 -m http.server -d docs 8080
```

Publish: enable GitHub Pages on `main` `/docs`, or any static host.

## 4. Where the RoutingGovernor sits

- Blueprint key: `RoutingGovernor`
- Source: `src/bizsupport/governor.cljc` (certification, capacity, scope gating)
- Pattern: TaskRouter-LLM proposes → RoutingGovernor validates → commit to audit ledger or hold

## 5. Claim / go-live

- Free claim funnel: https://itonami.cloud/isco-1212/
- Paid path docs: https://itonami.cloud/docs/go-live.md
- Blueprint: `blueprint.edn`

## Constraints

- Do not invent users/revenue numbers for marketing
- No force-push; keep AGPL headers
- Secrets stay out of this repo
