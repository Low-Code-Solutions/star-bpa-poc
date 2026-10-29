# Plan BPA-25: Spike, confirm the Camunda 8.10.1 task and identity API calls

- Status: Approved (Ahsan, 29 Oct 2026)
- Jira: BPA-25 (Spike, Epic BPA-3). Branch: `BPA-25-spike`
- Owner: Ahsan / Usama. Plan prepared by Claude (BA), 29 Oct 2026
- Sources: Jira BPA-25 description and comments (checks 6 and 7); 09 S5, S6, S14, S15, S25; ADR 0004, 0005,
  0007, 0008; BPA-40 decisions C2 and C3

## Readiness check

| Item | State |
|---|---|
| Story Ready in Jira | Yes (Faraz, 28 Oct 2026) |
| Checks defined | 1–5 in the description; 6 and 7 proposed in comments (29 Oct). Check 6 reworded after C3 (correlate replaces publish): 6a "does correlate accept `messageId`", 6b the original TTL-0 duplicate publish. Jira update pending PO confirmation |
| Stack | Camunda 8.10.1 compose on localhost:8080, API protected (0a = 401 confirmed 29 Oct) |
| App needed | No: the checks call the Camunda REST API directly |

## Check-to-evidence table

The kit's script runs each check, which saves its own request/response evidence (`evidence/<run>/NN-<label>.json`).

| Check | What it proves | Verdict |
|---|---|---|
| 0a / 0b | API refuses unauthenticated calls; gateway is 8.10.x | PASS/FAIL |
| 1a / 1b | `POST /v2/user-tasks/search` by `candidateGroup` finds the task; another group sees nothing | PASS/FAIL |
| 2 | User assigns the task to themselves and completes it with variables | PASS/FAIL, RECORD completer |
| 3a–c | `GET /v2/authentication/me` returns groups; wrong password is 401; `bpa-service` logs in | PASS/FAIL |
| 4 | Save job throws `WORKPACK_INVALID`; the task returns with `validationMessages` readable | PASS/FAIL |
| 5a / 5b | `bpa-service` assigns to another user, completes on their behalf, activates and completes a job, correlates a message; minimum permissions listed | PASS/FAIL, RECORD completer |
| 6a / 6b | Correlate with `messageId`; the same `messageId` published twice at TTL 0 | RECORD |
| 7a–7d | Correlate starts a message-start instance; a second correlate while it is active starts nothing (exact response); correlate with nothing waiting (exact response); the key is free once the instance ends | PASS/FAIL, RECORD |

## Files

All under `intent/BPA-3-work-pack-assignment/spikes/BPA-25/`:

- `run-bpa25.sh`: the runner.
- `bpa25.http`: single calls for manual retries.
- `models/bpa25-spike-task-api.bpmn` and `models/bpa25-spike-reassign.bpmn`: throwaway models; message names carry a `BPA25_` prefix so they cannot correlate to real Epic 1 instances.
- `README.md` and `.gitignore`.

Not committed: `evidence/` and `.env.bpa25`, both git-ignored.

No change to `models/`, `backend/`, `docker-compose.yml` or `.env.example`.

## Order

1. Commit this plan (first commit on the branch).
2. Fix `run-bpa25.sh` for Git Bash: with `MSYS_NO_PATHCONV=1`, `mktemp` paths under `/tmp` cannot be written by `curl.exe` (every call reports 000). Create temp files with relative paths inside `$EVID` instead.
3. Run `bash -n` and shellcheck.
4. Run `./run-bpa25.sh` against the stack.
5. Resolve FAILs:
   - On a 403, add the permission through `EXTRA_SVC_PERMS`.
   - On a 400, correct the request shape in the script and `.http` file, and record it as a finding.
6. Take Operate screenshots of the completed tasks for checks 2 and 5.
7. Commit the kit. Post `results.md`, the zipped evidence and the screenshots to BPA-25.
8. Claude (Project) updates ADR 0007, 04-conventions and the data model's UNCONFIRMED marks.
9. Set this plan to `Status: Closed`.

## Risks

- **Completer:** Camunda may record `bpa-service`, not the real user, as completer (5b). That needs a PO decision on D63, so it goes back to Faraz, not to a workaround.
- **Request shapes:** 8.10.1 may differ from the 8.8 docs the kit follows. Each difference is a finding to record, not to hide.
- **Minimum permissions:** least privilege is shown for the set used, not proven minimal. Proving it would mean removing one permission at a time, which is optional.

## Proof

- `results.md` with every row PASS or RECORD, or each FAIL explained.
- The evidence folder.
- Two Operate screenshots.

## Questions

- Q1. Will the PO accept the reworded check 6 (6a/6b) and the added 7d? This is pending Jira confirmation.
- Q2. Where do spikes live under L1 (ADR 0003)? This plan uses `intent/<epic>/spikes/`; Ahsan to confirm.

## Human-gap log

- 29 Oct: Camunda had no `demo/demo` admin. `docker-compose.yml` creates no initial user, although ADR 0005 (S29) assumes one. The admin was created by hand in the browser setup screen.
- 29 Oct: 8.10.1 rejected test-user emails on the `.invalid` domain. The kit was fixed to use `example.com`.
- 29 Oct: Git Bash path rewriting broke the evidence paths and, once disabled, broke the temp files. The script was fixed twice.
- 29 Oct: `.claude/hooks/require-plan.sh` blocked every write on this branch from Claude Code on Windows, including writing this plan. The likely cause is that it does not recognise `d:\` or `d:/` paths as absolute. The plan was written and committed by hand. A bug is proposed against BPA-32.
- Running the checks against the stack is a human step (L19).