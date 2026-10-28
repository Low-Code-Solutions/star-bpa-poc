# Plan: BPA-36 Commit Epic 1 models with 09 fixes, bpmnlint config and lint CI; form specs to docs

Jira: BPA-36 (Task, epic BPA-3)
Story last updated: 2026-10-28T19:53:17.498+0400
Status: Approved (Ahsan, 2026-10-28 21:55 PKT, in the claude.ai session)
Approver: Ahsan (Build Lead)
Branch: BPA-36-commit-epic1-models, from main ff4ea65

## Readiness check (Task, so a light check)
- Goal, changes and done criteria stated in Jira: yes.
- Decisions behind it: 09 L1 (paths), L10 (assignment_state documentation, `.bpmnlintrc`, pinned
  plugin versions, CI uses the same file), S14 (no form links; `.form` files are field specs only),
  G1 (Camunda 8.10.1). BPA-3 records the S13 Desktop Modeler review as done on 28 Oct, no gaps.
- Blocking questions: none. Q1 is how the files reach the repo (same route as BPA-35).

## Sources (SharePoint "04 Requirements snapshots/Epic 1 design 2026-10-27/models/")

| File | Size (SharePoint) | Repo path |
|---|---|---|
| bpmn/Process_PPC_WorkPackAssignment.bpmn | 39,828 B | models/bpmn/Process_PPC_WorkPackAssignment.bpmn |
| bpmn/Process_PPC_WorkerReassignment.bpmn | 6,577 B | models/bpmn/Process_PPC_WorkerReassignment.bpmn |
| bpmn/Process_PPC_DrawingRevisionNotice.bpmn | 7,533 B | models/bpmn/Process_PPC_DrawingRevisionNotice.bpmn |
| dmn/Decision_WorkpackReleaseCheck.dmn | 3,325 B | models/dmn/Decision_WorkpackReleaseCheck.dmn |
| forms/Form_CreateWorkPack.form | 6,021 B | docs/form-specs/Form_CreateWorkPack.form |
| forms/Form_ConfirmWorkPack.form | 6,358 B | docs/form-specs/Form_ConfirmWorkPack.form |
| forms/Form_SignOffRelease.form | 1,992 B | docs/form-specs/Form_SignOffRelease.form |
| forms/Form_ScheduleWorkPack.form | 2,541 B | docs/form-specs/Form_ScheduleWorkPack.form |

## Files that change

| File | Change |
|---|---|
| The 8 files above | New, committed byte-for-byte from SharePoint (sizes checked). Model content changes only if a 09 §4 item 2 check fails, and then only that fix, listed here |
| models/bpmn/.gitkeep, models/dmn/.gitkeep, docs/form-specs/.gitkeep | Deleted once the folders have files |
| .bpmnlintrc | New: extends `bpmnlint:recommended` and `plugin:camunda-compat/camunda-cloud-8-10` (L10) |
| package.json, package-lock.json | New: devDependencies pinned exactly: `bpmnlint` 12.0.0, `bpmnlint-plugin-camunda-compat` 2.63.0; script `lint:bpmn` |
| .github/workflows/lint.yml | New: on every pull request and push to main, `npm ci` then `npm run lint:bpmn` over `models/**/*.bpmn`; Node 22 |
| .github/workflows/.gitkeep | Deleted |
| docs/form-specs/README.md | New, three lines: these are field specs for the React screens (09 S14); nothing deploys them |
| .gitignore | Already ignores `node_modules/`; no change expected |
| intent/BPA-3-work-pack-assignment/plans/BPA-36.md, intent/README.md | This plan; regenerated index |

## Order of work
1. This plan (Draft), approval recorded.
2. Files arrive on the branch (Q1). Check sizes against the table.
3. 09 §4 item 2 checks on the models, scripted:
   - no `zeebe:formDefinition` / `zeebe:userTaskForm` on any user task (S14);
   - no `ServiceTask_LoadReferenceData` or `ServiceTask_LoadScheduleContext` (S14 drops G12);
   - `modeler:executionPlatformVersion="8.10.1"` on every model (G1);
   - `bpmn:documentation` naming the transition on every element that changes `assignment_state`
     (data-model §8: generate-workpack-print, validate-worker-assignment, create-digital-timesheets;
     replace-worker-assignment in the reassignment model) (L10).
   Any failure: the minimal fix, listed in this plan.
4. `.bpmnlintrc`, `package.json`, lock file; run lint locally until clean (or record intended warnings).
5. Element-ID check: every element ID named in the BPMN Reference field of the Epic 1 stories
   (Jira BPA-6 to 16, 22, 23, 26) exists in the models; every flow node is referenced by a story.
   Run once from a scratch script; output pasted in the PR (not committed, since it reads Jira).
6. CI workflow; form specs and README.
7. Checks, qa-reviewer pass, PR with plan Approved; final commit sets it Closed before merge.

## Risks
- The SharePoint snapshot is not the latest model. Mitigation: the step-3 checks prove the 09 fixes
  are in; BPA-3 (v1.0, 28 Oct) records the models as updated per 09 and reviewed in Modeler (S13).
- Lint warnings that S14 intends (user tasks without forms: BPA-3 notes 4 such warnings). Mitigation:
  keep the rule set as L10 says; record the 4 warnings as expected rather than switching rules off.
  If CI must pass with them, they are warnings, not errors.
- Plugin versions drift. Mitigation: exact pins and a committed lock file (L10).

## Proof
- Byte sizes match the table.
- Step-3 checks: output pasted in the PR.
- `npm run lint:bpmn`: 0 errors (expected warnings listed).
- Element-ID check: 0 missing IDs; count of flow nodes referenced.
- Hook tests still pass.

## Questions
- Q1: how do the 8 files reach the branch? Recommended, as for BPA-35: you download them from
  SharePoint and commit them to `BPA-36-commit-epic1-models` at the repo paths in the table (a commit
  message starting `BPA-36:` keeps the history clean). Alternative: I read them through the
  SharePoint connector and write them out, with the byte sizes as the check.

## Human-gap log
- 28 Oct: plan approved by Ahsan in chat (21:55 PKT). Q1: the recommended route (Ahsan commits the 8 files)
  is being followed; tooling built meanwhile.
