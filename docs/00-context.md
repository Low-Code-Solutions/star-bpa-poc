# 00 Context: STAR BPA POC

Status: **ACTIVE, 28 Oct 2026** (stale statements corrected for 09 S2/S20 and D58). Slice chosen: Work Pack Assignment (PPC). Applied under PO delegation (D55, confirmed by Khitaba); open to PO challenge.
Owner: Faraz. Master copy: this file in the repo, `docs/00-context.md` (ADR 0015); SharePoint "Project knowledge" and the Claude Project hold copies refreshed after merge.

## Purpose
LCS proof of concept to specify, build, test and showcase one small business-process slice for STAR
on Camunda 8 and PostgreSQL, and to learn how far Claude's tools and third-party connectors can carry
delivery from requirements to working software.

The slice complements D365 F&O, SAP, FMS and myPeople. Those systems stay the systems of record (SOR)
for their data. The POC automates the work between them, where today it runs on email, spreadsheets
or manual hand-offs.

## Questions the POC answers
1. Architecture fit: which system should own the data (SOR) and which should own the flow?
2. Requirements to code: can stories written here and held in Jira be built by Claude Code with few
   or no clarifying questions?
3. Toolset reach: which Claude surfaces and connectors pull their weight, and where do people still
   bridge gaps?
4. Showcase value: does the result make a credible demo?

## Slice
- **Chosen slice:** Work Pack Assignment, in Production Planning & Control (PPC). Today Work Packs are
  prepared in several systems and Worker assignments are kept in Excel and handed to FMS by hand
  (FMS<>D365 flow Int-5; minutes of 1 Oct 2026), so hours cannot be tracked per Worker.
- **Process start:** the Time & Method Analyst starts a Work Pack request from the React portal
  (POST /api/v1/workpacks; 09 S2, S20) and creates it with estimated man hours per phase (actor and
  start permission per D52; FRD-7 §7.6 step 11).
- **Process end:** every assigned Worker has a Project Hour Journal Line and a Digital Time sheet
  (EndEvent_WorkPackAssigned).
- **Main path:** create Work Pack, Project Hour Journal Header, confirm and release check (DMN),
  Project Engineer sign-off, schedule, Workpack print, Worker assignment file, row validation,
  Journal Lines, Digital Time sheets. Separate processes: replace a Worker assignment; notify owners
  when a linked drawing is revised.
- **Actors:** Time & Method Analyst (PPC); Project Engineer; Production Planner; Production Team;
  PPC Team; Supervisor; Production / Workpack Owner.
- **Epics:** Epic 1 Work Pack Assignment (BPA-3, 13 stories); Epic 2 Timesheet to Progress (BPA-4).
- **Decided:** in Sprint 0, under PO delegation (recorded 27 Oct 2026, D55). Decisions for Epic 1 are in
  claude/06-epic1-decisions-draft.md and claude/09-architecture-decisions-2026-10-27.md; the single
  domain data model is claude/11-data-model.md (repo docs/data-model.md, 09 S16).
- **Authority when sources differ (D57):** Jira for requirements; these knowledge files (master in the
  repo, ADR 0015) for standards; the decision log for rationale; everything else is a working copy.

Not chosen: HSE incidents, D365 to FMS batch orchestration, Transport requests, Clinic visits.

## In scope
- One process slice, modelled in BPMN, with decisions in DMN
- POC database for data no existing system masters
- Mocked integrations with SOR systems (API mocks for lookups, sample files for batch flows)
- For Epic 1: data from other systems is seeded POC master data, each row labelled with its real SOR
  (see 03-sor-matrix.md); the Production Team uploads the Worker assignment Excel file in the portal
  (POST /api/v1/assignment-files, D58); drawing revisions arrive through a mock D365 event endpoint

## Out of scope
- Changes to D365, SAP, FMS or myPeople
- An enterprise canonical data model across STAR systems (considered and set aside by the PO,
  28 Oct 2026; the POC keeps one domain data model, 09 S16)
- Production deployment
- Real client personal data (synthetic data only)
- For Epic 1: hours entry and approval, Work Pack closure (Epic 2); rescheduling (not in FRD-7, D27);
  material reservation and QC (master data only); payroll; AI features

## Team
- Faraz: Product Owner and Architect; approves stories as Ready and Done. Also supports Khitaba on
  business analysis and the dev team on build
- Khitaba: Business Analyst and Scrum Master
- Ahsan: Build Lead; repo, build architecture, test strategy, final pull-request approval
- Usama: Lead Developer; builds stories with Claude Code alongside Ahsan, job workers and integrations, peer review

## Plan
- Sprint 0 (1 week): setup, slice workshop, SOR matrix, first epic refined
- Sprint 1 (2 weeks): BPMN/DMN, happy-path stories, tests per scenario
- Sprint 2 (2 weeks): SOR lookups and mocks, exceptions and SLAs, showcase at review

## Working rules
- Codename "STAR" in all outputs. Use anonymised source material only.
- Partner-side integrations are batch/file-based unless a named source confirms otherwise.
- Never invent a business rule; mark it ASSUMPTION.

## Where things are
- Jira space: BPA (STAR BPA POC); Epic 1 = BPA-3, Epic 2 = BPA-4
- SharePoint: AlRushaidTech > STAR BPA POC (01 Setup and governance, 02 Source material,
  03 Workshops, 04 Requirements snapshots, 05 Showcase)
- GitHub: star-bpa-poc (Low-Code-Solutions organisation); Camunda 8.10.1 pinned in the repo
