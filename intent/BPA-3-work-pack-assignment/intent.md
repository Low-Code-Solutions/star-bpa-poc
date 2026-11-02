# Intent: Work Pack Assignment (Epic 1, BPA-3)

Author: Claude, from 00-context (ACTIVE, 28 Oct 2026) and the BPA-3 epic (v1.0, 28 Oct 2026).
Status: Accepted (Faraz, Product Owner, 2 Nov 2026; BPA-3 comment 10462; recorded under BPA-45)
Backfilled under BPA-33 (ADR 0015); the requirements themselves were agreed earlier in Jira and are
not changed by this file.
Source of truth: Jira BPA-3 and its stories for scope and acceptance criteria; this file states intent.

## Problem
In STAR's Production Planning & Control (PPC), Work Packs are prepared across several systems.
Production teams keep rosters and team assignments in Excel and upload hours to FMS; planning is at
team level, not individual Workers (MOM-2026-10-01). Work Packs and their Workers are not tracked in
one process, so hours cannot be tracked per Worker. The POC keeps a Worker-level assignment file
(C10, C18 in BPA-3), which is the future flow the same minutes describe.

Source note (contradiction, flagged): BPA-3 and 00-context say "Worker assignments in Excel" and
cite the FMS<>D365 flow as Int-5. The source-register summary of MOM-2026-10-01 says team
assignments, and lists employee data as interface 5 and team assignment as interface 6. This file
follows the minutes; which Int number applies is unverified (FLOW-FMS-D365 diagram to be checked).

## Proposed outcome
Every Work Pack is created with estimated man hours per phase, passes a release check, is signed off,
scheduled once (no rescheduling: D27, FRD-7 §7.5.5 and §7.6), printed, and assigned to individual Workers; every assigned Worker has a Project Hour
Journal Line and a Digital Time sheet, so hours can later be booked per Worker (Epic 2).

## Affected users
Time & Method Analyst (PPC), Project Engineer, Production Planner, Production Team, PPC Team,
Supervisor, Production / Workpack Owner. Each uses a React portal for their own tasks.

## Affected systems
- D365 F&O (project, WBS, materials, drawing revisions via Document Control), FMS (job codes, cutting
  sheets, today's assignment intake), P6 via the D365 PMS database (activities), myPeople (workers):
  all stay systems of record and are represented in the POC by seeded master-data copies labelled
  with their real SOR (03-sor-matrix).
- New in the POC: Camunda 8.10.1 for the flow, a Spring Boot backend, PostgreSQL schema `bpa`, a React
  portal.

## Constraints
- No changes to D365, SAP, FMS or myPeople; no live integration in Epic 1. Drawing revisions arrive
  through a mock event endpoint; partner-side integrations are batch or file based unless a named
  source says otherwise.
- Synthetic data only; codename STAR; no business rule without a source (otherwise ASSUMPTION).
- Every field has exactly one SOR.
- Business dates in Asia/Riyadh; timestamps in UTC (D28, 09 S9).
- Architecture decisions in 09 (LOCKED 27 Oct 2026) are inputs, not open points.

## Out of scope
Hours entry and approval, Work Pack closure (Epic 2); rescheduling; material reservation and QC
(master data only); payroll; material consumption form; job card and return material voucher on the
print; AI features; Camunda Tasklist for end users.

## Success
Epic 1 Definition of Done in BPA-3: the main process runs end to end from the portal, release is
blocked without reserved materials and an approved drawing, every assigned Worker gets a Journal Line
and a Digital Time sheet, the audit trail is complete, and the scorecard records Claude Code's
questions and human gap-bridging per story.

## Open questions (with STAR; non-blocking, working decision in brackets)
1. Where is a project created? (D365 PM, C8a)
2. Where is the WBS created? (D365 PM, C8c)
3. Job Number vs project number (D13)
4. Where are drawing revisions registered? (D365 Document Control, C15, D41)
5. Which system creates employee records after go-live? (myPeople, C14)
6. Where is a Work Pack created? (POC master data, real SOR FMS, D47)
7. Agency worker tag field and values (ASSUMPTION, C11)
8. Which worker identifier does the FMS assignment file carry? (Badge No, D59)
Also requested from STAR: the real FMS worker-assignment template and the FMS integration document.
