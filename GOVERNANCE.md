# Governance

`cloud-itonami-isic-8299` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- TaskRouter-LLM cannot directly assign, disclose or resolve a dispute.
- RoutingGovernor remains independent of the advisor.
- hard governor violations (clearance-tier-gate, capacity-gate,
  scope-gate, licensed-disclosure) cannot be overridden by human approval.
- a dispute request never auto-resolves, at any rollout phase.
- an assignment involving a :high-value task always reaches a human,
  regardless of confidence.
- every commit, hold and disclosure event is auditable.
- no schema field exists for raw client PII — scope is structural, not a
  runtime filter someone could forget to call.
- real client/operator personal data and contract documents stay outside
  Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing data to an uncontracted party
- assigning a task requiring a certification an operator does not hold
- overcommitting an operator beyond their stated capacity
- misrepresenting certification status
- failing to respond to security incidents or disputes
- hiding material changes to customer-facing operation
