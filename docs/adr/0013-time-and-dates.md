# 0013 Time and dates

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED; changed to Asia/Riyadh 27 Oct 16:35 PKT); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: S9

## Context

Several rules depend on "today": the date to complete may not be before today (D20), the planned dates
must respect the lead time (D9), and stories compare dates in Gherkin. D28 had set business dates in
Asia/Riyadh as an ASSUMPTION. If FEEL or DMN call `today()`/`now()`, or code reads the system clock, tests
depend on when and where they run.

## Options considered

The 09 change log records that S9 was **changed** to Asia/Riyadh on 27 Oct, confirming D28; the earlier
S9 value is not recorded in the sources. No other option is recorded.

## Decision

- Business dates are in **Asia/Riyadh (KSA time, UTC+3, no daylight saving)** and stored as `date` (S9).
- Timestamps are `timestamptz` in UTC.
- **One injectable `java.time.Clock`**, zone from config `app.business-zone=Asia/Riyadh`.
- **No `today()` or `now()` in FEEL or DMN**; the worker passes `today` in.
- ISO 8601 in the API (ADR 0011); React shows timestamps in KSA time.
- Tests state "Given today is … (Asia/Riyadh)".

## Consequences

- D28 is confirmed and no longer an ASSUMPTION; no contradiction remains (S9).
- The print's creation date comes from the same clock, so a rerun gives the same sha256 (S1, ADR 0012).
- S9 (Asia/Riyadh) is to be added to 04-conventions (09 §4 item 5).
- BPA-12 and BPA-22 already state Asia/Riyadh, so no story change is needed for the zone (09 §4 item 7).

## Sources

09 S1, S9, change log (27 Oct 16:35 PKT), §4 items 5, 7; 06 D9, D20, D28.
