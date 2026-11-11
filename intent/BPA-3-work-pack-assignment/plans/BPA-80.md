# Plan: BPA-80 Serve the React portal in the compose stack (nginx, same-origin /api proxy)
Jira: BPA-80 (task, epic BPA-3)
Story last updated: 2026-11-11T12:30:09.088+0400
Status: Closed (2026-11-11)
Approver: Ahsan (Build Lead)
Branch: BPA-80-portal-in-compose, from main 63d7b9c


## Context
Independent QA (Zain, on Zain's machine; claude/14-qa-independent-test-strategy.md v0.3, PO decision 11 Nov 2026) must test the
built portal against the real stack. Today the portal runs only as a developer's Vite dev server (`npm run dev`, port 5173,
`/api` proxied to `localhost:8081`). The compose file has only a stale commented `frontend` block. This task makes
`docker compose up -d --build` also build and serve the portal at **http://localhost:3000**, same-origin with the API, so the
session and `XSRF-TOKEN` cookies behave exactly as under Vite (BPA-43 Q11, ADR 0005 S26). The Vite dev workflow does not change.

## Session start: Jira against the pasted request (Jira wins)
Read BPA-80 and its one comment (10516, Faraz, 11 Nov: the three known problems and "hand the image tag to Zain once merged").
`updated` = 2026-11-11T12:30:09.088+0400 (recorded in the header). Differences:
1. **Status:** the request says Ready; Jira says **In Progress** (changed 11 Nov 12:30, no assignee). Not a scope difference.
2. **Port:** Jira says "proposal: 3000:80"; the request fixes 3000:80. No conflict (nothing in compose or listening on this host uses 3000).
3. **The request adds to Jira's Done-when** (same intent, stricter proof): nginx header and cookie pass-through, copying only
   `frontend/` and `api/openapi.yaml` into the image, a clean-checkout run, all 7 personas, and the unhappy CSRF path.
4. **The pasted request is cut off** after "A deep link to a task". I take the rest from Jira: the deep link reloads without a
   404 and existing CI stays green.
5. **Jira's comment asks for the "image tag" to be handed to Zain.** Compose builds the image locally; no registry is in scope
   (question Q3).
No contradiction of substance, so the plan goes ahead for your approval.

## Readiness check
| Item | State |
|---|---|
| Task, changes the repo, branch `BPA-80-portal-in-compose` | Yes |
| Estimate ≤ 8 | 2 |
| Done-when written (a Task has no Gherkin) | Yes: 4 items in Jira, 9 proof items below |
| No SOR, data, BPMN, DMN or OpenAPI change | Confirmed |
| PO approval | PO moved it to Ready on 11 Nov (Faraz, comment 10516) |
| Main at or after 63d7b9c | Yes: origin/main is 63d7b9c |
| Open question blocking | None. Q1 to Q7 below are confirmations |

## The known problems, verified
- **(a) Stale block:** the commented block runs `npm run dev` on `3000:3000` with `node:22-alpine`; Vite listens on 5173. Confirmed. It is replaced.
- **(b) Proxy target:** `vite.config.ts` proxies `/api` to `http://localhost:8081`, which inside a container is the container itself. Confirmed. nginx proxies to `bpa-app:8080` (the compose service name and internal port).
- **(c) Build context:** `package.json` has `prebuild: npm run gen:api`, which reads `../api/openapi.yaml`. Confirmed. The image build context is the repo root, like `bpa-app`.
- **(d) Found while reading, not in Jira:** the root `.dockerignore` is `*` then allows only `backend/`, `models/`, `templates/`, `api/`. With the repo root as context, **`frontend/` would be excluded and the build would find no source**. Fix without touching the shared file: a Dockerfile-specific ignore file `frontend/Dockerfile.dockerignore` (BuildKit reads `<Dockerfile>.dockerignore` first), so `bpa-app`'s context is unchanged.

