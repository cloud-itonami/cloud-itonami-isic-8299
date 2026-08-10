# Open Business Blueprint: cloud-itonami-isic-8299

This repository publishes an OSS business model for operating a
virtual-assistant / BPO task-matching service (generic VA agency /
task-rabbit-for-business class) on itonami.cloud, with a governed
dispatch-only operating model: match client work to certified,
capacity-available operators, never process payment or hold employer
responsibility.

## Classification

- Repository name: `cloud-itonami-isic-8299`
- Primary classification: ISIC Rev.4 8299 (Other business support service
  activities n.e.c.), narrowed to virtual-assistant/BPO task dispatch
  specifically
- Activity: decomposing client work requests into tasks, matching tasks to
  certified/capacity-available contracted operators, governed disclosure
  of assignment status
- Served domain: task metadata, operator certification/capacity records,
  assignment status — never client PII, never payroll/employment records

The ISIC code describes business-support activities not elsewhere
classified. The task-matching actor is the first productized service
inside that classification in this fleet, and is a `:spec → real repo`
promotion in `kotoba-lang/industry`'s registry.

## Customer

Primary customers (contracted, licensed access only — never public/
anonymous):

- small/mid businesses needing overflow research, scheduling, data-entry
  or customer-support capacity without hiring
- healthcare/fintech clients needing certification-verified handling of
  regulated data (HIPAA/PCI-DSS-scoped tasks)
- other `cloud-itonami-{ISIC}` blueprint operators who need business-
  support dispatch as a licensed capability

## Problem

Traditional BPO/VA agencies match work to workers manually or with opaque
internal tooling. Clients cannot verify that a task requiring regulated-
data handling actually went to a certified operator, and agencies have no
structural guarantee against operator overcommitment or client-PII leakage
into task records.

## Offer

Operators provide an OSS actor for task dispatch:

- task decomposition from client work requests (no PII fields)
- certification-verified operator matching (HIPAA/PCI-DSS/SOC 2/GDPR/
  ISO 27001 — real, citable standards only)
- capacity-aware assignment (never overcommits a contracted operator)
- governed, tier-scoped disclosure of assignment status
- a dispute-resolution channel, always human-reviewed
- immutable audit ledger of every assignment/disclosure event

The core promise: TaskRouter-LLM can draft task breakdowns and assignment
proposals, but it cannot commit an assignment or disclose status unless
the independent RoutingGovernor allows it.

## Revenue

Operators can sell:

- per-task or per-seat licensed access (contract tenant × tier)
- tiered subscriptions: `:tier/basic` (status only) → `:tier/pro`
  (+ hours/certification detail) → `:tier/institutional` (+ operator
  identity/raw audit detail)
- wholesale API access to other `cloud-itonami-{ISIC}` blueprint operators
- managed hosting: monthly subscription per tenant
- compliance package: audit export, dispute-handling SLA, security review

| Package | Customer | Price shape |
|---|---|---|
| Basic dispatch | small business overflow work | per-task or low monthly tier |
| Pro tier | regulated-data clients (health/fintech) | monthly platform fee |
| Institutional tier | enterprise BPO buyer | monthly fee + usage |
| Fleet wholesale | other cloud-itonami operators | API metering |
| Managed Starter | one VA/BPO agency (30–50 contracted operators, ~5 in-house coordinator seats) | ¥15,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against 5 real competitor
products. **Only 3 of the 5 publish real numbers.** Published:
**freee業務委託管理 (旧 pasture)** — Free ¥0/月 (1 active partner ID),
Starter 実質¥1,980(税抜)/月〜 ＋従量課金 (annual contract), Standard
「料金はお問い合わせください」
(<https://www.freee.co.jp/partner-management/pricing/>); **Asana** —
Starter ¥1,200/ユーザー/月 (annual) or ¥1,475 (monthly), Advanced ¥2,700 /
¥3,300, Enterprise custom (<https://asana.com/ja/pricing>); **monday.com
work management** — Basic ¥1,300/席/月, Standard ¥1,650, Pro ¥3,200 (annual,
3-seat minimum), Enterprise custom (<https://monday.com/pricing>). At the
assumed 5 coordinator seats that is ≈¥6,000–¥16,500/月 for the seat-priced
tools. **Not published — this is an observation, not a gap**: **Worksuite**
answers its own pricing page with "Every plan is a little different. Let's
shape yours." and a demo form, no figure anywhere
(<https://worksuite.com/pricing/>); **WorkMarket** (ADP) sells on custom
enterprise quote only (<https://www.workmarket.com/pricing>). **The two
products that are actually this actor's own class — freelancer/contingent
workforce management systems — are exactly the two that disclose nothing, so
the upper bound of this band is unmeasured. ¥15,000/月 is set from the
seat-priced floor only, and should be revised upward if real FMS pricing
becomes evidenced.** ¥15,000/月 sits just under monday.com Pro at 5 seats
(¥16,000) and Asana Advanced at 5 seats (¥16,500), which is the right
position because this actor holds neither end of the workflow: it does not
issue purchase orders, receive invoices or move money (freee's job), and it
is not the agency's project UI (Asana/monday's job). It is a layer on top of
both, and what it adds is not sold by any of the 5: a regulated-data task
can never route to an uncertified operator, a contracted operator can never
be silently overcommitted past stated capacity, a `:hit` sanctions verdict
is a HARD un-overridable block, and there is no schema field anywhere for a
client's raw PII.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥15,000/月 flat) is available now —
<https://buy.stripe.com/fZu5kEaeHbFCfdSguueEo00>. This is a no-code
Stripe-hosted checkout on Gftd Japan 株式会社's live account; nothing in
this repo's actor code changed. Fulfillment is manual today — after
subscribing, contact gftdcojp to arrange managed-tenant setup. **No agency
has subscribed to this tier yet — this is a live, working checkout with zero
paid tenants, not a claim of existing revenue.**

## Unit Economics

Track these numbers for every operator:

- operator onboarding/certification-verification hours
- monthly infrastructure cost
- LLM cost per operation (decompose/assign/disclosure)
- dispute-handling hours per tenant
- gross margin after infrastructure and support
- churn and expansion revenue per contract tier

## Open Participation

Anyone may fork, run the demo, deploy a self-hosted instance, submit
issues/patches, or create a local operator business. itonami.cloud should
require certification before listing an operator as trusted, routing
customer leads, or allowing managed disclosure under the platform brand.

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-isic-8299"
 :itonami.blueprint/name "Virtual-Assistant / BPO Task-Matching Actor"
 :itonami.blueprint/isic-rev4 "8299"
 :itonami.blueprint/domain :business-support/task-dispatch
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-8299"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real client or operator personal data.
- Do not add a schema field for raw client PII.
- Do not bypass the RoutingGovernor for production assignment or
  disclosures.
- Do not serve a disclosure to a tenant without an active, registered
  contract.
- Do not fabricate a certification-class catalog entry.
