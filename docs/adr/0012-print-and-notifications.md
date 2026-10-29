# 0012 Print and notifications

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: S1, S3, S8

## Context

A scheduled Work Pack is printed for the shop floor and the print is stored as an audit record
(D29; FRD-7 §7.3.3). A rerun of the worker must give the same file (S7, ADR 0007). Revised drawings
trigger an email and an in-system notice to the Work Pack owners (D44; BPA-16), and the POC must not send
real email. The PDF library and the mock SMTP server were open items for the Build Lead (06 follow-ups),
and whether the print needs Arabic text was asked (S3).

## Options considered

1. **Generate the PDF in React.** Rejected (S1: "Not generated in React").
2. **Backend worker with an HTML template converted to PDF** (S1). Chosen.
3. **Arabic text in the print**, with a Spike, ICU4J and an Arabic font. Not needed (S3).
4. **Relay mock mail to Outlook for a showcase.** Optional; needs IT to allow SMTP AUTH (S8).

## Decision

- **Print (S1).** Backend job worker `generate-workpack-print`: Thymeleaf fills an HTML template,
  OpenHTMLtoPDF (`io.github.openhtmltopdf`, pin latest) converts it to PDF, stored in `workpack_print`
  (bytea, sha256, unique `workpack_id`). PDFBox only in tests (assert extracted text, not bytes). Bundled
  embedded font (Noto Sans or DejaVu Sans). The PDF creation date is taken from the injected clock
  (ADR 0013) so a rerun gives the same sha256.
- **Language (S3).** The Work Pack print is English only; no Spike; no ICU4J or Arabic font.
- **Mock SMTP (S8).** Mailpit in Docker Compose; the backend sends to `mailpit:1025`; web inbox on 8025;
  tests start Mailpit with Testcontainers and assert through its API. Catch-only.

## Consequences

- Viewing the print in React is in ADR 0004 (S4).
- The notice content (one email per recipient per revision, the in-system `notification` rows) is a
  business decision in 06 D44, not repeated here; idempotency of the send uses the notification unique key
  `UNIQUE(workpack_id, drawing_ref, revision, recipient_username, channel)` (`docs/data-model.md` §5.9;
  ADR 0007).
- Mailpit is added to the compose stack when the notification work is built; it is not in
  `docker-compose.yml` as of 29 Oct.

## Sources

09 S1, S3, S4, S7, S8; 06 D29, D44 and follow-ups; `docs/data-model.md` §5.9.