## Checks before building (stop and ask if any is true): none is true
- **Cookies.** Session cookie: `HttpOnly`, `SameSite=Lax`, `Secure` = `${BPA_SESSION_COOKIE_SECURE:false}`, no domain or path set (application.yml:32-35). XSRF cookie: Spring's `CookieCsrfTokenRepository` (`withHttpOnlyFalse`), no domain, path `/`, `Secure` only when the request is HTTPS. Plain http on :3000 works. Confirmed again at runtime in the proof.
- **Forward headers.** `server.forward-headers-strategy` is not set. The app issues no redirects (login answers JSON; no `redirect` in backend config) and builds no absolute URLs; `Secure` is off. Nothing needs it. No backend or security change.
- **Hard-coded origins.** No `5173`, `8081`, CORS config, allowed origins or trusted-origin setting anywhere in `backend/src/main` (grep). The portal calls `window.location.origin`, so it follows whatever origin serves it.

## Design
**`frontend/Dockerfile`** (build context = repo root, set in compose):
- Stage 1 `node:<exact 24.x.y>-alpine` (CI uses Node 24): `WORKDIR /build`; copy `frontend/package.json` and `package-lock.json`, `npm ci` (dev dependencies stay, `openapi-typescript` is one); copy `api/openapi.yaml` and `frontend/`; `npm run build` (`prebuild` runs `gen:api`, then `tsc --noEmit && vite build`).
- Stage 2 `nginx:<exact 1.x.y>-alpine`: `frontend/nginx.conf` as `conf.d/default.conf`, `dist/` to `/usr/share/nginx/html`, `EXPOSE 80`.
- Exact patch tags, verified on Docker Hub at build time (as Mailpit v1.32.0 was). Recorded in the proof.

**`frontend/Dockerfile.dockerignore`:** `*`, then `!frontend/`, `!api/openapi.yaml`, then `frontend/node_modules/`, `frontend/dist/`, `frontend/src/services/schema.d.ts` (generated, gitignored; the image generates its own). Proof checks the context size and that nothing else is sent.

