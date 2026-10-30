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

## Layout
- `models/` and `templates/` are resource directories in `pom.xml` (ADR 0003, L1). Never copy them into `backend/`.
- Base package `com.lcs.star.bpa`, sub-packages `api`, `task`, `worker`, `domain`, `persistence`, `adapter`, `config` (S23).
- `micrometer-core` is excluded and banned by the enforcer: with it on the classpath the Camunda starter 8.10.1 never runs a
  worker (BPA-43 plan, Human-gap log).
