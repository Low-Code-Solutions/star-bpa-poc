# 0002 Application stack

- Status: Accepted
- Date: 2026-10-27 (decided, 09 LOCKED); written 2026-10-29 (BPA-37)
- Deciders: Ahsan (Build Lead), Usama (Lead Developer)
- Informed: Faraz (Product Owner and Architect), Khitaba (BA)
- 09 rows: "Workers are Spring Boot" (09 §1 note, closes 08 R7 and the handover's "Java vs Node"); S23

## Context

The compose file already described the application container as a "Spring Boot project", but the
handover still listed the job-worker language (Java or Node) as open (08 R7). Claude Code needs one stack
and one package layout to put code in the right place.

## Options considered

1. **Java with Spring Boot** for the backend and the job workers (08 Gate 1, proposal for ADR 0002:
   "Spring Boot + Camunda Spring SDK, Flyway, one `bpa-app` container"). Chosen.
2. **Node** for the job workers (the open "Java vs Node" item in the handover, cited by 08 R7). Rejected
   by 09 §1. The sources record no further comparison.

## Decision

- **Workers are Spring Boot** (09 §1). The backend that React calls (ADR 0004) is the same Spring Boot
  project: `backend/` is the Spring Boot project root (L1, ADR 0003), and the backend and the job workers
  share one service identity (S25, ADR 0005). OpenAPI generation targets Spring Boot 3 (S21, ADR 0011).
- **Base Java package `com.lcs.star.bpa`**, with sub-packages `api`, `task`, `worker`, `domain`,
  `persistence`, `adapter`, `config` (S23). If 04-conventions sets a different name, 04-conventions wins;
  as of 29 Oct it sets none.
- Database migrations use **Flyway** (S16, S17; ADR 0010).
- The front end is **React** (S2; ADR 0004), not part of the Spring Boot project.

## Consequences

- The Camunda client library is not named in 09. 08 proposed the Camunda Spring SDK; the choice is made
  when the walking skeleton is built (L4, L5) and recorded in that item's plan. Not recorded in the sources
  as a decision.
- S23 is to be added to 04-conventions (09 §4 item 5).
- Walking skeleton (BPA-43): the Camunda client library is `io.camunda:camunda-spring-boot-3-starter` 8.10.1 (`@JobWorker`), with
  `camunda-process-test-spring-boot-3` for tests, on Spring Boot 3.5.x and Java 21. Spring Boot 3 follows S21 (ADR 0011); the open-source
  support for Boot 3.5 ended in June 2026, so **Boot 4 migration is a production item**.
- camunda-spring-boot-3-starter 8.10.1: workers never run while micrometer-core is on the classpath (found under BPA-43). Micrometer is
  excluded and banned; recheck on the next 8.10.x patch and then report it to Camunda.

## Sources

09 §1 (Spring Boot note), S2, S16, S21, S23, S25, L1, §4 item 5; 08 R7 and Gate 1.
