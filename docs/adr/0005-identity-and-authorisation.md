# 0005 Identity and authorisation

- Status: Accepted
- Date: 2026-10-27 (S5, S6, G9, S22, L2, L4, L9, L18; 09 LOCKED); 2026-10-28 (S25-S29); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer) for the 27 Oct rows; Faraz with Ahsan/Usama for
  S25-S27; Faraz for S28 and S29
- Informed: Khitaba (BA)
- Amended: 2026-11-13 (BPA-48; E2-D1, Khitaba for the PO, 10 and 11 Nov 2026): eleven personas; project scope returns as `user_project`.
  See the last item under Consequences.
- Amended: 2026-11-02 (PO decision 2 Nov 2026, BPA-44; approved by Ahsan on behalf of Faraz): seven personas, access by
  Camunda group only; `user_role_scope` removed. See the last item under Consequences.
- 09 rows: S5, S6, G9, S22, S25, S26, S27, S28, S29; L2, L4, L9, L18

## Context

Each persona uses its own React portal (S2, ADR 0004), and Camunda must record the real user who completes
a task. Some scope (department, project) cannot be expressed by Camunda groups. The first proposals put
login and roles in the POC database (06 D19; 07 §9 `user_role`) and planned basic auth with demo users
and candidate groups created by hand in Admin (08 Gate 1, proposal for ADR 0006). Seeding users from both
the reference seed and a startup job would have given two writers (L2).

## Options considered

1. **Roles in the POC database** (`user_role`, 07 §9; D19 "login and roles = POC database"). Rejected:
   it would give roles two SORs (S6; 09 §3).
2. **Keycloak, or OIDC against STAR's Entra ID, in the POC.** Not in the POC; kept for production (S5).
3. **Camunda basic auth behind a backend session, Camunda as the source of truth for users and groups**
   (S5, S6). Chosen.
4. **Seed users in `R__seed_reference_data.sql` as well as by the startup job.** Rejected (L2): the
   startup job is the only writer.
5. **Database-backed sessions.** Only if a showcase needs restarts without logging users out (S27).
6. **Restricting the mock D365 endpoint** (session, group or API key). Not restricted (S28).
7. **Changing the default Camunda and database passwords now.** Kept as they are for now (S29).

## Decision

**Login (S5, L4).** Camunda basic auth. The React login form posts once to `POST /api/v1/auth/login`;
the backend verifies the user against Camunda, reads the user's groups once, and issues an HttpOnly session
cookie. React never stores the password or sends a basic-auth header later. React shows `GET /api/v1/me`
(username, groups); `POST /api/v1/auth/logout` ends the session. HTTPS anywhere other than localhost.

**Roles (S6; amended 2 Nov 2026).** Camunda is the source of truth for users, group membership and task
state. One Camunda group per role, kebab-case: `time-method-analyst`, `project-engineer`,
`production-planner`, `workpack-owner`, `ppc-team`, `supervisor`, `production-team`. Access is by
Camunda group only: the POC holds no department or project scope (PO decision 2 Nov 2026, BPA-44; the
`user_role_scope` table of the first design was removed). `user_role.role` is removed. The **backend
enforces** the group (403); React only hides what the user cannot use. Groups are read at login and kept in the session.

**Candidate groups (G9).** `time-method-analyst` on UserTask_CreateWorkPack and UserTask_ConfirmWorkPack;
`project-engineer` on UserTask_SignOffRelease; `production-planner` on UserTask_ScheduleWorkPack. The other
groups exist for portals only.

**Personas (S22, L2, L9; amended 2 Nov 2026).** One `personas.yaml` lists exactly **seven** test
users, one per group, each with a username equal to its group ID; no department or project scope and
no second users (PO decision 2 Nov 2026, BPA-44). The password comes from `.env` (`DEMO_PASSWORD`,
placeholder in `.env.example`), never from the yaml. An idempotent startup job creates the Camunda
users and groups through the REST API and upserts `app_user`; it is the **only** writer of them (L2)
and runs only with profile `local` or `showcase`. No live myPeople sync.

**Service identity (S25).** One technical Camunda user `bpa-service`, created by the S22 job, password from
`.env` (`BPA_SERVICE_PASSWORD`, placeholder in `.env.example`). Used by the backend for the REST API
(search, assign, complete tasks; publish messages) and by the job workers. Least privilege: only the
permissions those calls need, including assigning tasks to other users. Connectors keep their own
credentials.

**CSRF (S26).** Spring Security cookie-based CSRF token (`XSRF-TOKEN` cookie read by React, sent back as
`X-XSRF-TOKEN`) on every state-changing call. Session cookie `HttpOnly`, `SameSite=Lax`, `Secure` outside
localhost. Built into the walking skeleton.

