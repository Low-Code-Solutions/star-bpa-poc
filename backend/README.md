# backend

Spring Boot 3.5 backend and Camunda job workers for the STAR BPA POC (ADR 0002). Java 21; Maven through the committed wrapper.

## Build and test
From `backend/`, with Docker running (Testcontainers starts PostgreSQL 17 and Camunda 8.10.1):

```sh
./mvnw -B verify
```

CI runs the same command (`.github/workflows/backend.yml`). Without a local JDK, run it in a container that uses the host's Docker:

```sh
docker run --rm --network host -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD/..":"$PWD/.." -w "$PWD" eclipse-temurin:21-jdk sh ./mvnw -B verify
```

## Run
From the repo root: copy `.env.example` to `.env`, then `docker compose up -d --build`. The app listens on http://localhost:8081
(`/actuator/health`). On start-up Flyway applies `src/main/resources/db/migration/` to schema `bpa`, and the BPMN and DMN files
in the top-level `models/` folder deploy to Camunda.

## Login and personas (ADR 0005)
- With profile `local` or `showcase` (compose sets `local`), the S22 persona sync runs at start-up as the Camunda admin
  (`CAMUNDA_ADMIN_*`): it creates the seven S6 groups, the 9 users in `src/main/resources/personas.yaml` (password
  `DEMO_PASSWORD`) and `bpa-service` (password `BPA_SERVICE_PASSWORD`, kept if the user exists), and writes `app_user` and
  `user_role_scope`. The app then deploys the models and runs its workers as `bpa-service`.
- API contract: `api/openapi.yaml` (S20). The Spring interfaces are generated at build time into
  `target/generated-sources/openapi` (S21); controllers in `com.lcs.star.bpa.api` implement them.
- Tests tagged `integration` (`PersonaSyncTest`, `Bpa25CamundaChecksTest`) start their own Camunda 8.10.1 configured like compose.

## Layout
- `models/` and `templates/` are resource directories in `pom.xml` (ADR 0003, L1). Never copy them into `backend/`.
- Base package `com.lcs.star.bpa`, sub-packages `api`, `task`, `worker`, `domain`, `persistence`, `adapter`, `config` (S23).
- `micrometer-core` is excluded and banned by the enforcer: with it on the classpath the Camunda starter 8.10.1 never runs a
  worker (BPA-43 plan, Human-gap log).
