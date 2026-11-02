# Plan: BPA-44 Remove department/project scope: 7 personas, access by group only
Jira: BPA-44 (Task, epic BPA-3)
Story last updated: 2026-11-02T13:07:09.943+0400
Status: Approved (Ahsan, 2 Nov 2026)
Approver: Ahsan (Build Lead)
Branch: BPA-44-remove-scope, from main ecd4fbc

## Readiness check (Task, light)
- Source: Jira BPA-44 (no comments), PO decision 2 Nov 2026; BPA-15, BPA-16, BPA-26 read (updated 2 Nov, no scope; they only need the 7 personas and group checks). Jira wins over ADR-0005, 09 S6/L9, data-model 5.10-5.11, 03-sor-matrix, spec.md row 69, which stay stale (not edited here).
- Not a story: no Gherkin. Tests keep their BPA43_/BPA25_ names where the behaviour survives; changed or new tests use `BPA44_<camelCase>`.
- Finding: BPA-26 task API code is NOT in the repo. `backend/.../task/` holds only `package-info.java`; no commit mentions BPA-26. So there is no scope check there to remove. Jira's Development panel shows a merged PR on BPA-26; likely a mislink. Will mention in the PR.
- Finding: the only enforcement today is group-based (`SecurityConfig` 403, `WorkPackController` start). No code anywhere reads department/project scope; scope is only written.

