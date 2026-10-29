# 0003 Repo layout

- Status: Accepted (amended by ADR 0015)
- Date: 2026-10-27 (decided, 09 LOCKED); amended 2026-10-28 (ADR 0015); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: L1 (amended by ADR 0015); L17

## Context

On 27 Oct the path conventions disagreed (08 R5): the design pack used `forms/…` and `models/…`, the repo
had `backend/models/forms/`, docs/04 had no forms path, and CLAUDE.md had an open question on the
`backend/` prefix. Claude Code would put files in the wrong place or ask every time. The decisions log
(09) also lived only in the Claude Project, where Claude Code cannot read it.

## Options considered

1. **All code under `backend/`; forms in `backend/models/forms/`** (08 Gate 1, proposal for ADR 0010).
   Rejected: superseded by L1 (09 §3).
2. **Top-level layout by artifact type** (L1). Chosen.
3. **An `infra/` folder** for the WireMock mocks and the Postgres init. Considered on 28 Oct and not
   adopted (Ahsan; 09 change log).

## Decision

Top level (L1, as amended by ADR 0015):

| Path | Holds |
|---|---|
| `docs/`, `docs/adr/`, `docs/form-specs/` | Standards and design docs; ADRs; field specs for React screens |
| `models/bpmn/`, `models/dmn/` | Process and decision models (single copy) |
| `api/openapi.yaml` | The API contract |
| `templates/` | Files users download |
| `backend/` | Spring Boot project; Flyway in `backend/src/main/resources/db/migration/` (Spring Boot default) |
| `backend/mocks/`, `backend/postgres/init/` | WireMock mappings and the Postgres init (stay under `backend/`, Ahsan, 28 Oct) |
| `frontend/` | React portal |
| `intent/`, `REVIEW.md`, `.claude/` | Delivery artifacts, review passes, agents/hooks/settings (added by ADR 0015) |

- `backend/pom.xml` adds `../models` and `../templates` as resource directories, so auto-deploy and
  "Download template" read the single top-level copy. Never copy them into `backend/` (L1).
- `backend/models/` is deleted. The four drafted `.form` files move unchanged to `docs/form-specs/` as the
  S14 field spec; nothing deploys them (L1; ADR 0004).
- **09 is copied verbatim into the repo** as `docs/09-architecture-decisions.md`, including READ THIS FIRST,
  and kept in step (L17).

## Consequences

- 04-conventions' top-level `db/migration/V<n>__…` path is superseded (09 §3; S16, ADR 0010).
- The restructure was done under BPA-34, the models and form specs moved under BPA-36, and 09 was copied
  under BPA-35. Since BPA-35 the repo is the master copy of 09 (ADR 0015; CLAUDE.md), so "kept in step"
  (L17) now means the Claude Project copy is refreshed after each merge.
- `backend/pom.xml` does not exist yet; the resource-directory rule applies when the walking skeleton
  creates it.

## Sources

09 L1, L17, §3, change log (28 Oct); 08 R5 and Gate 1; ADR 0015.
