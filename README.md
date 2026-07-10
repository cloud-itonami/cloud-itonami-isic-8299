# cloud-itonami-isic-8299

Open Business Blueprint for **ISIC Rev.4 8299**: other business support
service activities n.e.c., narrowed to a **virtual-assistant / BPO
task-matching** service — dispatching client work requests (research,
scheduling, data entry, customer-support overflow) to a pool of contracted
human operators — published as an OSS business that any qualified operator
can fork, deploy, run, improve and sell.

Built on this workspace's [`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
and [`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291).

> **Why an actor layer at all?** A TaskRouter-LLM is great at drafting a
> task breakdown and suggesting who might handle it — but it has **no
> notion of an operator's certification entitlement, weekly capacity
> limits, or a client's PII scope boundary**. Letting it assign work
> directly invites a task requiring HIPAA/PCI-DSS handling routed to an
> uncertified operator, an operator silently overcommitted past their
> stated capacity, or client-confidential identifiers leaking into a task
> record. This project seals the TaskRouter-LLM into a single node and
> wraps it with an independent **RoutingGovernor**, a human **review
> workflow**, and an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **matches tasks to certified, capacity-available operators**. It
never processes payment, never holds employment/payroll responsibility for
operators (see `cloud-itonami-isic-7820` for the employer-of-record dispatch
model — a structurally different business), and never stores a client's
raw personal or financial identifiers — there is no field anywhere in this
schema for one (see `docs/adr/0001-architecture.md`). Required
certifications are limited to real, citable standards
(`src/bizsupport/facts.cljc`: HIPAA, PCI DSS, SOC 2, GDPR data-processor
obligations, ISO/IEC 27001) — never a fabricated certification class.

## Consuming this actor from another blueprint

The governed read op is the actual product surface: `:disclosure/query`
(a task's assignment status, columns limited to your contract tier). It
always runs through the RoutingGovernor's licensed-disclosure check — there
is no bypass.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, RoutingGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, deploy, support and sell the service |
| Trust controls | Governance, security reporting, policy tests, audit requirements |

The primary industry classification is **ISIC Rev.4 8299** because the
commercial activity is a business-support service not elsewhere classified
— here, virtual-assistant/BPO task dispatch specifically.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌───────────────┐   proposal      ┌────────────────────┐
   │ TaskRouter-LLM│ ────────────────▶│ RoutingGovernor     │  (independent system)
   │ (sealed)      │  draft + rationale│ clearance · capacity│
   └───────────────┘                  │ · scope · human      │
                                       └────────────────────┘
                                              │
                                   commit / disclose only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: TaskRouter-LLM never assigns, discloses, or resolves
a dispute the RoutingGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 8-operation demo through one OperationActor
clojure -M:lint
```

## Non-Negotiables

- Do not commit real client or operator personal data.
- Do not add a schema field for raw client PII (SSN, payment card, home
  address, financial account).
- Do not bypass the RoutingGovernor for production assignment or
  disclosures.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a certification-class catalog entry.

License: AGPL-3.0-or-later.
