# UI screen map: STAR BPA POC portal (epic BPA-124)

Status: **DRAFT, 27 Nov 2026**, written from Jira and the prototype export only (see "Inputs"). Owner: Ahsan. The standing reference for BPA-125 to BPA-137.
The repo is the master copy (ADR 0015). Questions in section 9 are open; every rule they would settle is marked ASSUMPTION below.

## Inputs and precedence
- **Read:** Jira BPA-124 and BPA-125 to BPA-137 (Description, Gherkin, BPMN Reference, SOR Notes, Source Reference, status, comments), read only on 27 Nov 2026; the 19 prototype files in `ui-design/` (outlined with a throwaway script, nothing written there); `frontend/`; ADRs 0003, 0004, 0005, 0006, 0013, 0014, 0015, 0017; `personas.yaml`; `api/openapi.yaml`.
- **Not available (not in the repo, and the PO chose to proceed without them):** the epic's `intent.md` and `spec.md` (Jira names `claude/29-ui-epic-intent.md` and `claude/30-ui-epic-spec.md`, accepted rev 1 on 27 Nov); the UI decisions U-D29 to U-D104 (`claude/27-ui-decisions-2026-11-27.md`, `claude/25`); the UI component guide (DRAFT v2); the brand and colours decision (U-D28); the design naming rule. Where a rule is quoted below from a story it says so; the stories quote U-D84 to U-D101 only in part.
- **Precedence when sources disagree:** Jira story → epic spec.md → U-D84–U-D104 → U-D29–U-D83 → U-D28 brand → component guide → the prototype.
- **Decision (Ahsan, 27 Nov 2026, twice): colours and everything else are taken from the files in `ui-design/`, and the build must be exactly the same as them.** This replaces the earlier draft rule "no hex codes, font names or pixel sizes from the prototype" and settles the font and status-colour conflicts (C6, C7) for now. **"Exactly the same" is the acceptance test of every UI story:** the screen matches its template at 1440 px and 390 px in layout, spacing, sizes, colours, fonts, icons, labels and states (section 11 lists the exact values for BPA-125; each later story's plan extracts them for its own components). The only differences allowed are the ones the Jira stories and the ADRs require, listed in section 11.5; any other difference is a defect or a question. Layout, components, labels, flows, behaviour, colours, fonts, sizes, radii, shadows and spacing come from the prototype; the tokens are listed in section 10. **Figures on it are still demo data and are never copied.** The export's README says the live prototype (claude.ai artifact 8EmPMXMMcmrTb2Wsh3BGQb) is the source of truth and that fonts and small details can differ: nobody has compared the export with it yet (human gap). If U-D28 or the component guide, when they reach the repo, disagree with the export, Faraz or Khitaba decides; the precedence list above still applies.
- **Frontend path:** `frontend/` (ADR 0003), not `portal/src/` (the component guide).

## 1. Inventory: file → screen → persona → state

All persona files share one app (1,036 lines) and differ only in the starting persona and state. 02 is a separate, larger app. 04 is a flows page.

| File | Screens it shows | Persona | State |
|---|---|---|---|
| 01-Sign-in | Sign in (title "Sign in", subtitle "STAR Portal") | all | sign-in |
| 02-Leadership-dashboard | Dashboard, Projects (list, Timeline), Work Packs, Production Time Sheet, Manpower (Overview, People), Man-hours, Project detail, Work Pack detail, Worker detail, Notifications, Export drawer, Filters drawer | Leadership (`leader.user`) | normal; Loading / No data / Error through a "Prototype state" switch |
| 03-Personas-all | the persona app, opens as TMA (no persona list page exists in the file) | Time & Method Analyst | normal |
| 04-UX-flows | UX flows: "End to end" and one flow per role | all (reference) | n/a |
| 10-Persona-PPC-tma | Dashboard, My tasks, Work Packs (Create Work Pack, Print Work Packs), Man-hours, Projects | Time & Method Analyst (`tm.user`) | normal |
| 10-Persona-Engineering-pe | Dashboard, My tasks, Work Packs, Projects | Project Engineer (`pe.user`) | normal |
| 10-Persona-PPC-pp | Dashboard, My tasks, Work Packs (Schedule), Manpower, Man-hours, Projects | Production Planner (`pl.user`) | normal |
| 10-Persona-PPC-ppc | all 7 menu items; Hour Journal, FMS files, Project assignments | PPC Team (`pc.user`) | normal |
| 10-Persona-Production-pt | Dashboard, My tasks, Work Packs (Worker assignments, File history), Time sheets, Manpower, Projects | Production Team (`pt.user`) | normal |
| 10-Persona-Production-wo | Dashboard, My tasks, Work Packs (no Draft), Man-hours, Projects | Workpack Owner (`wo.user`) | normal |
| 10-Persona-Production-sup | Dashboard, My tasks, Time sheets (Book daily hours, Upload hours), Manpower, Man-hours, Projects | Supervisor (`sv.user`) | normal |
| 10-Persona-Production-tk | same menu as the Supervisor; Validate hours | Timekeeper (`tk.user`) | normal |
| 10-Persona-Production-ps | same menu; Approve hours | Production Supervisor (`ps.user`) | normal |
| 10-Persona-ProjectManagement-pm | Dashboard, My tasks, Work Packs, Manpower, Man-hours (CBS cost alerts), Projects (Weekly progress) | Project Manager (`pm.user`) | normal |
| 10-Persona-PCON-cc | Dashboard, My tasks, Man-hours (By CBS), Projects | Cost Controller (`cc.user`) | normal |
| 90-State-loading | TMA dashboard with six skeleton blocks | any | loading |
| 91-State-no-data | TMA dashboard with "No data for this selection", and the "No project selected" variant | any | empty |
| 92-State-error | TMA dashboard with "We couldn’t load your data." | any | error |

### Shell (persona app)
- **Sidebar:** logo "STAR"; "Search menu" (empty result "No menu item matches"); the menu; a footer with initials and the persona name. On a phone the menu is a drawer.
- **Top bar:** a hamburger (phone only), search ("Search Work Pack No., Badge No., Time sheet", hint "⌘ K"), buttons "Dark mode", "Notifications" (a dot when there are some) and "Logout".
- **Page header:** icon tile and h1; "Data as of <date time> · Week 47 open"; a date-range button with a popover (This week (open), Last week, Last 4 weeks, Month to date, Last 3 months; From date, To date; "Time sheets and tasks count by work date; Work Packs are not date-filtered."); "Export".
- **Filter bar:** a project picker ("Projects: All (n)", search, "Select all (n)", "Deselect all", "Done"), chips and "Clear all".
- **Breadcrumb** (none on the Dashboard): `Dashboard › My tasks`, `Dashboard › <module> › <screen>`, `Dashboard › <module> › WP-00xxxx`, `Dashboard › Manpower › <Worker>`.
- **Toast** after an action: "<message> Next step: <Role>." with "Switch to <Role> ›" (prototype only).

### Persona home sections, in order (shown per role in section 5)
1 Summary, 2 Needs attention, 3 Where is work waiting?, 4 Progress to date, 5 Projects & Work Packs, 6 Tasks pending, 7 Man-hours and progress, 8 Man-hours by phase, 9 Manpower.

### Persona list screens (columns and row actions as in the prototype)
- **My tasks:** Task, Reference, Details, Waiting; "Open".
- **Projects:** Project, Name, Stage, Work Packs, In Progress, Time sheets in period; "View Work Packs".
- **Work Packs:** Work Pack, Project, Discipline, Status, Estimate, Progress; "View". Toolbar: TMA "Create Work Pack", "Print Work Packs"; PP "Schedule"; PT "Worker assignments".
- **Print Work Packs:** Work Pack, Project, Start, Status; "Print".
- **Schedule:** Work Pack, Project, Status, Start, Finish; "Schedule" / "View".
- **Assignments:** Work Pack, Project, Start, Assignment; "Submit file" / "Replace file"; "File history".
- **Time sheets:** Time sheet, Work Pack, Yard Supervisor, Work date, Hours, Status; Supervisor "Book hours" and "Upload hours", the others "Open" / "View".
- **Hour Journal:** Work Pack, Project, Estimated, Header, Lines, Status.
- **FMS files:** Week, Approved hours, FMS timesheet file, Hand-off, Completion report; "Record hand-off", "Upload again", "Download file"; "Upload completion report".
- **Project assignments:** User, Group, Projects; "Edit".
- **Weekly progress:** Project, Week, Status, Earned %; "Review" / "Reopen".
- **CBS cost alerts** (PM) and **Actual man-hours by CBS** (CC): CBS, Revised budget or Forecast, Actual, Over, Alert; CC "Review".
- **Notifications:** Notification, Date, Area, Type, Status; "Open"; "Mark all as read".
- **Man-hours:** Work Pack, Project, Phases, Estimated, Earned, Actual, Difference.
- **Manpower:** Badge No., Worker, Position, Type, Work Packs, Hours, Leave.
- Every list: "Search <what>", a section filter where there is one, and the pager "x–y of n". Empty: "Nothing matches your search or filters. Clear filters or widen the date range."

### Persona form screens
Confirm Work Pack, Create Work Pack, Sign off Work Pack release, Schedule Work Pack, Submit / Replace Worker assignment, Assignment file history, Work Pack print, Book daily hours, Validate hours, Approve hours, Final approval of hours, Approve completion %, Approve progress retraction, Approve the week’s progress, Reopen a locked week, Drawing revised, Review actual man-hours by CBS, Record hand-off to FMS, Upload FMS completion report, Project assignments (edit), Work Pack detail, Worker detail, Time sheet detail. Two columns: titled blocks on the left; "Checks" (✓ / ! / ✕) or "Hours summary" on the right; an optional reason box; an action bar.

### Leadership app (02) beyond the shared parts
- Menu: Dashboard, Tasks pending (an anchor), Work Packs, Time sheets (badge), Manpower, Man-hours, Projects, with sub-items "Project list" and "Timeline", and on a project "Overview, Facilities, Activities, Work Packs, Production Time Sheet, Manpower, Materials & Drawings, Weekly Progress, Cost Control, History".
- Extras: a global search, the "Prototype state" switch, a Filters drawer (Type of project, Contract, Work, People, Status, Tasks), a detail drawer, and an Export drawer with eleven reports (Excel and PDF each).

## 2. Two-way trace

### 2.1 Screen → story (the covered part)

| Screen / section / action | Story · scenario |
|---|---|
| Sign in; empty username; empty password; wrong login; Logout | BPA-125 sc.1 to 5 |
| Sidebar menu per role; Leadership menu; breadcrumb on detail and task screens; no breadcrumb on the Dashboard; parent highlighted; 403 page "You don't have access to this page" | BPA-126 sc.1 to 7 |
| Dashboard sections per role (outline); counts follow the date filter; "No project selected" | BPA-127 sc.1 to 3 |
| Where is work waiting? status column and role cards; own card "You"; "Oldest waiting 55 h"; 0 count | BPA-128 sc.1 to 4 |
| Cards open the next screen; no icon buttons in cards; keyboard; card with nothing behind it | BPA-129 sc.1 to 5 |
| Section filter pill; Yard Supervisor first; one section only; Clear; no match | BPA-130 sc.1 to 4 |
| One-page project; no filters; side menu scroll and highlight; section paging; project without live data | BPA-131 sc.1 to 5 (epic BPA-117) |
| Notifications (bell, page, mark read) | BPA-132, **parked** |
| Worker detail from Manpower; hours split; leave; no hours in my projects | BPA-133 sc.1 to 4 |
| Work Pack print preview; not scheduled; no cutting sheet | BPA-134 sc.1 to 3 |
| Book daily hours rows: + and − steps, "Fill 8 h for everyone", over 16 h, leave, 16 h boundary | BPA-135 sc.1 to 5 |
| Man-hours page and Manpower page sections; not in the menu for `pe.user` | BPA-136 sc.1 to 3 |
| Loading, no-data, error and Try again | BPA-137 sc.1 to 4 |

### 2.2 On screen, with no story in BPA-124 (gaps)
1. Top-bar search ("Search Work Pack No., Badge No., Time sheet"; "⌘ K").
2. Dark mode.
3. The date-range picker, its quick picks and its errors ("From date must not be after To date"). BPA-127 sc.2 uses the dates; nothing builds the picker.
4. Export (csv in the prototype, xlsx in the build) and the eleven Leadership reports.
5. Menu search; the "Switch to <Role>" toast (prototype only).
6. The shell around the Dashboard: PageHeader ("Data as of …"), the project picker and the filter chips ("Projects: All (n)"). BPA-127 sc.3 mentions deselecting projects only.
7. The My tasks table and its columns.
8. Every list screen and toolbar action in section 1 (Projects, Work Packs and its toolbar, Print Work Packs, Schedule, Assignments and File history, Time sheets, Hour Journal, FMS files, Project assignments, Weekly progress, CBS cost alerts, Man-hours, Manpower). Some exist in the repo from Epic 1 and 2; their new look has no story.
9. The redesigned look of the existing task forms (Create, Confirm, Sign off, Schedule, Submit assignment, Validate, Approve, Approve completion %, Retraction, Approve week, Reopen week, Review CBS, FMS hand-off and upload) and of Work Pack detail (BPA-73), Time sheet detail and the Work Pack list.
10. Leadership-only: Timeline/Gantt, heat tables, Required vs Available, the readiness list, the detail and Filters drawers, the Prototype-state switch, Production Time Sheet. Most belong to epic BPA-117.
11. The "Drawing revised" task of PE and WO (see conflict C14).
12. Redirecting to Sign in when a session expires (ADR 0005; today's portal does not).

### 2.3 Scenario → screen (gaps)
| Scenario | Gap |
|---|---|
| BPA-126 sc.7 "You don't have access to this page" with a link to the Dashboard | no template shows this page |
| BPA-129 sc.5 the Work Packs list with the filter chip "Draft" and "No rows match" | the persona app's list has no chips; Leadership's has |
| BPA-131 sc.5 "No POC activity · only In Progress projects have live data" | not in any template |
| BPA-127 sc.2 "Hours with Timekeeper 4" counts by work date | the template shows the card; the rule is only in a one-line note |
| BPA-128 sc.2 the own card "You" selected by default under "In Progress" | the template marks "You" but has no default-selection rule |
| BPA-126 sc.5 parent highlighted on Work Packs › Schedule | the template's breadcrumb shows it; the highlight is not described |

## 3. Labels and their source

Technical keys (group IDs, element IDs, API fields, status values) are not renamed.

| Label | Source |
|---|---|
| "Sign in", "Enter your username", "Enter your password", "Username or password is incorrect", "Logout" | BPA-125 Gherkin (U-D90) |
| "Dashboard", "My tasks", "Tasks pending", "Work Packs", "Time sheets", "Manpower", "Man-hours", "Projects" | BPA-126 (U-D87) |
| "You don't have access to this page" | BPA-126 sc.7 |
| The nine section names Summary, Needs attention, Where is work waiting?, Progress to date, Projects & Work Packs, Tasks pending, Man-hours and progress, Man-hours by phase, Manpower | BPA-127 (U-D84) |
| "No project selected", "Select all projects" | BPA-127 sc.3 |
| Draft / Released / In Progress; "Production Planner · Schedule Work Pack"; "Production Team · Submit Worker assignment"; "You"; "Oldest waiting 55 h" | BPA-128 (U-D85) |
| "No rows match" | BPA-129 sc.5 |
| "Filter (1)", "Clear", "Yard Supervisor" | BPA-130 (U-D93, U-D56) |
| The nine project sections; "Weekly progress" (the Gherkin writes "Weekly Progress" in sc.3: its own inconsistency) | BPA-131 (U-D92) |
| "Mark all as read", "No rows" | BPA-132 (parked) |
| "On approved leave today", "No hours booked in your projects" | BPA-133 (U-D98) |
| "Download PDF", "Page 1 of 1", "No print before the Work Pack is scheduled", "Cutting sheet: Not available" | BPA-134 (U-D99) |
| "Fill 8 h for everyone", "Over 16 h", "16 h · of 16 h max", "Send to Timekeeper" | BPA-135 (U-D95, U-D104); the rules are BPA-50 to 52 |
| "Man-hours and progress", "Man-hours by phase" | BPA-136 (U-D100) |
| "Loading the latest approved data…", "No data for this selection", "Clear all filters", "We couldn't load your data.", "Try again" | BPA-137 (U-D101) |
| User task names ("Confirm Work Pack", "Sign off Work Pack release", "Schedule Work Pack", "Submit Worker assignment", "Book daily hours", "Validate hours", "Approve hours", "Final approval of hours", "Approve completion %", "Approve the week’s progress", "Review actual man-hours by CBS") | the BPMN user task names of Epic 1 and 2 (the prototype's names for some differ slightly: check per story) |
| **No source: ASSUMPTION (questions Q7 and Q18)** | logo "STAR" and the sign-in subtitle "STAR Portal"; the persona subtitle "<Role> · <Department>" and the department names; "Data as of <date time> · Week 47 open"; "Search menu", "No menu item matches"; the top-bar search placeholder; every KPI tile label in Summary (My open tasks, Waiting over 48 h, My projects, Notifications), Progress to date (Completed Work Packs, Average completion, Gained last week, Earned vs Estimated), Man-hours (Estimated, Plan (P6), Earned (locked), Actual (approved)) and Manpower (Workers with hours, Direct Hire, Rental, On leave); the "Business value:" sentences; the card sub-titles; the date-range labels and popover note; "Export" and its toast; every list's search placeholder, column header, pager and empty text; the check texts of the forms (e.g. "Materials reserved", "Drawing approved"); the 503 sign-in text kept from BPA-43 |

## 4. Components

Guide names where known (the guide was not available; the names below come from the brief and are marked "proposal" where a story must own them).

| Component | In `frontend/` today | First created by | Extended by |
|---|---|---|---|
| Design tokens (CSS custom properties in `global.css`) | exist, Barlow and the BPA-23 palette | BPA-125 (proposal for Faraz); the values are in section 10 (decision 27 Nov) | all |
| AppShell | `layouts/MainLayout` (a header grid, no shell) | BPA-125 (proposal) | BPA-126, 127 |
| TopBar | `components/layout/Header` (title, user, group list, "Log out") | BPA-125 (proposal): replaces Header; "Logout" | search and bell (gaps) |
| Sidebar | `components/layout/Sidebar` (Home › Work Packs, Projects and master data, Users and projects) | BPA-126: U-D87 order, role items, highlight | BPA-136 |
| PageHeader | none (an `h2` per page) | BPA-125 (proposal) | BPA-127 (date range, Export) |
| Breadcrumb | none | BPA-126 (proposal: BPA-125 does not need it) | BPA-131, 133 |
| SectionPanel / SectionHeader (icon tile, sentence case) | none | BPA-127 | BPA-128, 130, 136 |
| KPI tile | none | BPA-127 (Summary) | BPA-133, 136 |
| DonutCard | none (no chart library) | BPA-127 (Tasks pending) | none |
| BarListCard (stacked share, butterfly, phase rows) | none | BPA-127 | BPA-136 |
| TrendChartCard | none | not in any persona template: Leadership (BPA-117) | none |
| DataTable (search, sort, pager, "No rows match") | `.sign-table` used inline in four features | BPA-129 | BPA-131, 133, 136 |
| StatusPill / severity pill | none (`lifecycle` steps only) | BPA-127 | BPA-128, 129 |
| Section filter pill | none | BPA-130 | none |
| FilterPanel (drawer) | none | not opened in any persona template: Leadership only | none |
| ProjectPicker | the data hook `useMyProjects` only | BPA-127 (sc.3) | BPA-130 |
| Skeleton, empty and error states | `Spinner` ("Loading…"), `ErrorMessage` | **BPA-137 (the standard)**; BPA-125 uses `ErrorMessage` until then (proposal) | all |
| Book-daily-hours grid | `features/timesheets/BookHours` (a Worker × phase × day grid of the open week) | BPA-135 (extends; Q10) | none |
| Work Pack print preview | `features/workpacks/WorkPackPrints` (a list and the PDF in a frame) | BPA-134 | none |

Proposals for Faraz: tokens, AppShell, TopBar, PageHeader and the state components are owned by BPA-125 and BPA-137 as above; the spec §7 order (125 → 126 + 137 → …) puts them first.

## 5. Role check (prototype against U-D86, U-D87 and U-D104, as the stories quote them)

### Dashboard sections (U-D86, BPA-127): the prototype matches
TMA 1–8 (the prototype: wait, prog, pw, tp, mh, ph); PE 1–6; PP, PPC Team, PM 1–9; PT 1–6 and 9; WO 1–8; Supervisor and Timekeeper 1–3, 6, 7, 9; Production Supervisor 1–4, 6, 7, 9; Cost Controller 1, 2, 4, 6, 7, 8. No mismatch found.

### Menus (U-D87, BPA-126, BPA-136)

| Persona | Menu in the prototype | Against the stories |
|---|---|---|
| Time & Method Analyst | Dashboard, My tasks, Work Packs, Man-hours, Projects | not stated in a story |
| Project Engineer | Dashboard, My tasks, Work Packs, Projects | matches BPA-126 sc.7 and BPA-136 sc.3 (no Time sheets, no Manpower, no Man-hours) |
| Production Planner | Dashboard, My tasks, Work Packs, Manpower, Man-hours, Projects | BPA-136 sc.2 (Manpower for `pl.user`) matches |
| Production Team | Dashboard, My tasks, Work Packs, Time sheets, Manpower, Projects | not stated |
| Workpack Owner | Dashboard, My tasks, Work Packs, Man-hours, Projects | not stated |
| Supervisor | Dashboard, My tasks, Time sheets, Manpower, Man-hours, Projects | matches BPA-126 sc.1 |
| Timekeeper, Production Supervisor | as the Supervisor | not stated |
| PPC Team | all seven | BPA-136 sc.1 (Man-hours for `pc.user`) matches |
| Project Manager | Dashboard, My tasks, Work Packs, Manpower, Man-hours, Projects | not stated |
| Cost Controller | Dashboard, My tasks, Man-hours, Projects | not stated |
| Leadership | Dashboard, Tasks pending, Work Packs, Time sheets, Manpower, Man-hours, Projects | matches BPA-126 sc.2 |

The U-D87 per-role table is not available, so eight rows are **not verifiable**. Q19 asks for it.

### U-D104 (only the Supervisor books and uploads hours): the prototype complies
"Book hours", "Upload hours" and the Book daily hours form appear only for the Supervisor. The Timekeeper (Validate), the Production Supervisor (Approve) and the PPC Team (Final approval) decide hours; PT sees Time sheets read-only; Leadership sees hours and acts on nothing.

### Mismatches
1. Leadership: a persona in the prototype and in three stories, but no group or user in `personas.yaml` (C9).
2. The Gherkin usernames (`tk.user` …) are not the eleven personas (C8).
3. "Supervisor" in the persona files and "Yard Supervisor" for the same role in Leadership and U-D56 (C13).
4. "Drawing revised" is a task for PE and WO in the prototype; in the repo it is a notification (C14).
5. The repo's menu has "Users and projects" (ppc-team only) and "Projects and master data"; the new menu has "Projects", with "Project assignments" as an action for the PPC Team (U-D88).
6. The prototype's PM menu has no Time sheets; BPA-136 says only that `pe.user` lacks Manpower and Man-hours: consistent, not stated for the PM.

## 6. Data: the endpoint each screen uses

Contract paths are in `api/openapi.yaml`. Nothing is designed here: "missing" is a flag.

| Screen | Endpoint | State |
|---|---|---|
| Sign in, Logout, session | `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `GET /api/v1/me` | exists |
| My tasks, task cards, task forms | `GET /api/v1/tasks`, `GET /api/v1/tasks/{taskKey}`, `POST /api/v1/tasks/{taskKey}/complete` (ADR 0004) | exists |
| Summary, Progress to date, Projects & Work Packs, Tasks pending, Man-hours and progress, Man-hours by phase, Manpower; the counts and the oldest wait of Where is work waiting? | `/api/v1/dashboard/*` named in BPA-131 (`openapi-dashboard-BPA-117.yaml`) | **missing** (no such path; the file is not in the repo; Q13) |
| Needs attention for the own role | `GET /api/v1/tasks` (the user's tasks) | exists |
| Work Packs list and the filter by status | `GET /api/v1/workpacks` (status, project, weekEnding, search) | exists |
| Work Pack detail, print preview | `GET /api/v1/workpacks/{n}`, `…/history`, `…/open-tasks`, `…/look-ahead`, `GET /api/v1/workpacks/{n}/print` | exists |
| Projects list and the project picker | `GET /api/v1/me/projects` | partial (no stage, counts or health) |
| One-page project (BPA-131) | dashboard | **missing** |
| Time sheets list for each role | `GET /api/v1/digital-timesheets` (the Supervisor's board), `GET /api/v1/hours-submissions/{id}` | partial (not a list of every status for every role) |
| Book daily hours | `GET` and `POST /api/v1/digital-timesheets/{id}/lines`, `PUT` and `DELETE /api/v1/digital-timesheet-lines/{lineId}`, `POST …/amendments`, `POST /api/v1/timesheet-files` | exists (BPA-50 to 52) |
| Worker detail and Manpower | none | **missing** |
| Man-hours table | `GET /api/v1/progress-periods/{wk}/earned-manhours` | partial (per activity, not per Work Pack) |
| Weekly progress, CBS alerts | `/api/v1/progress-periods/{wk}/workpacks`, `…/cbs-actuals`, `POST …/reopen`, `GET /api/v1/me/variance-alerts` | exists |
| Hour Journal | `GET /api/v1/hour-journal-headers`, `/hour-journal-lines` | exists (not used by the portal today) |
| FMS files | `GET /api/v1/fms-hours-exports/{id}/file`, `POST /api/v1/completion-reports` | exists |
| Project assignments | `GET /api/v1/users`, `GET` and `POST` and `DELETE …/users/{u}/projects` | exists |
| Notifications | `GET /api/v1/me/notifications`; mark as read | read exists, not used today; mark as read **missing** (BPA-132 parked) |

### Where the backend must answer 403 (hiding in the UI is not security, ADR 0004)
- Every read behind a menu item a role does not have: BPA-126 sc.7 (Time sheets for `pe.user`), BPA-136 sc.3 (Manpower and Man-hours for `pe.user`).
- Every dashboard read, limited by role and by the user's projects (`ProjectScope`, BPA-77 and BPA-79).
- Worker detail limited to the user's projects (BPA-133 sc.4).
- The print for a group outside `PRINT_GROUPS`.
- The Leadership reads for any group but Leadership.

## 7. Delivery order (spec §7, **not confirmed**: spec.md is not in the repo)
125 → 126 + 137 → 127 / 128 / 129 → 130 → 133–136 → 131. BPA-131 belongs to epic BPA-117, whose endpoints must exist first; BPA-132 is parked.

| Shared piece | Proposed owner (for Faraz) |
|---|---|
| Tokens, AppShell, TopBar, PageHeader | BPA-125 |
| Sidebar, Breadcrumb | BPA-126 |
| Skeleton, empty and error states | BPA-137 (BPA-125 and 126 use `ErrorMessage` and `Spinner` until then) |
| SectionPanel, KPI tile, StatusPill, ProjectPicker, DonutCard, BarListCard | BPA-127 |
| DataTable | BPA-129 |
| Section filter pill | BPA-130 |

## 8. Tests per story
- One test per Gherkin scenario, named `BPA<n>_<camelCaseScenario>`, written first and shown red (ADR 0014; one React test per screen condition).
- A component test for each state of each new component (idle, loading, empty, error, and where relevant disabled, over limit, leave).
- A visual check against the template at 1440 px and 390 px before each PR. **Decision (Ahsan, 27 Nov): Playwright is added after the project is complete, not now.** Until then the check is a person's, side by side, and each PR says so (a human gap, Q15 closed on those terms).
- The backend's 403 is tested in the owning story's API test, not in React.

## 9. Open conflicts and questions

### Conflicts
| # | Topic | Sources | Resolution |
|---|---|---|---|
| C1 | Frontend path | the guide `portal/src/…` against ADR 0003 `frontend/` | `frontend/` |
| C2 | Menu order and labels | the guide §4.3 against U-D87 (BPA-126) | U-D87 |
| C3 | Project detail | the guide's tabs against U-D92 (BPA-131) | U-D92, one page with a side menu |
| C4 | Section heading | the guide (14/600 uppercase) against U-D84 (18 px sentence case, 36 px icon tile) | U-D84 |
| C5 | Sign-in | the prototype (any password, test accounts) against U-D90 and ADR 0005 | ADR 0005: a real password, no account list |
| C6 | Font | the guide's Inter, the prototype's Manrope, Nunito Sans and Telegraf, the repo's Barlow | **Decided by Ahsan 27 Nov: Manrope for headings and Nunito Sans for text, nothing else (section 10).** Telegraf and Avenir Next are not used (no licence question). Khitaba is asked to confirm (Q1) |
| C7 | Status colours | the guide §2 (#B42318 / #8A6200 / #1F6B42) against U-D28 (#D64545 / #E0A100 / #2E8B57); the prototype uses both families (text and pill colours #B42318 / #8A6200 / #1F6B42, fills and dots #D64545 / #B88A00 / #2E9A5E) | **Decided by Ahsan 27 Nov: the prototype's colours (section 10).** Khitaba confirms against U-D28 (Q2) |
| C8 | Test usernames | the Gherkin's `tk.user` … against docs/04 and `personas.yaml` (eleven, username = group) | map in tests (Q3) |
| C9 | Leadership | BPA-126, BPA-131 and the prototype against `personas.yaml`; BPA-131's parent is BPA-117 | Q4 |
| C10 | Where the design docs live | Jira (`claude/`, SharePoint) against ADR 0015 (the repo is the master) | Q5 |
| C11 | Styling rule | ADR 0017 and `frontend/CLAUDE.md` (Barlow, BPA-23 palette, "styling never changes a label") against the new design | Q6: amend ADR 0017 |
| C12 | Sign-in texts | the repo (BPA-43: "Log in", "Log out", "Wrong username or password") against BPA-125 | Jira wins; the existing tests change |
| C13 | The prototype's own drift | "Supervisor" and "Yard Supervisor"; `ppc.user` in texts and `pc.user` as the login; "Digital Time sheets", "My Work Packs" and "Assignments" defined but not shown | Q8 |
| C14 | Drawing revised | a task in the prototype against a notification in BPA-16 | Q9 |
| C15 | Book hours | BPA-135 (one date, one box per Worker and phase) against BPA-50's week grid | Q10 |
| C16 | Print preview | BPA-134 (an A4 preview) against ADR 0004 and 0012 (the browser's PDF viewer) | Q11 |
| C17 | Delivery order | the brief's spec §7 against the missing spec.md | Q12 |

### Questions
Q1 Font: the prototype's (decided 27 Nov by Ahsan); Khitaba to confirm. Q2 Status colours: the prototype's (decided 27 Nov by Ahsan); Khitaba to confirm against U-D28. Q3 Map the Gherkin usernames to the eleven personas in tests (tm, pe, pl, pt, wo, sv, tk, ps, pc, pm, cc → time-method-analyst, project-engineer, production-planner, production-team, workpack-owner, supervisor, timekeeper, production-supervisor, ppc-team, project-manager, cost-controller), or change the Gherkin. Q4 Who adds a Leadership group. Q5 Who copies the design docs into the repo. Q6 The ADR 0017 amendment. Q7 Keep the unsourced labels as ASSUMPTION. Q8 Supervisor against Yard Supervisor; `ppc.user`. Q9 Drawing revised. Q10 BPA-135 beside or instead of BPA-50's grid. Q11 Print preview as HTML or as the PDF. Q12 Confirm the delivery order. Q13 Which epic builds the `/api/v1/dashboard/*` reads. Q14 `/` becomes the Dashboard and the task list moves to My tasks. Q15 Playwright or a person's visual check. Q16 Template folder: move `ui-design/` to `docs/ui-prototype/` (recommended; ADR 0003 puts design documents in `docs/`) or keep it and add it to ADR 0003 and CLAUDE.md; nothing moves until approved. Q17 A follow-up Task for the 401 redirect (ADR 0005). Q18 The department names under the persona subtitles. Q19 The U-D87 per-role menu table. Q20 **Closed (Ahsan, 27 Nov):** Manrope and Nunito Sans only; Telegraf and Avenir Next are not used. Q21 **Closed (Ahsan, 27 Nov): dark mode is wanted**, the same as the prototype; it needs a Jira story (section 12, to be created only on confirmation). Q22 **Closed (Ahsan, 27 Nov): the top-bar search and its "⌘ K" hint are left out** until a story builds the search (my reading of "leave the second"; to be corrected if meant otherwise). Q23 **Closed (Ahsan, 27 Nov): white by default; dark mode only after a click on the icon; nothing is remembered** (as the prototype). Q24 **Open:** design work with no Jira story (the restyle of the existing screens, the shared components, lists, filters, dark mode) has no key for the branch name, commit messages and the plan header (CLAUDE.md wants "<KEY>-…"). Proposal: BPA-125 carries the shell, tokens, sign-in and dark mode; each later piece rides with the story that shows it (BPA-126 the sidebar and breadcrumb, BPA-127 the dashboard sections …); a restyle with no matching story uses the epic key in the commit message ("BPA-124: …") on a branch named after the first story that needs it. Faraz or Ahsan decides. Q15 **Closed (Ahsan, 27 Nov): Playwright after the project is complete**; until then a person's side-by-side check per PR.

### Human-gap log
- The UI decisions, intent, spec, component guide, brand and naming rule were not in the repo.
- The templates sit in `ui-design/`, not `ui-templates/`, and are untracked.
- Nobody has compared the export with the live prototype.
- No screenshots: a person compares each build with the templates.
- The Gherkin usernames and the missing Leadership group need a PO decision.
- The prototype export has defects that must not be copied: the Projects & Work Packs bars open a screen that does not exist (`mod:wps`), the Notifications tile does nothing, and the Filters drawer has no button that opens it.

## 10. Design tokens taken from `ui-design/` (decision of Ahsan, 27 Nov 2026)

How these were read: a throwaway script (outside the repo, not committed) tallied the inline styles of `03-Personas-all.html` and the `<style>` blocks of 01, 02 and 03. **The prototype defines no CSS custom properties: every value is a hard-coded literal.** The token names below are therefore **proposed by us (ASSUMPTION)**, named by role, and the counts are how often the value appears in the persona app. Dark mode is not a token set (see Q21). Values are meant for `frontend/src/styles/global.css`; BPA-125 creates them.

### Colour
| Proposed token | Value | Used for (as in the prototype) | Count |
|---|---|---|---|
| `--surface` | #FFFFFF | page and card background, sticky action bar | 100 |
| `--surface-subtle` | #F9FAFB | table header, a leave row | 20 |
| `--surface-muted` | #F2F4F7 | hairlines inside cards, chips | 19 |
| `--text-strong` | #101828 | headings, page title, sign-in title | 54 |
| `--text` | #1D2939 | body text, link hover | 48 |
| `--text-muted` | #475467 | secondary text, sub-titles | 90 |
| `--text-faint` | #98A2B3 | hints, placeholders, inactive | 17 |
| `--border` | #E4E7EC | card and table borders | 54 |
| `--border-input` | #D0D5DD | inputs, secondary buttons, dashed upload box | 38 |
| `--primary` | #289CC1 | the `pri` button of the task forms (background), page-header icons, "In Progress", the keyboard focus ring (2 px, 2 px offset) | 28 |
| `--primary-dark` | #1B7A99 | **the Sign in, Done and Export buttons (background)**, links, secondary-button text, section icon colour, "Released" pill text | 48 |
| `--primary-tint` | #E6F4F9 | the 36 px section icon tile; selected states | 16 |
| `--primary-tint-2` | #E9F5FA | selected card or row | 11 |
| `--status-draft` | #B88A00 (text pill #B54708) | Work Pack "Draft", warning dots, warn button border | 10 |
| `--status-released` | #4E79C7 (text pill #1B7A99) | Work Pack "Released", "Plan (P6)" dot | 4 |
| `--status-in-progress` | #289CC1 (text pill #1F4E79) | Work Pack "In Progress" | – |
| `--status-completed` | #2E9A5E (text pill #1F6B42) | "Completed", OK check dot, "Open" | 6 / 11 |
| `--danger` | #D64545 (text #B42318) | error and over-limit: red row marker, bad button border, ✕ check | 7 / 37 |
| `--danger-tint` | #FDECEC (border #F2B8B5, soft #FEF6F6) | overdue pill, error box | 5 / 1 / 2 |
| `--warning-text` | #B54708 on #FEF3E2 | "Not scheduled · no print yet", warn button text | 10 / 1 |
| `--warning-pill` | #8A6200 on #FFF4D6 | the "Waiting" severity pill | 1 |
| `--success-text` | #1F6B42 on #E8F5EE | "Business value:" line, success box | 11 / 1 |
| `--accent-purple` | #8E6CC9 (text #6941C6 on #F4F0FB) | Rental bars, "Workers with hours" dot | 6 |
| `--text-grey` | #7A868C | rare | 2 |

Pill colours by status text (the prototype's `pill` map): Draft #B54708, Released #1B7A99, In Progress #1F4E79, Completed #1F6B42, Open #1F6B42, Closed #475467, Locked #475467. Work Pack status dots (its `WSC` map): Draft #B88A00, Released #4E79C7, In Progress #289CC1, Completed #2E9A5E. Buttons (its `pri / sec / warn / bad` map as background, text, border): primary #289CC1 / #FFFFFF / #289CC1; secondary #FFFFFF / #1B7A99 / #D0D5DD; warn #FFFFFF / #B54708 / #B88A00; bad #FFFFFF / #B42318 / #D64545.

### Typography
- **Headings** (`h1`, `h2`, `h3`, `.hfont`): **`Manrope, sans-serif`** (decision of Ahsan, 27 Nov). The prototype's stack is `Telegraf, Manrope, 'Avenir Next', sans-serif`; Telegraf and Avenir Next are left out.
- **Body:** **`'Nunito Sans', 'Segoe UI', system-ui, sans-serif`** (the prototype's stack is `'Avenir Next', Avenir, 'Nunito Sans', 'Segoe UI', system-ui, sans-serif`; Avenir Next and Avenir are left out).
- **Weights loaded** (as the export does): Manrope 500, 600, 700, 800 and Nunito Sans 400, 500, 600, 700. The build self-hosts both with `@fontsource/manrope` and `@fontsource/nunito-sans` (ADR 0017: no external requests, which also differs from the export's Google Fonts link).
- **Page title** (`h1`): 24 px, 600, letter-spacing -.01em, #101828; its sub-title 15 px. **Sign-in:** logo "STAR" 26 px, 800, letter-spacing .02em; title 22 px, 600; sub-title 14 px #475467.
- **Section heading** (`h2`, U-D84): 18 px, 600, line-height 1.3, sentence case (`text-transform: none`, letter-spacing 0), #101828, with a 36 × 36 px icon tile (radius 10 px, background #E6F4F9, icon #1B7A99, 20 px icon).
- **Sizes in use:** 10, 11, 12, 13 (most common, 71×), 14 (42×), 15, 16, 18, 20, 22, 24, 26, 28 px. **Weights:** 600 (102×), 700, 500, 400, 800. Body line-height 1.3 and 1.55. Numbers use `font-variant-numeric: tabular-nums` (`.num`). `-webkit-font-smoothing: antialiased`.
- Small texts: a card sub-title 12 px; the severity pill 13 px, weight 500; the "Business value:" line 12 px #1F6B42.

### Shape, space and elevation
- **Radius:** 8 px (35×), 12 px (32×; table boxes, cards), 10 px (31×; icon tile, inputs), 16 px (15×; large cards, drawers), 999 px (14×; pills), 3 px (bars), 6, 4 and 5 px, 50% (round dots and avatars).
- **Shadow:** card `0 1px 3px rgba(31,42,48,.06)` (20×); popover `0 6px 20px rgba(31,42,48,.10)`; drawer `0 12px 32px rgba(31,42,48,.18)`; small `0 2px 8px rgba(31,42,48,.07)`.
- **Card padding:** 16 px 18 px (25×); 14 px 16 px; 18 px 20 px; 22 px 24 px (large). **Button and chip padding:** 6 px 8 px, 0 10 / 12 / 14 / 16 px, 2 px 10 px (pills).
- **Gap:** 12 px (45×), 8 px (34×), 10 px, 6 px, 4 px, 14 px, 16 px.
- **Border:** 1 px solid #E4E7EC (cards, 37×), 1 px solid #D0D5DD (inputs, 20×), 1 px solid #98A2B3 (a stronger border, 15×; which controls use it was not checked), 1 px dashed #D0D5DD (upload box), 2 px solid #101828 (once; which element was not checked).
- **Control heights:** 36 px (18×) and 40 px (22×; inputs and buttons), 44 px (touch targets), 52 px; avatar 36 to 40 px.
- **Layout widths** (the values appear in the markup; what each applies to was not checked, ASSUMPTION): 1360 px, probably the content maximum; 360 to 440 px, popovers; breakpoints 640 px (phone: sidebar becomes a drawer, one column, sticky action bar), 700 px (section-filter popover becomes a bottom sheet), 760 px (crew grid), 1100 px (cards go to two columns).
- **Focus:** `outline: 2px solid #289CC1; outline-offset: 2px` on buttons, links, selects, inputs and textareas. This replaces the repo's marking-paint yellow focus ring (ADR 0017 amendment, Q6).
- **Tables:** `.tbox` is a scroll box with a 1 px #E4E7EC border and 12 px radius; the header row is sticky on #F9FAFB.

### What is not taken
Figures, names, project and Work Pack numbers, dates and every number on the prototype are demo data. The export's behaviour defects (section 9) are not copied. The prototype's inline-style approach and its renderer are not used: the build uses plain CSS with the tokens above (ADR 0017, `frontend/CLAUDE.md`: no UI library). The values are the same; the mechanism is not.

## 11. Exact component specs for BPA-125 (read from `ui-design/`, 27 Nov 2026)

Source: the page source embedded in `ui-design/01-Sign-in.html` and `03-Personas-all.html` (decoded by the throwaway script; the markup and the style strings below are copied as written). All icons are 24 × 24 viewBox line icons with `fill="none"` and `stroke="currentColor"` or an explicit stroke; stroke-width 1.8 for navigation and header icons, 2 for top-bar and form icons. The build uses the same SVG paths.

### 11.1 Sign in (`01-Sign-in.html`)
- **Page:** a flex box, `min-height: 100vh`, centred both ways, background #F9FAFB, padding 24 px, text colour #1D2939, body font stack.
- **Card (`main`):** width 100%, `max-width: 420px`, background #FFFFFF, `border: 1px solid #E4E7EC`, `border-radius: 16px`, `box-shadow: 0 6px 20px rgba(31,42,48,.08)`, `padding: 32px 28px`, a column with `gap: 18px`.
- **Heading block** (column, gap 6 px): logo text "STAR" (heading font, 26 px, 800, letter-spacing .02em, #101828); `h1` "Sign in" (22 px, 600, #101828, margin 0); a line "STAR Portal" (14 px, #475467, margin 0).
- **Each field** is a `label`: a column, gap 6 px, 14 px, 600, #1D2939, the label text above the input. **Input:** height 44 px, `border: 1px solid #D0D5DD` (**#B42318 when invalid**, with `aria-invalid="true"`), radius 8 px, padding `0 12px`, 15 px, weight 400, text #101828, background #FFFFFF. Username has `autocomplete="username"`, Password `autocomplete="current-password"`; Enter submits.
- **Error line** (`role="alert"`): flex, gap 8 px, centred, background #FDECEC, text #B42318, radius 8 px, padding `10px 12px`, 14 px, 600, with a 16 px icon (a circle and an exclamation mark: `<circle cx="12" cy="12" r="9"/><path d="M12 8v4M12 16h.01"/>`, stroke 2) before the text.
- **Button "Sign in":** height 44 px, no border, radius 10 px, background **#1B7A99**, text #FFFFFF, 15 px, 600, `font: inherit`.
- **Below the button (prototype only):** a `Test accounts` select under a top border. Not built (11.4).

### 11.2 Top bar (`header`, shared by every screen except Sign in)
- A flex row, `align-items: center`, gap 12 px, padding `14px 32px`, `border-bottom: 1px solid #E4E7EC`. At 640 px or less: padding `10px 12px`, gap 8 px.
- **Menu button** (phone only, `display: none` above 640 px): 40 × 40, radius 10 px, `border: 1px solid #E4E7EC`, background #FFFFFF, an 18 px "three lines" icon (`M4 6h16M4 12h16M4 18h16`, stroke #1D2939, 2). `aria-label="Open menu"`.
- **Search** (hidden at 640 px or less): a `label` with `flex: 0 1 460px; min-width: 240px`, flex, gap 10 px, `border: 1px solid #E4E7EC`, radius 10 px, padding `0 12px`, height 44 px; a 16 px search icon (`<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>`, stroke #475467, 2); a visually hidden label "Search"; the input (no border, no outline, 14 px, transparent, placeholder "Search Work Pack No., Badge No., Time sheet"); a hint "⌘ K" (12 px, #475467, `border: 1px solid #E4E7EC`, radius 6 px, padding `2px 6px`).
- **Icon buttons** (a flex group, gap 8 px), each 40 × 40, radius 10 px, no border, cursor pointer: "Dark mode" (transparent, `aria-pressed`, icon `M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z`), "Notifications" (#FFFFFF, icon `M6 8a6 6 0 1112 0c0 7 3 8 3 8H3s3-1 3-8M10 20a2 2 0 004 0`, with an 8 px round #B88A00 dot at `top: 9px; right: 10px` when there is something new), **"Logout"** (#FFFFFF, hidden at 640 px or less like the search, icon `M9 21H5V3h4M16 17l5-5-5-5M21 12H9`). All icons 18 px, stroke #1D2939, 2. Each has an `aria-label` and a `title`.
- The "Logout" button signs out and shows Sign in with an empty password (BPA-125 sc.5).

### 11.3 Page header (`PageHeader`)
- A column, gap 4 px, then a row (`flex-wrap`, gap 14 px, centred): a **52 × 52 icon tile** (radius 12 px, background #E9F5FA, a 26 px icon, stroke #289CC1, 1.8); the title block (`flex: 1 1 260px; min-width: 0`): `h1` (margin 0, 24 px, 600, letter-spacing -.01em, #101828), under it a sub-title (margin-top 4 px, 15 px, weight 500, #1D2939), and for list and home screens the "Data as of …" line (margin-top 4 px, 12 px, #475467, a 16 px clock icon); then the actions (flex, gap 10 px, wrap, `position: relative`): the date-range button (min-height 40 px, padding `0 16px`, `border: 1px solid #D0D5DD`, radius 10 px, background #FFFFFF, 14 px, #1D2939, gap 10 px, an 18 px calendar icon `M8 2v4M16 2v4M3 9h18M5 4h14v17H5z`), and the **Export** button (40 × 40, radius 10 px, background **#1B7A99**, an 18 px download icon `M12 3v12M7 10l5 5 5-5M4 21h16`, stroke #FFFFFF, 2).
- **Main area:** `max-width: 1360px`, centred, padding `24px 32px 56px` (phone: `14px 12px 24px`), a column with gap 22 px.
- **Date popover:** `position: absolute; top: 52px; right: 0; z-index: 20; width: 380px; max-width: calc(100vw - 24px)`, background #FFFFFF, `border: 1px solid #E4E7EC`, radius 12 px, `box-shadow: 0 6px 20px rgba(31,42,48,.10)`, padding 14 px, a column with gap 10 px. Date inputs: height 40 px, `border: 1px solid #D0D5DD`, radius 8 px, 13 px. Error line 12 px, 600, #B42318. "Done": min-height 40 px, radius 8 px, background #1B7A99, text #FFFFFF, 600.
- **Dashboard home** (the BPA-125 landing): h1 "Dashboard", sub-title "<Role> · <Department>"; the sections under it are BPA-127's.

### 11.4 Sidebar (used from BPA-126; listed here because the shell is built in BPA-125)
- `nav aria-label="Main menu"`: `position: sticky; top: 0; height: 100vh; overflow-y: auto; flex: 0 0 200px; max-width: 200px`, `border-right: 1px solid #E4E7EC`, padding `20px 10px`, a column with gap 4 px, background #FFFFFF; hidden at 640 px or less (a drawer opens from the menu button).
- Logo "STAR": heading font, 24 px, 700, letter-spacing .02em, #101828, padding `0 12px 16px`.
- Menu search: a `label` with margin `0 4px 12px`, padding `0 10px`, height 38 px, `border: 1px solid #D0D5DD`, radius 8 px, a 16 px search icon, an input (no border, 13 px, "Search menu"); "No menu item matches" (13 px, #475467, padding `4px 12px`).
- Menu item (a `button`, `aria-current="page"` when selected): flex, centred, gap 14 px, width 100%, min-height 44 px, padding `0 14px`, radius 10 px, no border, 14 px, weight 500, left-aligned; an 18 px icon (stroke 1.8, `currentColor`); **selected: background #E9F5FA, text #101828, weight 500; otherwise background #FFFFFF, text #1D2939**. A count badge at the right (`margin-left: auto`, 12 px, 600, padding `2px 8px`, radius 999 px, background #FDECEC, text #B42318).
- Footer (`margin-top: auto`, padding `16px 8px 0`, flex, gap 12 px): a 40 px round avatar (background #E9F5FA, text #1B7A99, 700, 14 px) with the initials, and the persona name (15 px).

### 11.5 Allowed differences from the templates (BPA-125)
These are required by the Jira stories and the ADRs; every other difference is a defect.
1. **No "Test accounts" select, and a real password check** (U-D90, ADR 0005; BPA-125 Description). The Sign in card ends after the button.
2. **The user is named by the session** (`/api/v1/me`), not by the prototype's persona list; there is no persona switcher and no "Switch to <Role>" toast (prototype aids).
3. **The Leadership user opens the Leadership dashboard in an iframe in the prototype**; in the build it is a normal screen (BPA-126 and BPA-117).
4. **All numbers, names and dates are the user's real data from the backend**, never the demo values.
5. **The prototype's defects are not copied** (section 9).
6. **Anything the Jira Gherkin words differently from the prototype follows Jira:** BPA-125 uses "Enter your username", "Enter your password" and "Username or password is incorrect", which are also the prototype's texts, so none differs today.
7. **Dark mode (decided: yes, 27 Nov) has no Jira story and none will be written (decision of Ahsan, 27 Nov).** It is built in BPA-125's top bar (section 12). **The top-bar search is left out for now** (Q22 closed by Ahsan, 27 Nov: "leave" it, read as left out; no story builds the search): a visible difference from the template, until a story builds it.
8. **The "Notifications" bell is left out too** (BPA-132 is parked and nothing opens: a dead button is worse than none; added 27 Nov while building BPA-125).
9. **"Logout" stays visible on a phone.** The template hides the top-bar Logout at 640 px or less and its drawer has none, so a phone user could not sign out; BPA-125 scenario 5 needs it.
10. **The sidebar's items are today's** (Home, Work Packs, Projects and master data, Users and projects), restyled as the template's menu item (11.4); BPA-126 replaces them with the U-D87 menu. The template's menu search is not built. The logo "STAR" and the persona (initials and name) are in the sidebar column, as in the template, not in the top bar.
11. **The Dashboard sub-title** is built as the template writes it, "<Role> · <Department> · <n> assigned projects" (n from `GET /api/v1/me/projects`; omitted while that is not answered), including the template's plural "1 assigned projects".
The rest of section 11.2 is built as written.

## 12. Dark mode: the template's behaviour (built in BPA-125; no Jira story)

**Decision (Ahsan, 27 Nov): the front-end design gets no user stories of its own; it is implemented from the templates.** The story below is therefore **withdrawn as a Jira proposal**: dark mode is built in BPA-125's top bar and its scenarios become tests named `BPA124_…` (no scenario of BPA-125 covers them). Section 12.2 is kept only as the acceptance list.

### 12.1 What the prototype does (`03-Personas-all.html`, `02-Leadership-dashboard.html`)
- The top-bar button "Dark mode" (40 × 40, radius 10 px, transparent, an 18 px moon icon `M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z`, stroke #1D2939, 2) has `aria-pressed="true"` or `"false"`, `aria-label="Dark mode"` and `title="Dark mode"`.
- Pressing it flips one flag; the app's root element then gets the class `dark`, and the CSS is `.dark { filter: invert(.92) hue-rotate(180deg); background: #FFFFFF }`. Nothing else is restyled: dark mode is that one filter on the whole app root, so the whole page, icons and charts included, are inverted and rotated.
- The flag is the component's state: it is **not stored** and is gone after a reload; the build does the same (Q23 closed by Ahsan, 27 Nov: white by default, dark only after a click on the icon). The Sign in screen has no root class and is never dark. The Leadership app (an iframe in the prototype) keeps its own flag.
- A known property of the filter approach, to check on the build: a CSS `filter` on an ancestor makes `position: fixed` descendants (the phone drawer, the bottom-sheet section filter, popovers positioned with `fixed`) position against that ancestor instead of the screen. The prototype lives with this; the build is checked at 390 px in dark mode.

### 12.2 Proposed story (draft for Khitaba and Faraz; to be created only on a yes)
- **Summary:** "Any user can switch the portal to dark mode"
- **As a** portal user **I want** a Dark mode button in the top bar **So that** I can read the portal in dark colours.
- **Labels:** claude-generated, assumption, forms. **Epic:** BPA-124. **Estimate:** 1 (ASSUMPTION). **Order:** after BPA-125 (it needs the top bar); it can run beside BPA-126.
- **Acceptance criteria (draft):**
  1. *Switch on:* Given I am signed in and the portal is in its normal colours, When I press "Dark mode", Then the portal shows in dark colours and the "Dark mode" button is pressed.
  2. *Switch off:* Given dark mode is on, When I press "Dark mode" again, Then the portal shows in its normal colours and the button is not pressed.
  3. *Sign in stays light:* Given dark mode is on, When I log out, Then the Sign in screen shows in its normal colours.
  4. *Phone width:* Given dark mode is on, When I open the menu on a 390 px screen, Then the menu drawer opens over the whole screen in dark colours. (Covers the `fixed` property in 12.1.)
  5. *Light by default:* Given I sign in, Then the portal shows in its normal (white) colours and the "Dark mode" button is not pressed; and Given I pressed "Dark mode" and then reload the page, Then the portal shows in its normal colours again (Q23 closed: light by default, dark only on a click, nothing remembered).
- **BPMN Reference:** none (UI only). **SOR Notes:** none; the choice is not stored anywhere (Q23 closed). **Source Reference:** the top bar of `ui-design/03-Personas-all.html` and `02-Leadership-dashboard.html`; decision of Ahsan, 27 Nov 2026; ASSUMPTION: no FRD or U-D names it.
- **Tests when it starts:** one `BPA<n>_…` test per scenario above; a component test for the button states.
