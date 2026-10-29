# 0001 Runtime and versions

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: G1; S24 (consequence)

## Context

The design pack asked which Camunda 8 version the POC runs (07 §11 G1) and proposed 8.7; the models had
been linted against the 8.7 rules. The repo check of 27 Oct found Camunda **8.10.1** already pinned in
`.env.example` and in the compose defaults (08 R1), so the question became "confirm", not "decide".
A hosted environment (VM) had also been raised without measured figures (S24).

## Options considered

1. **Camunda 8.7** (07 G1 first proposal). Rejected: the repo already pins 8.10.1 (08 R1).
2. **Camunda 8.10.1 Self-Managed**, PostgreSQL as secondary storage, Connectors 8.10.1, WireMock 3.9.1
   (08 Gate 1, proposal for ADR 0001). Chosen as G1.
3. For hosting: size a VM now, or defer until a hosted environment is needed (S24). Deferral chosen.

## Decision

- **Camunda 8.10.1** is what the app runs. Patches come from the official 8.10 Docker Compose bundle (G1).
- **PostgreSQL 15 or higher** (8.10 drops 14); **17 recommended** (G1).
- BPMN `executionPlatformVersion` is **8.10.1** (G1).
- **VM sizing is deferred, not open** (S24). Development runs on localhost on the team's machines. A hosted
  machine is sized only if a hosted environment is needed (for example the showcase), from measured
  `docker stats`.

## Consequences

- The compose stack and `.env.example` pin `CAMUNDA_VERSION=8.10.1`, `CAMUNDA_CONNECTORS_VERSION=8.10.1`,
  `POSTGRES_VERSION=17`, `WIREMOCK_VERSION=3.9.1`; Camunda uses PostgreSQL as RDBMS secondary storage.
- The four models carry `executionPlatformVersion="8.10.1"` and are linted with
  `plugin:camunda-compat/camunda-cloud-8-10` (L10; see ADR 0014).
- Confirming that the compose stack boots with PostgreSQL secondary storage is a hands-on step for
  Ahsan/Usama (L19), logged as a human step for the POC measure.
- S24 is revisited together with S28 and S29 (ADR 0005) when a shared or showcase environment is set up.

## Sources

09 G1, S24, L10, L19; 07 §11 G1; 08 R1 and Gate 1.
