# STAR BPA POC

A proof of concept from LCS: one business-process slice for STAR, specified, built and tested on Camunda 8 and PostgreSQL.
The systems of record (D365 F&O, SAP, FMS, myPeople) stay as they are; the POC automates the work between them. All data is
synthetic, and the codename "STAR" is used in every output.

## What this is

The chosen slice is **Work Pack Assignment** in Production Planning & Control: a Time & Method Analyst creates a Work Pack in
the React portal, it is checked, signed off, scheduled and printed, and the Production Team then uploads an Excel file that
assigns Workers to it. The POC also asks how far Claude's tools can carry delivery from requirements to working software.

The purpose, the questions the POC answers, scope, team and plan are in [docs/00-context.md](docs/00-context.md). Requirements
are in the Jira space **BPA** (lowcodesol.atlassian.net), not in this repo.

## Stack

| Part | Choice | Decided in |
|---|---|---|
| Process engine | Camunda 8.10.1 Self-Managed (Operate, Tasklist, Connectors) | [ADR 0001](docs/adr/0001-runtime-and-versions.md) |
| Database | PostgreSQL 17 (15 or higher): databases `camunda` and `bpa`, schema `bpa` | [ADR 0001](docs/adr/0001-runtime-and-versions.md) |
| Backend and job workers | Spring Boot 3.5 on Java 21, Flyway migrations, one `bpa-app` service | [ADR 0002](docs/adr/0002-application-stack.md) |
| Portal | React (Vite, TypeScript, TanStack Query), API types generated from the contract | [ADR 0017](docs/adr/0017-frontend-architecture.md) |
| Mocks | WireMock 3.9.1 for the systems the POC does not touch; Mailpit for email | [docs/03-sor-matrix.md](docs/03-sor-matrix.md) |

All decisions are in [docs/09-architecture-decisions.md](docs/09-architecture-decisions.md) and the [ADR index](docs/adr/README.md).

## Run it locally

You need Docker with Compose v2. The backend and frontend builds are described in [backend/README.md](backend/README.md) and
[frontend/README.md](frontend/README.md).

```sh
cp .env.example .env        # placeholders for a synthetic, local-only stack; change them if you like
docker compose up -d        # first run builds bpa-app and the portal; add --build after pulling changes
```

| Service | URL | Notes |
|---|---|---|
| **Portal (React)** | http://localhost:3000 | nginx serves the built portal and proxies `/api` to the app, so both are one origin (BPA-80) |
| Camunda (Operate, Tasklist, Admin, REST API) | http://localhost:8080 | log in as `demo` / `demo` |
| BPA app (API and job workers) | http://localhost:8081 | health at `/actuator/health` |
| Connectors | http://localhost:8086 | |
| WireMock | http://localhost:8089 | mock partner systems |
| Mailpit | http://localhost:8025 | mock SMTP inbox (backend sends to port 1025); drawing-revision emails arrive here |
| PostgreSQL | localhost:5432 | user and passwords in `.env` |

On start-up the app creates the **seeded personas** in Camunda and deploys the BPMN and DMN models from `models/`
([ADR 0005](docs/adr/0005-identity-and-authorisation.md)). There are exactly seven, one per Camunda group, each with the
username equal to its group ID, and all share the password `DEMO_PASSWORD` from `.env`:

`time-method-analyst`, `project-engineer`, `production-planner`, `production-team`, `ppc-team`, `supervisor`, `workpack-owner`

They are defined in [backend/src/main/resources/personas.yaml](backend/src/main/resources/personas.yaml); access is by group
only ([docs/04-conventions.md](docs/04-conventions.md)). Reference data (Workers, activities, drawings) is seeded by Flyway.

Open the portal at **http://localhost:3000** and log in as a persona. Use it from the same machine (`localhost`): it is plain http, and the
session cookie is not marked `Secure` (09 S24, ADR 0005 S26). For a few seconds after a cold start the portal's `/api` calls answer 502 until the app is up; reload. For work on the portal itself, run the Vite dev server next to the stack:

```sh
cd frontend
npm ci
npm run dev                 # http://localhost:5173, proxies /api to the app on :8081
```

To start again from empty data, run `docker compose down -v`.

**Setting the business date for QA (BPA-81).** Scenarios that start "Given today is … (Asia/Riyadh)" need a date other than today. In `.env` set
`BPA_PROFILES=local,qa` and `BPA_CLOCK_TODAY=2026-12-01` (yyyy-MM-dd), then run `docker compose up -d bpa-app`; the log shows a WARN "QA business date is …".
Time keeps moving and the date rolls on after midnight in Asia/Riyadh; restart `bpa-app` to hold it. Remove both lines and restart to go back to the real date.
The Create and Confirm Work Pack date checks use the server's business date (`GET /api/v1/business-date`, BPA-82), so the screen and the save step agree. Database and Camunda times stay real. QA only, never for a shared environment.

Tests: `./mvnw -B verify` in `backend/` (needs Docker for Testcontainers) and `npm test` in `frontend/`.

## Repo layout

The rule behind it is [ADR 0003](docs/adr/0003-repo-layout.md); paths in every doc are from the repo root.

| Path | Holds |
|---|---|
| `models/bpmn/`, `models/dmn/` | Process and decision models, the single copy that is deployed |
| `api/openapi.yaml` | The API contract |
| `templates/` | Files users download, such as the Worker assignment Excel template ([templates/README.md](templates/README.md)) |
| `backend/` | Spring Boot project root; also `mocks/` (WireMock mappings) and `postgres/init/` |
| `frontend/` | The React portal |
| `docs/` | Standards, [ADRs](docs/adr/README.md), [form specs](docs/form-specs/README.md), the [data model](docs/data-model.md) |
| `intent/` | Per epic: `intent.md`, `spec.md` and one plan per story ([index](intent/README.md)) |
| `REVIEW.md` | The review passes recorded before each PR |
| `.claude/`, `.mcp.json` | Claude Code agents, skills, hooks and the Jira connection |
| `.github/workflows/` | CI: backend tests, lint, plan check |

## Where things live

- **Contract:** [api/openapi.yaml](api/openapi.yaml); the Spring interfaces and the portal types are generated from it.
- **Models:** `models/bpmn/` and `models/dmn/`. Never copy them into `backend/`.
- **Data and ownership:** [docs/data-model.md](docs/data-model.md) and [docs/03-sor-matrix.md](docs/03-sor-matrix.md) (which system owns which data).
- **Conventions and story format:** [docs/04-conventions.md](docs/04-conventions.md), [docs/02-story-standard.md](docs/02-story-standard.md); terms in [docs/01-glossary.md](docs/01-glossary.md).
- **Delivery process:** [CLAUDE.md](CLAUDE.md) (Jira first, a plan per story, tests first, a PR for every change), [ADR 0015](docs/adr/0015-delivery-artifact-chain.md) and [ADR 0016](docs/adr/0016-jira-source-of-truth.md).

Nothing is committed to `main` directly: every change goes through a pull request that Ahsan approves and merges.
