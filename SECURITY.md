# Security Policy

This project handles operator certification data, task/client metadata and
subscriber contract terms. Treat vulnerabilities as potentially high impact
even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real client or operator personal data exposure
- authorization bypass
- RoutingGovernor bypass (clearance-tier-gate, capacity-gate, scope-gate,
  licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond a contract's tier
- tenant isolation failures
- ingestion of client PII through an undocumented field

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on task/operator data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real client/operator personal data outside this repository.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for dispatchers, ops managers and service accounts.