**Sessions (S27).** In-memory sessions; 30-minute idle timeout (ASSUMPTION, configurable
`server.servlet.session.timeout`); on expiry the API returns 401 and React sends the user to login. A role
change takes effect at the next login (accepted for the POC).

**Mock D365 endpoint (S28).** `POST /api/v1/drawing-revisions` is not restricted (no session, group or API
key); OpenAPI marks it `security: []`. It stands in for a D365 Document Control event and is called by
tests and in the showcase.

**Default credentials (S29).** Keep `demo/demo` for Operate/Admin and the database users and passwords in
`.env.example`. No runtime DDL restriction on the app's database user.

## Consequences

- `DEMO_PASSWORD` and `BPA_SERVICE_PASSWORD` are in `.env.example` as placeholders, with `CAMUNDA_ADMIN_USER` and
  `CAMUNDA_ADMIN_PASSWORD` for the S22 job (BPA-43).
- Walking skeleton (BPA-43): the S22 job runs before the Camunda client starts, so the app deploys the models and runs its workers
  as `bpa-service`. To deploy, `bpa-service` also holds RESOURCE `CREATE` on `*` besides the BPA-25 set (**ASSUMPTION**; shown to
  work in `Bpa25CamundaChecksTest`, not proven minimal). Personas get no Camunda authorizations: the backend acts for them (S14).

- The walking skeleton includes the React login (L4): login, `GET /api/v1/me`, logout, and one denied call
  returning 403 to prove backend enforcement; users and groups come from `personas.yaml` via the S22 job.
- BPA-25 is reduced to confirming the 8.10.1 API calls (S15), plus a fifth check for `bpa-service` (S25).
  Claude writes a `.http` file and a short shell runner against the compose stack; the JUnit version comes
  with the walking skeleton (L18). Running the checks is a human step (L19).
- Every "done by" field stores the Camunda username (06 D63, which applies S6).
- OIDC (Keycloak or Entra ID) replaces basic auth for production (S5).
- **Revisit triggers:** S28 if the POC is exposed beyond the team's machines or a showcase environment;
  S29 and S24 when a shared or showcase environment is set up.
- 03-sor-matrix records Camunda as the SOR for login and group membership (09 §4 item 6, done).
- **Amended 29 Oct 2026 (A11, Khitaba, approved by Faraz; recorded under BPA-39):** `user_role_scope` keeps its rows in
  the POC database, but each column carries the SOR of the entity it identifies (`docs/data-model.md` §1.3; Jira BPA-15, BPA-16 SOR Notes):
  username and group_id Camunda identity, department_ref D365 F&O, project_ref D365 F&O Project Management.
  The "SOR POC database" wording under Decision refers to the row. *Superseded 2 Nov 2026 by the next item.*
- **Amended 2 Nov 2026 (PO decision 2 Nov 2026, BPA-44; approved by Ahsan on behalf of Faraz; delivered in PR #19):**
  the POC uses only the seven personas, one login each, username equal to the Camunda group ID. Access is
  by group only: department and project scope are out of the POC (production would add them, for example
  through OIDC claims). `user_role_scope` was dropped from V1 (no shared or showcase environment existed,
  09 L5) and the S22 job no longer writes it. Test users are named exactly as the persona IDs
  (04-conventions). Jira BPA-15, BPA-16 and BPA-26 were updated the same day.
- **Amended 13 Nov 2026 (BPA-48; E2-D1, Khitaba for the PO, 10 Nov 2026; HR route parked 11 Nov 2026):** Epic 2 adds four personas, so
  `personas.yaml` lists **eleven** users, one per Camunda group, username equal to the group ID: `timekeeper`, `production-supervisor`,
  `cost-controller`, `project-manager` (employeeRef E9008 to E9011) beside the seven above. `line-manager` and `hr-manager` are not
  added: the HR approval route is parked with BPA-71. Access to a task is still by group only. **Project scope returns in one narrow
  form:** the table `user_project` (username, project_ref; POC database, ASSUMPTION; data model v0.7 section 13) holds which projects a
  user sees (BPA-76 to BPA-79). It is written by this S22 sync from a `projects:` list per persona in `personas.yaml` (it has a foreign key to
  `app_user`, so a SQL seed cannot write it), and by the PPC Team through the portal (BPA-76). There is still no department or role
  scope and no `user_role_scope` table. **Restart behaviour (ASSUMPTION):** the sync gives a persona its projects only while that user has no
  `user_project` row, so a project the PPC Team removed in the portal is not brought back by a restart; if every project of a user was removed,
  the next start seeds that user again.

## Sources

09 S5, S6, S15, S22, S24-S29, G9, L2, L4, L9, L18, L19, §3, §4 items 6-7; 06 D19, D52, D63; 07 §8, §9;
08 Gate 1.
