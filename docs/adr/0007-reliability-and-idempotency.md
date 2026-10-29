# 0007 Reliability and idempotency

- Status: Accepted (G10 amended by D70)
- Date: 2026-10-27 (decided, 09 LOCKED); G10 amended 2026-10-28 (D70); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer); D70 on the PO call (Faraz with Khitaba and
  Ahsan/Usama)
- Informed: Khitaba (BA)
- 09 rows: S7; G10 (amended by D70)

## Context

Job workers can be re-run by Camunda after a failure, and a message can be published twice (for example
when the same assignment file is uploaded again). The design pack proposed "3 retries with backoff, then
an incident" pending convention A8 (07 §6, G8), and a message ID of `sha256:workpackNumber` with a unique
`sha256` on `assignment_file` (07 G10).

## Options considered

1. **3 retries with backoff, then an incident** (07 §6, G8; 08 Gate 1, proposal for ADR 0004).
   Rejected: no custom backoff (S7; 09 §3).
2. **Camunda defaults** (3 retries, immediate retry, incident at 0) with idempotent workers (S7). Chosen.
3. For duplicate files: **unique `sha256` on `assignment_file`** (G10 as first decided). Amended by D70:
   indexed, not unique.

## Decision

- **Retries (S7).** Camunda defaults: 3 retries, immediate retry, incident at 0; **no custom backoff**.
  Business/validation errors throw a BPMN error and are not retried (ADR 0006).
- **Idempotency (S7).** Every worker is idempotent on its business key (07 §6) through unique constraints or
  `ON CONFLICT`. Side effects such as email check a dedupe key first.
- **Duplicate message publish (G10, as amended by D70).** The message ID is
  `<file sha256>:<workpackNumber>`, so a re-publish is a no-op. `assignment_file.sha256` is **indexed, not
  unique**. An upload is a duplicate ("File already processed", nothing published) only when an earlier
  upload with the same content published at least one message; a file that was not routed can be resent
  unchanged.

## Consequences

- Re-run keys per worker follow S7 (06 D65): `save-workpack` on the process instance key (unique on
  `workpack`), `record-release-signoff` on the task key, `save-workpack-schedule` one per Work Pack; each
  returns the record already saved on a retry. The other keys are in 07 §6 and `docs/data-model.md`.
- SMTP down: the in-system notification is stored and the email retries under S7, then raises an incident
  (06 D46, with A8 replaced by S7).
- The G10 message-ID format is to be added to 04-conventions (L16).
- Every upload gets its own `assignment_file` row (D70; ADR 0009).

## Sources

09 S7, G10, L16, §3, change log (28 Oct, D70); 07 §6, §11 G8, G10; 06 D46, D65, D70; 08 Gate 1.