## Where the scope design lives (non-docs)
| File | What it does | Change |
| --- | --- | --- |
| backend/src/main/resources/personas.yaml | 9 users (tma-1, tma-2, pe-1, planner-1, wpo-1, ppc-1, supervisor-1, pt-1, pt-2), 4 `scope:` entries | 7 users named as the groups, no scope, no extra users |
| backend/.../config/PersonaFile.java | `Persona.scope` field and `Scope` record | remove both |
| backend/.../config/PersonaSync.java | `syncScope` upserts `app_user`, inserts/prunes `user_role_scope`; javadoc | keep app_user upsert (rename `syncAppUser`), drop scope insert/prune, fix javadoc |
| backend/.../db/migration/V1__baseline_schema.sql L570-593 | `user_role_scope` table, 4 column comments, FKs to department and project_job_code | drop (see Q1); the section comment "Domain F: access scope" reworded |
| backend/.../db/migration/R__seed_reference_data.sql L10 | header comment names `user_role_scope` | reword comment only; PRJ-139/140 and departments stay |
| backend/src/test/.../PersonaSyncTest.java | 9-user and scope-row assertions, `scopeRows()` helper | rewrite first (see tests) |
| backend/src/test/.../api/AuthApiTest.java, IdleSessionTest.java, Bpa25CamundaChecksTest.java | log in as `tma-1`, `pt-1` | rename to `time-method-analyst`, `production-team` |
| frontend/src/App.test.tsx, frontend/README.md | `tma-1` | rename |
| backend/README.md L26-28 | "9 users", "writes ... user_role_scope" | update to 7 users, app_user only |
| intent/BPA-3-.../plans/BPA-39/40/42/43.md, spec.md row 69 | history | not edited (closed plans are history; spec.md is a doc owned by the separate docs update) |
| docs/*, ADR-0005 | stale | not edited (per instruction) |
| SecurityConfig, WorkPackController, AuthController, MeController, CamundaAdminClient, application.yml, openapi.yaml, workflows | group checks / no scope logic | no change expected; verify by grep |

No scope logic exists in `api`, `task`, `worker`, `domain` or `persistence`.

## Files that change
personas.yaml, PersonaFile.java, PersonaSync.java, V1__baseline_schema.sql (or new V2, Q1), R__seed_reference_data.sql (comment), PersonaSyncTest.java, AuthApiTest.java, IdleSessionTest.java, Bpa25CamundaChecksTest.java, App.test.tsx, backend/README.md, frontend/README.md, this plan, REVIEW.md (qa-reviewer pass), regenerated intent index if the repo does that.

## Order of work (test-first, ADR 0014)
1. Branch exists. Commit this plan first.
2. Tests first, run, show they fail for the expected reason:
   - `PersonaSyncTest`: replace `BPA43_personaSyncCreatesUsersGroupsAndScopeRows` with `BPA44_personaSyncCreatesSevenPersonasInTheirGroups` (exactly 7 personas, username == its single group, each logs in with DEMO_PASSWORD, `app_user` holds exactly those 7, and `to_regclass('bpa.user_role_scope')` is null). Idempotency test drops scope assertions, counts 7 app_user rows.
   - Rename the logins in the other tests (`tma-1` -> `time-method-analyst`, `pt-1` -> `production-team`) and in App.test.tsx. Expected failure: unknown user / 9 != 7 / table still exists.
   - Keep `BPA43_startWorkPackIsDeniedToOtherGroups403` (logs in as `production-team`): this is the wrong-group 403 test.
3. Code: personas.yaml, PersonaFile, PersonaSync, schema, comments, READMEs. Run tests green.
4. Local run: `docker compose down -v` (needs your go-ahead, V1 path) then `up -d --build`; check 7 users + bpa-service in Camunda, each persona logs in and gets `/me`.
5. Verification greps, then qa-reviewer, plan Closed, PR.

## Scenario-to-test table (Task; done criteria instead of Gherkin)
| Done criterion | Test |
| --- | --- |
| exactly 7 persona users + bpa-service, each logs in | `BPA44_personaSyncCreatesSevenPersonasInTheirGroups` (integration) |
| sync idempotent, app_user upserted | `BPA43_personaSyncIsIdempotent` (updated) |
| each persona sees /me | local run step 4 (plus existing `AuthApiTest` /me test with new username) |
| table gone, grep clean | assertion in the BPA44 test + grep step; `SorCommentTest` still passes |
| wrong group gets 403 | `BPA43_startWorkPackIsDeniedToOtherGroups403` (kept) |
Removed: `BPA43_personaSyncCreatesUsersGroupsAndScopeRows` (replaced), `scopeRows()` helper, scope assertions in the idempotency test.

## Risks
- Existing developer databases: with V1 edit, a DB that already ran V1 fails Flyway checksum validation; fix is `docker compose down -v` (09 L5). With V2, no reset needed.
- Camunda keeps the old 9 users (tma-1 ...) in its identity volume until `down -v`; the sync only adds. Old users would linger if volumes are not reset. Noted in PR and README.
- Personas change usernames and emails (`<group>@example.com`), employeeRef E9001-E9007 kept. All synthetic, ASSUMPTION unchanged.
- Docs stay stale until the separate docs PR; REVIEW.md will say so.
- Jira says "grep is clean" with no docs exception; you said docs excepted. Closed plans (BPA-39/40/42/43) and spec.md also contain the word and are not edited; I read "docs excepted" as covering `docs/`, `intent/` history and `spec.md`. Tell me if you want `spec.md` touched.

## Proof
Test output (backend `./mvnw verify` incl. integration tag, frontend `npm test`), grep output for `user_role_scope|UserRoleScope`, compose run with 7 persona logins and `/me`, CI green (build and tests, SOR-comment, bpmnlint, harness, plan check), qa-reviewer findings in REVIEW.md.

## Questions
- **Q1 (answered by Ahsan, 2 Nov 2026): NO shared or showcase environment exists; edit V1 and reset with `docker compose down -v`.** Original question:: does a shared or showcase environment exist yet?** If NO: edit `V1__baseline_schema.sql` and reset with `docker compose down -v` (09 L5). If YES: add `V2__drop_user_role_scope.sql` (`DROP TABLE bpa.user_role_scope;`) and leave V1 untouched. The repo shows none (BPA-43 closed, only local compose; spec L5 says V1 is frozen at the first shared environment), but I will not guess. Default if you say nothing: V1 edit.
- Q2: confirm `spec.md` row 69 stays untouched in this PR.
- ASSUMPTION to comment in Jira (after your yes): persona emails `<group>@example.com` and employeeRef values are synthetic.

## Human-gap log
- BPA-26 "merged task API code" not found in repo (see Readiness check).
- Docker down -v needs your approval at run time.