**`frontend/nginx.conf`** (one server block):
- `location /api`: `proxy_pass` to `bpa-app:8080` through a variable with Docker's resolver (`resolver 127.0.0.11 valid=10s ipv6=off;` and `set $backend http://bpa-app:8080;`). **Why:** plain `proxy_pass http://bpa-app:8080` resolves the name once at start, so after `docker compose up -d --build bpa-app` (which this project does after every backend change) nginx keeps the old container IP and answers 502 until restarted. With a variable the URI is passed through unchanged. Proof includes this restart case.
- Headers: `Host $http_host` (keeps `localhost:3000`), `X-Forwarded-For $proxy_add_x_forwarded_for`, `X-Forwarded-Proto $scheme`. **No** `proxy_cookie_*`, no header stripping or rewriting: `Cookie`, `Set-Cookie` and `X-XSRF-TOKEN` pass untouched. HTTP/1.1 upstream.
- `client_max_body_size 8m`. **Why (not in Jira):** nginx's default is 1 MB, but the assignment upload allows 5 MB files (6 MB request, `spring.servlet.multipart`). Without it a larger assignment file would get nginx's 413 HTML instead of the app's answer. The backend stays the one that decides the limit.
- `location /` with `try_files $uri /index.html` (React Router deep links); `/api` is a separate `location` before it and never falls through to `index.html`. Hashed `/assets/` get a long cache; `index.html` is `no-cache`. gzip on for text types.
- No `/actuator` proxying (the app's 8081 stays published as today).

**`docker-compose.yml`:** the commented block becomes a `frontend` service: `build: { context: ., dockerfile: frontend/Dockerfile }`, `depends_on: [bpa-app]`, `networks: [star]`, `ports: ["3000:80"]`, `restart: unless-stopped`. `bpa-app` has no healthcheck, so `depends_on` means "started"; for a few seconds after a cold start the proxy answers 502 until the app is up (accepted, noted for QA).

**Docs:** README (service table row, "Open the portal"), CLAUDE.md "Stack and layout" first bullet (add the portal on :3000). `frontend/README.md` and the Vite workflow are left as they are.

## Files
- New: `frontend/Dockerfile`, `frontend/Dockerfile.dockerignore`, `frontend/nginx.conf`, `intent/BPA-3-work-pack-assignment/plans/BPA-80.md`.
- Changed: `docker-compose.yml`, `README.md`, `CLAUDE.md`, `REVIEW.md` (review pass), `intent/README.md` (generated).
- Not touched: `frontend/vite.config.ts`, `package.json`, `src/`, backend code and config, `api/openapi.yaml`, models, data, `.env.example`, `.github/workflows/`, the root `.dockerignore`.

## Order of work
1. After approval: `git switch main && git pull`, confirm main at or after 63d7b9c, create `BPA-80-portal-in-compose`, commit this plan (Approved) as the first commit.
2. **Red state recorded:** before any change, `http://localhost:3000` is refused and `docker compose config --services` has no `frontend`.
3. Write the three frontend files. `docker build -f frontend/Dockerfile .` alone: context size, image runs, `/` serves `index.html`.
4. Replace the compose block; `docker compose up -d --build frontend` against the running stack (port 3000 is free) for quick feedback.
5. Proof script (below), then the clean-checkout run.
6. Docs. `qa-reviewer`, `REVIEW.md` pass, plan `Closed`, push, PR, read CI myself, Jira linkage comment (text shown first). Ahsan moves the status and merges.

## Done-when to proof
| # | Done when | Proof (evidence goes in this table at the end) |
|---|---|---|
| 1 | `docker compose up -d --build` on a clean checkout serves the portal at :3000 | Fresh `git clone` of the branch into a scratch folder (equivalent to `git clean -xfd`), `.env` from `.env.example` with synthetic passwords, a separate compose project name. `curl -i http://localhost:3000/` is 200 `text/html`; the JS under `/assets/` is 200 with a JS content type; `docker compose ps` lists `frontend` |
| 2 | Through :3000 only, for each of the 7 personas: login, `GET /api/v1/me` returns that user, logout works | A script over `time-method-analyst, project-engineer, production-planner, production-team, ppc-team, supervisor, workpack-owner`: `GET /api/v1/me` (401 and the XSRF cookie), login with `X-XSRF-TOKEN` (200), `GET /api/v1/me` (that username and group), logout, `GET /api/v1/me` (401). Output table of 7 rows |
| 3 | One state-changing call passes CSRF; the same call without the token is rejected | As `time-method-analyst`: `POST /api/v1/workpacks` with the token is 201; the same without `X-XSRF-TOKEN` is 403 ("...CSRF token is missing"); the `Set-Cookie`/`Cookie` headers shown unchanged through nginx |
| 4 | A deep link reloads without a 404 | `GET /tasks/2251799813784803` is 200 `index.html` (the portal has no per-task routes yet, any path renders it, AppRoutes.tsx). `GET /api/v1/nothing-here` is the backend's JSON answer, never `text/html` |
| 5 | Existing CI stays green | The PR's six checks, read by me from `gh pr view` before I say so. No frontend source, backend or workflow file changes |
| 6 | Cookies and headers pass through nginx (request item) | Compare `Set-Cookie` for login through :3000 with the same call to :8081 directly: same attributes (`HttpOnly`, `SameSite=Lax`, no `Secure`, no domain) |
| 7 | Uploads above nginx's default 1 MB reach the app (my addition) | A 2 MB file posted to `/api/v1/assignment-files` as `production-team` through :3000 is answered by the app (its own 400/file-level text), not nginx's 413. An ordinary `.xlsx` is 201 |
| 8 | Backend restart does not need an nginx restart (my addition) | `docker compose up -d --build bpa-app`, then `GET :3000/api/v1/me` answers 401 JSON (not 502) once the app is up, with no `frontend` restart |
| 9 | Vite dev workflow unchanged | `git diff --stat main` shows no change to `vite.config.ts`, `package.json`, `src/` |

Test-first rule: this Task has no Gherkin scenarios and no application code; the proof is the script above, written and run
against the "red" state first (no portal on :3000), then green.

## Risks
- **Stale upstream address** after a backend rebuild: handled by the resolver variable; proof 8.
- **Upload size** (nginx 1 MB default): `client_max_body_size 8m`; proof 7.
- **Build context:** the root `.dockerignore` hides `frontend/`; the Dockerfile-specific ignore file fixes it; proof 1 and the context size.
- **502 for a few seconds on a cold start** (no healthcheck on `bpa-app`). Accepted; no backend change.
- **Tag drift:** exact Node and nginx tags pinned; a floating tag is not used.
- **Port 3000 on a QA machine** may be taken by something else; the stop-and-ask rule applies here (Q5).
- **CI does not build the image,** so a broken Dockerfile would not fail CI (Q7).
- **Plain http only.** If a TLS front is ever added, `BPA_SESSION_COOKIE_SECURE` and forwarded-header handling must be revisited (09 S24 stays deferred).

## Questions
- **Q1:** status: Jira says In Progress, the request says Ready. Informational; who moved it?
- **Q2:** the pasted request is cut off at "A deep link to a task". I plan to the Jira Done-when. Is there more?
- **Q3:** "hand the image tag to Zain": compose builds locally and no registry is configured. Proposal: no push; Zain runs `docker compose up -d --build` from main. Confirm.
- **Q4:** BPA-43 plan Q11 (closed) says a compose `frontend` service "comes with the showcase environment, not now". BPA-80 (PO, 11 Nov) now asks for it for QA. I treat BPA-80 as the newer decision and do not edit the closed plan or ADR 0004. Confirm.
- **Q5:** should the host port be overridable (`${PORTAL_PORT:-3000}:80`) for QA machines where 3000 is taken? Not in scope as written; I will not add it unless you say so.
- **Q6:** the clean-checkout proof needs the host ports your running stack already uses (8080, 8081, 8086, 8089, 5432, 1025, 8025). I would **stop** (not remove) your local containers, run the scratch clone under a different project name, remove only the scratch project, then start yours again. Your database volume stays. OK to do that? Otherwise I do the proof on the running stack plus a no-cache image build, which is weaker.
- **Q7:** add a CI step that builds the image? Out of scope. If you want it, a follow-up Jira item (I will not create one without your confirmation).

## Human-gap log
- A real browser check (I have none here): open http://localhost:3000, log in as a persona, see the task list, hard-reload a deep link such as /tasks/123. Ahsan or Zain.
- Docker Desktop must be running; the scratch clone needs a few minutes for the first image builds (the backend image is built from source).
- Hand-off to Zain after merge: `git pull` on main, `docker compose up -d --build`, open http://localhost:3000.

## Proof (11 Nov 2026, evidence for the Done-when table)
Red first: before any change `http://localhost:3000` was refused (curl 000) and `docker compose config --services` listed no `frontend`.
Pinned: `node:24.22.1-alpine3.23` (21 Oct) and `nginx:1.31.7-alpine` (15 Oct), both older than two weeks.
Image build context: 420 kB (frontend/ and api/openapi.yaml only, via `frontend/Dockerfile.dockerignore`); `vite build` ran inside the image.
Proof script (kept outside the repo): `proof-bpa80.sh quick|full`, every call through http://localhost:3000.

| # | Result |
|---|---|
| 1 | **Clean checkout.** `git clone` of the branch (commit 229c268, 0 modified files, no node_modules or dist), `.env` copied from `.env.example`, project `bpa80scratch`, `docker compose up -d --build`: all 7 services up; `GET :3000/` is 200 `text/html` from nginx/1.31.7; `/assets/index-*.js` is 200 `application/javascript` |
| 2 | **7 personas**, each: `GET /api/v1/me` 401 (and the XSRF cookie) → login 200 → `/me` shows that username and its group → logout 204 → `/me` 401. All 7 rows identical in shape, on the clean stack and on the running stack |
| 3 | **CSRF.** `POST /api/v1/workpacks` as `time-method-analyst`: with `X-XSRF-TOKEN` **201**; without it **403** ("Your groups do not allow this call, or the CSRF token is missing") |
| 4 | **Deep link.** `GET /tasks/2251799813784803` and `/some/deep/link`: 200 `text/html` with the portal's root element. `GET /api/v1/nothing-here`: 401 `application/problem+json`, no `index.html` |
| 5 | CI: see the PR (six checks, read from `gh pr view`) |
| 6 | **Cookies.** The three `Set-Cookie` headers of a login (`XSRF-TOKEN; Path=/`, `JSESSIONID; Path=/; HttpOnly; SameSite=Lax`, `XSRF-TOKEN; Path=/`) are identical through :3000 and straight to :8081: no `Secure`, no domain, nothing rewritten |
| 7 | **Uploads.** A 2 MB file through :3000 as `production-team`: **201** with the app's own file-level outcome ("File cannot be read"), not nginx's 413. An ordinary `.xlsx`: **201** with row outcomes ("Work Pack not found" on the empty scratch database). After the review fixes, on the running stack: **7 MB** gets the app's own **413** `application/problem+json` ("Maximum upload size exceeded"), **9 MB** gets nginx's 413 `text/html`: the limit works as intended; between 1 MB and 8 MB the app decides. These two were rejected before anything is stored |
| 8 | **Backend address change.** `bpa-app` was stopped, its address taken by a throwaway container, and it came back on **172.18.0.9** (was .7). `GET :3000/api/v1/me` answered 502 for about 10 s (the app starting) and then **401 JSON** with no `frontend` restart (its `StartedAt` unchanged). A first attempt got the same address back (.7), so it proved nothing; this one did |
| 9 | **Vite workflow.** `git diff --stat main..HEAD`: no change to `vite.config.ts`, `package.json`, `package-lock.json` or `src/`. Only CLAUDE.md, README.md, docker-compose.yml, three new frontend files and this plan |

## Build notes
- **My mistake, recorded:** the branch already existed when I started (at 63d7b9c). `git switch -c` failed, my command chain went on, and the plan commit landed on local `main` (198df3b, not pushed). Ahsan stopped me; I cherry-picked it onto `BPA-80-portal-in-compose` (df55c24, the first commit of the branch) and set local `main` back to `origin/main` (63d7b9c). Nothing reached the remote. From then on each commit checks the branch name first.
- `docker compose up -d --build frontend` also rebuilds `bpa-app` (it is a dependency), so the first run takes a few minutes.
- The proof script's first run printed 000 for the ordinary `.xlsx` (a space in the path, my script); re-run correctly, 201.
- The clean-checkout run needed the host ports of the running stack: its containers were **stopped** (volumes kept), the scratch project was removed with `down -v` (its own volumes only), and the stack was started again; the database still holds 29 Work Packs.
- Checks before building (cookies, forward headers, hard-coded origins): none was true; confirmed at runtime by proof 2, 3 and 6. No backend or security change.

## Review (qa-reviewer at bdb3181, 11 Nov 2026) and what was done
No finding blocks the PR. Fixed in this branch: `server_tokens off` (the Server header no longer shows the version; checked), `proxy_set_header Connection ""` for HTTP/1.1 to the app, the upload-limit comment now says what was proved (7 MB and 9 MB checked above), "BuildKit required" in `Dockerfile.dockerignore`, and the README says to use the portal from `localhost` (plain http, session cookie not `Secure`) and that `/api` answers 502 for a few seconds on a cold start. Re-run after the fixes: 7 personas, deep links, `/api` not index.html, uploads, all as before.
Recorded, not changed (decisions for the PO, not for this Task):
- **Cost to every developer.** `frontend` is now part of plain `docker compose up -d --build`, so a failed npm or Docker Hub pull in the portal build stops the whole stack coming up, including backend-only work, and `up -d --build frontend` also rebuilds `bpa-app`. A compose profile or a CI image build would remove it (Q7, and Q8 below).
- **Q8 (new):** the port is published on all interfaces like the others. Binding `127.0.0.1:3000:80` would match 09 S24 and S26 but stops another machine (a QA machine on the LAN) reaching it. PO decision; not done.
- No security headers (CSP, X-Frame-Options) on a local-only plain-http stack: accepted.
- `location /api` is a prefix match (`/apifoo` also goes to the app): behaviour is right for real paths, accepted. `try_files` serves `index.html` for a missing static file (normal for a SPA).
- Tags are exact but not digest-pinned: accepted.
Questions Q1 to Q5 and Q7 were not answered in the session; they go to the PR description for Ahsan, Faraz and Khitaba. Q6 was done as written (stop, not remove; the plan was approved with that step).
