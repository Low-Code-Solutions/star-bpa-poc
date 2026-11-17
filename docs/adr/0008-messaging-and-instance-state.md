# 0008 Messaging and instance state

- Status: Accepted (G7 amended by D61)
- Date: 2026-10-27 (decided, 09 LOCKED); G7 amended 2026-10-28 (D61, D67); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer); D49 by Khitaba (BA); D61 accepted 28 Oct
  (build-team answer relayed by Khitaba); D62 accepted 28 Oct; D67 by Faraz (Product Owner)
- Informed: Faraz (G3 acknowledgement pending, non-blocking)
- 09 rows: G3, G6, G7 (amended by D61); L10 (the `assignment_state` part)

## Context

Three things arrive from outside a running instance: the Worker assignment file, its replacement, and a
drawing revision from D365 Document Control (07 §5). The intake must know whether the Work Pack's instance
is ready for a file, still assigning, finished, or replacing (D37), without a message being buffered for
the wrong instance (07 G4, G7). Rows of one file may be validated later by different instances, so someone
must own the result (07 G3). A drawing revision must be checked (unknown drawing, duplicate) before any
process starts (07 G6).

## Options considered

1. **G3 result.** The intake writes routing outcomes and `validate-worker-assignment` writes row outcomes,
   with the result rebuilt from `assignment_file_row` (07 G3, Claude recommendation A, decided as D49).
   The other options behind "recommendation A" are not recorded in the sources.
2. **G6 recording.** Record the revision in the API, synchronously, so it can return 404 and drop
   duplicates before publishing (07 G6). No alternative is recorded in the sources.
3. **G7 state.** A `workpack.assignment_state` column set by the workers (07 G7, merged with G4 / D50).
   Holding it as a process variable (task headers, input mappings) is ruled out by L10.
4. **Message buffer.** `AssignmentFileReceived` with a 1-hour TTL as a buffer (D31, D50). Replaced by
   TTL 0 (D62).

## Decision

- **Result owner (G3).** D49 confirmed: routing outcomes are written by the intake step, row outcomes by
  `validate-worker-assignment`; the result is rebuilt from `assignment_file_row` after each update. Since
  D58 the intake step is the upload endpoint, and the result is shown on screen and downloadable
  (ADR 0009).
- **Drawing revision (G6).** The API records the revision synchronously: 404 for an unknown drawing,
  duplicate dropped, previous revision marked Superseded, then `DrawingRevised` published. The POC never
  decides Superseded itself; it applies the status the D365 event reports (D45, D53). `supersededRevision`
  travels as a process variable and is not stored (D68).
- **Instance state (G7, as amended by D61).** `workpack.assignment_state` is an enum
  `AwaitingFile, Assigning, Assigned, Replacing`, set by the workers and the upload endpoint. It is **not a
  process variable**: no task headers or input mappings; each worker element that changes it carries
  `bpmn:documentation` naming the transition (L10). The transition table and upload gates live in
  `docs/data-model.md` §8.
- **Messages** (07 §5, with D62 and G7):

  | Message | Correlation key | TTL | Published by |
  |---|---|---|---|
  | `AssignmentFileReceived` | `workpackNumber` | 0 (D62; was PT1H) | Upload endpoint, only when `assignment_state = AwaitingFile` |
  | `AssignmentFileReplaced` | `workpackNumber` | 0 (G7) | Upload endpoint, setting `Replacing` in the same transaction (D61) |
  | `DrawingRevised` | `drawingRef:revision` | 0 | `POST /api/v1/drawing-revisions` (G6) |
  | `WorkPackAssigned` | `workpackNumber` (messageId the same) | 0 (ASSUMPTION; BPA-49) | Job worker `publish-workpack-assigned` behind the Epic 1 message end event, and `POST /api/v1/timesheet-processes` (E2-D18); starts `Process_PPC_TimesheetToProgress`. Duplicate guard: `published_message`, published first and recorded after |

  The message ID for assignment files is in ADR 0007 (G10).
- **Upload while busy.** Upload while `Assigning` → "Assignment still in progress; try again later";
  upload while `Replacing` → "Replacement already in progress" (D61 as corrected by D67).

## Consequences

- With TTL 0 a message that cannot correlate is not kept: its rows read "Not processed: Work Pack not
  ready; resend the file" on screen at once. No sweep job (D62).
- Shared contract: BPA-10 sets AwaitingFile, BPA-22 sets Assigning (or AwaitingFile when every row is
  rejected), BPA-15 sets Assigned, a replacement upload sets Replacing and BPA-13 resets Assigned on both
  outcomes (spec.md shared contracts).
- G3 needs Faraz's acknowledgement (09 §2); it does not block the build.

## Sources

09 G3, G6, G7, L10, §2, change log (28 Oct, D61, D67); 07 §5, §11 G3, G4, G6, G7; 06 D31, D37, D45,
D49, D50, D53, D58, D61, D62, D67, D68; `docs/data-model.md` §8.
