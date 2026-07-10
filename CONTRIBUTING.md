# Contributing

`cloud-itonami-isic-8299` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real client or operator personal data, credentials or
  contract documents.
- Keep production task assignment and disclosures behind RoutingGovernor.
- Treat every new task/operator field as high-risk: add tests for
  clearance-tier-gate, capacity-gate, scope-gate, licensed-disclosure,
  confidence floor and audit logging.
- Never add a schema field for raw client PII (SSN, payment card, home
  address, financial account). If a proposed feature needs one, it does
  not belong in this repository — raise it as an ADR instead of adding
  the field.
- Never add a certification-class entry to `bizsupport.facts/catalog`
  that is not a real, named, citable standard.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
