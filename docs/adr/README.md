# Architecture decision records

Format: MADR short form (status, date, deciders in the header; context, options considered,
decision, consequences). Files `NNNN-kebab-title.md`, numbered from 0001 (09 L3).
The decisions log `09-architecture-decisions` is the record until each ADR below is written.

| ADR | Title | Covers (09 rows) | Status |
|---|---|---|---|
| 0001 | Runtime and versions | G1, S24 | Planned |
| 0002 | Application stack | Spring Boot workers, S23 | Planned |
| 0003 | Repo layout | L1 (amended by 0015) | Planned |
| 0004 | UI channel and task API | S2, S4, S14 (S15 rejected option) | Planned |
| 0005 | Identity and authorisation | S5, S6, G9, S22, L2 | Planned |
| 0006 | Validation pattern | G2, S14 conditions | Planned |
| 0007 | Reliability and idempotency | S7, G10 | Planned |
| 0008 | Messaging and instance state | G3, G6, G7 | Planned |
| 0009 | Assignment file intake | S18, S19 (Proposed until the BA confirms S19) | Planned |
| 0010 | Data model, SOR and migrations | S16, S17, L5 | Planned |
| 0011 | API contract and tooling | S20, S21 | Planned |
| 0012 | Print and notifications | S1, S3, S8 | Planned |
| 0013 | Time and dates | S9 | Planned |
| 0014 | Test strategy | L15 | Planned |
| [0015](0015-delivery-artifact-chain.md) | Delivery artifact chain: intent → spec → plan → PR | L1 (amended), L14 (replaced) | Accepted |
| 0016 | Jira as source of truth for requirements | Jira BPA-19 (Done, no file yet) | Planned |
