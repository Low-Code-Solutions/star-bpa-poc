# The React portal in the compose stack (BPA-80; ADR 0004, ADR 0005 S26): built once, then served by nginx, which also proxies /api to
# bpa-app so the portal and the API are one origin (as the Vite dev server does, BPA-43 Q11).
#
# Build context is the repo root (compose: context ".", dockerfile "frontend/Dockerfile"), like bpa-app: `npm run build` runs
# `gen:api` first, which reads ../api/openapi.yaml (S21). Only frontend/ and api/openapi.yaml are sent: see
# frontend/Dockerfile.dockerignore (the root .dockerignore is for the backend image and excludes frontend/).
# Exact tags, not floating ones, each older than two weeks when chosen (10 Nov 2026): Node 24 as in CI, nginx 1.31 mainline.

FROM node:24.22.1-alpine3.23 AS build
WORKDIR /build
# Dependencies first, so a source-only change does not reinstall them.
COPY frontend/package.json frontend/package-lock.json frontend/
WORKDIR /build/frontend
RUN npm ci
WORKDIR /build
COPY api/openapi.yaml api/openapi.yaml
COPY frontend/ frontend/
WORKDIR /build/frontend
# prebuild runs gen:api (types from ../api/openapi.yaml); then tsc --noEmit and vite build into dist/.
RUN npm run build

FROM nginx:1.31.7-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/frontend/dist /usr/share/nginx/html
EXPOSE 80
