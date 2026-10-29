# BPA-25 spike kit: Camunda 8.10.1 calls behind the backend task API

Jira: BPA-25 (Spike, Epic 1 BPA-3). Owner: Ahsan / Usama. Prepared by Claude, 29 Oct 2026.
Sources: 09 S5, S6, S14, S15, S25; ADR 0004, 0005, 0007, 0008; BPA-40 decisions C2, C3.

Running this is a **human step** (L19): nothing in the Claude Project can reach your compose stack.
Claude wrote the kit and dry-ran it against a stub server. That proves the script's control flow
only, **not** Camunda's behaviour.

## What is in here

| File | Purpose |
|---|---|
| `run-bpa25.sh` | Runs checks 0-7, saves every request and response under `evidence/<run>/`, and writes `results.md` for Jira |
| `bpa25.http` | The same calls one at a time (VS Code REST Client or IntelliJ HTTP client), for poking at a failure |
| `models/bpa25-spike-task-api.bpmn` | Throwaway: user task (group `time-method-analyst`), save service task with a `WORKPACK_INVALID` loop-back, and a wait for `BPA25_AssignmentFileReceived` |
| `models/bpa25-spike-reassign.bpmn` | Throwaway: message start `BPA25_AssignmentFileReplaced`, which stands in for `Process_PPC_WorkerReassignment` |

The message names carry a `BPA25_` prefix so they can never start or correlate to a real Epic 1 instance on the same
cluster. Both models lint with 0 errors against `camunda-compat/camunda-cloud-8-10`. The only warnings are the missing
Camunda forms, which is intended (ADR 0004).

## Before you run

1. **Compose stack up on 8.10.1** (ADR 0001), REST API on `http://localhost:8080` or set `CAMUNDA_URL`.
2. **API protected, with authorizations on.** Otherwise checks 3 and 5 prove nothing; check 0a fails loudly if not.
   The docs give these settings: `CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=false` and
   `CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED=true` (confirm the exact variable names against the 8.10 compose bundle).
3. **Admin login.** Defaults to `demo/demo` (S29); override with `ADMIN_USER` / `ADMIN_PASSWORD`.
4. **Passwords.** Create `.env.bpa25` next to the script (git-ignored, never committed):

   ```bash
   DEMO_PASSWORD=<password for the spike persona bpa25-tma>
   BPA_SERVICE_PASSWORD=<password for bpa-service, as in your .env>
   ```

## Run

```bash
./run-bpa25.sh              # full run, about 1-3 minutes
./run-bpa25.sh --no-setup   # re-run checks only (users, authorizations and models already in place)
```

Setup creates groups `time-method-analyst` and `production-team`, users `bpa25-tma` and `bpa-service`, and their
authorizations, then deploys the two models. It is safe to re-run: 409 "already exists" is accepted.

## What each check proves

| # | Check | Called as | Verdict |
|---|---|---|---|
| 0a / 0b | API refuses unauthenticated calls; gateway is 8.10.x | none / admin | PASS/FAIL |
| 3a-c | `GET /v2/authentication/me` returns the user's groups; a wrong password gives 401; `bpa-service` can log in | user / svc | PASS/FAIL |
| 1a / 1b | `POST /v2/user-tasks/search` by `candidateGroup` finds the task; another group finds nothing | svc | PASS/FAIL |
| 2 | The user assigns the task to themselves and completes it with variables | user | PASS/FAIL, plus RECORD completer |
| 4 | The save job throws `WORKPACK_INVALID` with `validationMessages`; the task comes back and the variable is readable | svc | PASS/FAIL |
| 5a / 5b | `bpa-service` assigns to `bpa25-tma`, completes on their behalf, activates and completes the job, and correlates a message | svc | PASS/FAIL, plus RECORD completer |
| 6a | Does correlate accept a `messageId`? (reworded check 6) | svc | RECORD |
| 6b | The same `messageId` published twice at TTL 0 (original check 6; closes the ADR 0007 open point) | svc | RECORD |
| 7a | Correlate starts the reassignment and returns its instance key | svc | PASS/FAIL |
| 7b | A second correlate with the same key while that instance is active starts nothing: **exact status and body** | svc | PASS/FAIL, plus RECORD |
| 7c | Correlate with nothing waiting: **exact status and body** | svc | RECORD |
| 7d | Once the first instance has ended, the same key starts a new one (supports C3's "still ending" case) | svc | PASS/FAIL |

**RECORD rows are facts, not pass/fail.** The C2 and C3 rollback code branches on the 7b and 7c responses. D63 and the
choice of completer depend on rows 2 and 5b.

## After the run (about 10 minutes)

1. **Operate screenshots** of the two completed tasks: who is shown as completer for check 2 (user) and check 5
   (`bpa-service` on the user's behalf). No API here reliably answers that, so the screenshot is the evidence.
2. Paste `evidence/<run>/results.md` into a BPA-25 comment and attach the zipped `evidence/<run>/` folder.
   The evidence holds no passwords: request bodies are redacted before they are written.
3. Tell Claude in the Project. Claude then:
   - updates ADR 0007 (6a, 6b) and 04-conventions (the API calls and the minimum permissions);
   - removes "UNCONFIRMED" from the data model's B4 and C3 lines;
   - proposes the BPA-26 change for Faraz.

## If a check fails

| Symptom | Likely cause | What to do |
|---|---|---|
| 0a FAIL (200 without credentials) | API unprotected | Set the two security variables above, restart, re-run |
| 403 on a `bpa-service` call | Missing permission | Re-run with `EXTRA_SVC_PERMS=READ_PROCESS_INSTANCE` (or the permission named in the problem detail). The final list is the "minimum permissions that worked" for check 5 |
| 1a or 4 FAIL with 0 items or 0 jobs but status 200 | Search and job activation **filter** unauthorized results instead of refusing them (Camunda docs) | Treat as a permission problem, as above |
| 400 on any call | The 8.10.1 request shape differs from the 8.8+ docs the kit follows | Compare with the cluster's Swagger UI, fix the body in `bpa25.http` first, then in the script. That difference is itself a spike finding: record it |
| 1a FAIL after `POLL_SECONDS` | Search reads secondary storage and lags | Re-run with `POLL_SECONDS=60` |
| 3c FAIL, `bpa-service` already existed | Its password differs from `BPA_SERVICE_PASSWORD` | Reset it in Admin |
| 2 PASS but 5b shows `bpa-service` as completer | Camunda records the caller, not the assignee | **Decision for Faraz:** the backend's own "done by" columns (D63) become the record of the real user. Raise it on BPA-25 |

## Limits

- **Least privilege is shown, not proven.** The script grants a candidate set and reports whether every call worked.
  To prove each permission is needed, remove one and re-run. That is optional and manual.
- **REST only.** The walking skeleton redoes these checks as JUnit with the Spring SDK (L18).
- **Users.** `bpa25-tma` is spike-only; delete it in Admin afterwards if you like. `bpa-service` is the real
  technical user (S25). The S22 startup job will become its only writer (L2), so the job must accept that the user
  already exists.
- **Repo location.** L1 (ADR 0003) has no place for spikes. Suggested:
  `intent/BPA-3-work-pack-assignment/spikes/BPA-25/`, with `evidence/` and `.env.bpa25` git-ignored. This is
  Ahsan's call.
