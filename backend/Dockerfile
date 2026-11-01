# Build context is the repo root (compose: context ".", dockerfile "backend/Dockerfile"), so that models/, templates/ and api/ sit
# beside backend/ and the pom's ../models and ../templates resource directories and ../api/openapi.yaml (S21) resolve. They are copied into the image build stage only,
# never into the backend/ source tree (ADR 0003, L1).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY models/ models/
COPY templates/ templates/
COPY api/ api/
COPY backend/ backend/
WORKDIR /build/backend
RUN sh ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/backend/target/bpa-*-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
