# Plan: BPA-34 Restructure repo to 09 L1 (layout only, no content)

Jira: BPA-34 (Task, epic BPA-3)
Story last updated: 2026-10-28T19:53:05.414+0400
Status: Closed (2026-10-28)
Approver: Ahsan (Build Lead)
Branch: BPA-34-restructure-repo-layout, from main 65eb5de

## Readiness check (Task, so a light check)
- Goal, changes, scope and done criteria stated in Jira: yes
- Decision behind it: 09 L1 (LOCKED), amended by ADR 0015; mocks and Postgres init stay under
  `backend/` (Ahsan, 28 Oct 2026, option a)
- Blocking questions: none. Q1 and Q2 below are non-blocking.

## Target layout (09 L1 as amended)

```
docs/  docs/adr/  docs/form-specs/      intent/  REVIEW.md  .claude/
models/bpmn/  models/dmn/  api/  templates/
backend/                                 Spring Boot project root (backend/pom.xml, L1)
  src/main/resources/db/migration/       Flyway (S16, L1)
  mocks/mappings/  mocks/__files/        WireMock (unchanged)
  postgres/init/                         PostgreSQL init (unchanged)
frontend/
```

## Files that change

| File | Change |
|---|---|
| backend/models/bpmn/.gitkeep, backend/models/dmn/.gitkeep, backend/models/forms/.gitkeep | Deleted (L1: `backend/models/` is deleted) |
| backend/api/.gitkeep | Deleted (API contract is top-level `api/`) |
| backend/db/migration/.gitkeep | Deleted (Flyway moves to the Spring Boot default path) |
| backend/app/.gitkeep | Deleted: L1 makes `backend/` the Maven root (`backend/pom.xml`), so there is no `backend/app/` (interpretation of L1; see Q2) |
| models/bpmn/.gitkeep, models/dmn/.gitkeep | New |
| api/.gitkeep | New |
| templates/.gitkeep | New |
| docs/form-specs/.gitkeep | New |
| backend/src/main/resources/db/migration/.gitkeep | New |
| CLAUDE.md | "Stack and layout": replace the `backend/` code line with the L1 layout; all docs/04 paths become repo-root paths |
| docs/04-conventions.md | Migrations line: `backend/src/main/resources/db/migration/V<n>__<description>.sql`; one line saying all paths are from the repo root. BPMN/DMN and API paths already match the top level |
| docker-compose.yml | Commented `bpa-app` service: `build: ./backend` and its comment line, instead of `./backend/app`. Volumes for Postgres init and WireMock unchanged |
| intent/BPA-3-work-pack-assignment/plans/BPA-34.md | This plan |
| intent/README.md | Regenerated |
| .claude/hooks/test-hooks.sh | Departure (added during the work): sample paths in the throwaway test repos changed from `backend/app/Foo.java` to `backend/src/main/java/Foo.java`, so no stale layout path remains; behaviour unchanged |

Not changed: `backend/mocks/`, `backend/postgres/init/` (kept by decision); `docs/03-sor-matrix.md`
"mocks/" location (03 is replaced in BPA-35); `.gitignore` and compose `backend/exchange` lines (Q1);
any content.

## Order of work
1. BPA-34: this plan (Draft), then approval recorded.
2. BPA-34: folder moves (deletes and new `.gitkeep`s).
3. BPA-34: CLAUDE.md, docs/04, docker-compose.yml path lines.
4. BPA-34: checks, qa-reviewer pass, open PR with the plan Approved.
5. BPA-34: after review, set this plan to Closed in the final commit, before Ahsan merges.

## Risks
- A later Task or story puts files in the old paths. Mitigation: the CLAUDE.md layout line is the
  single statement of paths, and docs/04 says paths are from the repo root.
- Removing `backend/app/` is an interpretation of L1's `backend/pom.xml`. Mitigation: Q2; it is one
  `.gitkeep` and one commented compose line, easy to reverse.
- Compose stops validating. Mitigation: `docker compose config -q` before and after.

## Proof
- `docker compose config -q` passes (with `.env` copied from `.env.example` in a scratch copy).
- `find` listing of the tree matches the target layout above; no `.gitkeep` left in removed folders.
- `grep` finds no remaining references to `backend/models`, `backend/api`, `backend/db` or
  `backend/app` outside this plan and git history.
- Hook tests still pass (`sh .claude/hooks/test-hooks.sh`). Done 28 Oct: 25 passed, 0 failed (dash and bash).
- Done 28 Oct: `docker compose config -q` valid before and after; stale-path grep clean; removed folders gone.
- qa-reviewer findings pasted in the PR.

## Questions
- Q1 (non-blocking): `backend/exchange` (the watched folder) is still in `.gitignore` and the commented
  compose service. BPA-3 records D58 (portal upload replaces the watched folder) as decided, while 09
  S19 still shows it awaiting the BA's confirmation. Contradiction flagged, not resolved here; remove
  in a later item once S19 is marked confirmed in 09.
- Q2 (non-blocking): confirm that L1's `backend/pom.xml` means the Spring Boot project lives directly in
  `backend/` (so no `backend/app/`). Proceeding on that reading unless you say otherwise.
  Resolved 28 Oct: Ahsan agreed `backend/` is the Spring Boot root (one deployable, S2). A
  `backend/.dockerignore` excluding `mocks/` and `postgres/` comes with the walking skeleton, when a
  Dockerfile first exists.

## Human-gap log
- 28 Oct: layout option (a) chosen by Ahsan in chat (no `infra/`, no `microservices/`).
- 28 Oct: plan approved and Q2 answered by Ahsan in chat (20:58 PKT).
- 28 Oct: the plan was committed as Draft (3dbd317) before approval because the session's stop hook
  requires the work of each turn to be committed and pushed; approval recorded in 1994bf8.
- 28 Oct: PR #5 cannot be approved in GitHub: it was opened under Ahsan's account (the session uses his
  credentials) and GitHub blocks self-approval. Review confirmed by Ahsan in chat (21:06 PKT); plan
  closed before merge; the merge is the sign-off.
