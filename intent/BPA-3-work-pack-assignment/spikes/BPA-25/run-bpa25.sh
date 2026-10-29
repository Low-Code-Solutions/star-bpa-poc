#!/usr/bin/env bash
# =============================================================================
# BPA-25 spike runner: confirm the Camunda 8.10.1 calls behind the backend task API.
#
# Runs checks 0-7 against a running compose stack, saves every request/response
# as evidence (passwords are never written), and builds results.md to paste into
# Jira BPA-25.
#
# Usage:   ./run-bpa25.sh              (all checks)
#          ./run-bpa25.sh --no-setup   (skip users/groups/authorizations/deploy)
#
# Needs:   bash, curl, jq. Settings come from the environment or ./.env.bpa25:
#   CAMUNDA_URL           default http://localhost:8080
#   ADMIN_USER/PASSWORD   default demo/demo (ADR 0005, S29)
#   DEMO_PASSWORD         required: password for the spike persona bpa25-tma
#   BPA_SERVICE_PASSWORD  required: password for bpa-service (ADR 0005, S25)
#   EXTRA_SVC_PERMS       optional, comma-separated PROCESS_DEFINITION permissions
#                         to add to bpa-service if a check reports 403
#   POLL_SECONDS          default 30 (search endpoints are eventually consistent)
#
# THROWAWAY: the models and the bpa25-* user exist only for this spike.
# Endpoint shapes follow the 8.8+ Orchestration Cluster REST API (v2) docs and are
# exactly what this spike is meant to confirm on 8.10.1; read the evidence files
# when a call returns 400.
# =============================================================================
set -uo pipefail
# Git Bash on Windows rewrites arguments that start with "/" into Windows paths when it
# calls native tools such as jq.exe. That turned "/v2/users" into "C:/Program Files/Git/v2/users"
# in the evidence files. Switch the rewriting off.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")" || exit 2
ENV_FILE="${ENV_FILE:-.env.bpa25}"
# Strip Windows line endings: a trailing \r would silently become part of each password.
# shellcheck disable=SC1090
[[ -f "$ENV_FILE" ]] && source <(tr -d '\r' <"$ENV_FILE")

CAMUNDA_URL="${CAMUNDA_URL:-http://localhost:8080}"
ADMIN_USER="${ADMIN_USER:-demo}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-demo}"
POLL_SECONDS="${POLL_SECONDS:-30}"
EXTRA_SVC_PERMS="${EXTRA_SVC_PERMS:-}"
DO_SETUP=1
[[ "${1:-}" == "--no-setup" ]] && DO_SETUP=0

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 2; }
done
: "${DEMO_PASSWORD:?Set DEMO_PASSWORD (env or $ENV_FILE)}"
: "${BPA_SERVICE_PASSWORD:?Set BPA_SERVICE_PASSWORD (env or $ENV_FILE)}"

SVC_USER="bpa-service"
TMA_USER="bpa25-tma"
TASK_PROC="Process_Spike_BPA25_TaskApi"
REASSIGN_PROC="Process_Spike_BPA25_Reassign"
MSG_RECEIVED="BPA25_AssignmentFileReceived"
MSG_REPLACED="BPA25_AssignmentFileReplaced"

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
EVID="evidence/$RUN_ID"
mkdir -p "$EVID"
RESULTS="$EVID/results.md"
SEQ=0
STATUS=""
BODY=""
LAST_FILE=""
declare -a VERDICTS=()

# --------------------------------------------------------------------------- helpers

creds() {
  case "$1" in
    admin)  printf '%s:%s' "$ADMIN_USER" "$ADMIN_PASSWORD" ;;
    svc)    printf '%s:%s' "$SVC_USER" "$BPA_SERVICE_PASSWORD" ;;
    tma)    printf '%s:%s' "$TMA_USER" "$DEMO_PASSWORD" ;;
    tmabad) printf '%s:%s' "$TMA_USER" "not-the-password-bpa25" ;;
    none)   printf '' ;;
    *) echo "unknown identity $1" >&2; return 1 ;;
  esac
}

# api <label> <identity> <METHOD> <path> [json-body]
# Sets STATUS, BODY, LAST_FILE. Never writes credentials.
api() {
  local label="$1" who="$2" method="$3" path="$4" body="${5:-}"
  # Relative temp file: with path conversion off, native curl.exe cannot write to /tmp/...
  local tmp; tmp="$(mktemp -p "$EVID" .resp.XXXXXX)"
  local -a args=(-sS -o "$tmp" -w '%{http_code}' -X "$method" "$CAMUNDA_URL$path"
                 -H 'Accept: application/json')
  local c; c="$(creds "$who")"
  [[ -n "$c" ]] && args+=(-u "$c")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  STATUS="$(curl "${args[@]}" 2>"$tmp.err")" || STATUS="000"
  BODY="$(cat "$tmp")"
  [[ "$STATUS" == "000" ]] && BODY="curl error: $(cat "$tmp.err")"
  rm -f "$tmp" "$tmp.err"
  SEQ=$((SEQ + 1))
  LAST_FILE="$EVID/$(printf '%02d' "$SEQ")-$label.json"
  local req_body=null resp_body
  if [[ -n "$body" ]]; then
    # Redact before anything reaches disk.
    req_body="$(jq -c 'if type=="object" and has("password") then .password="***" else . end' <<<"$body" 2>/dev/null \
                || jq -n --arg b "$body" '$b')"
  fi
  if [[ -z "${BODY//[[:space:]]/}" ]]; then
    resp_body=null   # 204 and other empty bodies
  else
    resp_body="$(jq -c . <<<"$BODY" 2>/dev/null || jq -n --arg b "$BODY" '$b')"
  fi
  jq -n --arg label "$label" --arg as "$who" --arg m "$method" --arg p "$path" \
        --argjson rb "$req_body" --arg s "$STATUS" --argjson resp "$resp_body" \
        '{label:$label, request:{as:$as, method:$m, path:$p, body:$rb}, response:{status:($s|tonumber? // $s), body:$resp}}' \
        >"$LAST_FILE"
  printf '  %-6s %-48s as %-7s -> %s\n' "$method" "$path" "$who" "$STATUS" >&2
}

deploy() {
  local file="$1" tmp; tmp="$(mktemp -p "$EVID" .resp.XXXXXX)"
  STATUS="$(curl -sS -o "$tmp" -w '%{http_code}' -u "$(creds admin)" \
            -X POST "$CAMUNDA_URL/v2/deployments" -F "resources=@$file" 2>/dev/null)" || STATUS="000"
  BODY="$(cat "$tmp")"; rm -f "$tmp"
  SEQ=$((SEQ + 1))
  LAST_FILE="$EVID/$(printf '%02d' "$SEQ")-deploy-$(basename "$file" .bpmn).json"
  jq -n --arg f "$(basename "$file")" --arg s "$STATUS" --arg b "$BODY" \
     '{label:"deploy", request:{as:"admin", method:"POST", path:"/v2/deployments", file:$f},
       response:{status:($s|tonumber? // $s), body:($b|fromjson? // $b)}}' >"$LAST_FILE"
  printf '  %-6s %-48s as %-7s -> %s\n' POST "/v2/deployments ($(basename "$file"))" admin "$STATUS" >&2
}

ok() { [[ "$STATUS" =~ ^2 ]]; }
jget() { jq -r "$1 // empty" <<<"$BODY" 2>/dev/null; }

# verdict <check> <PASS|FAIL|RECORD|BLOCKED> <note> [evidence-file]
verdict() {
  local file="${4:-$LAST_FILE}"
  VERDICTS+=("| $1 | **$2** | $3 | \`$(basename "$file")\` |")
  printf '  => %s %s: %s\n' "$1" "$2" "$3"
}

# poll <seconds> <function>; the function returns 0 when done
poll() {
  local limit="$1" fn="$2" waited=0
  while (( waited < limit )); do
    "$fn" && return 0
    sleep 2; waited=$((waited + 2))
  done
  return 1
}

section() { printf '\n== %s\n' "$1"; }

# --------------------------------------------------------------------------- 0. preflight

section "0. Preflight"
api preflight-unauth none GET /v2/topology
if [[ "$STATUS" == "401" ]]; then
  verdict "0a API protected" PASS "Unauthenticated call refused (401)"
else
  verdict "0a API protected" FAIL "Unauthenticated call returned $STATUS; set unprotected-api=false and authorizations enabled, otherwise checks 3 and 5 prove nothing"
fi
api preflight-topology admin GET /v2/topology
VERSION="$(jget '.gatewayVersion')"
if ok && [[ "$VERSION" == 8.10* ]]; then
  verdict "0b Version" PASS "gatewayVersion $VERSION"
else
  verdict "0b Version" FAIL "status $STATUS, gatewayVersion '${VERSION:-unknown}' (expected 8.10.x)"
fi
if [[ "$STATUS" == "401" || "$STATUS" == "000" ]]; then
  echo
  if [[ "$STATUS" == "401" ]]; then
    echo "STOPPED: Camunda rejected the admin login '$ADMIN_USER'. Nothing else can run."
    echo "Set ADMIN_USER / ADMIN_PASSWORD in $ENV_FILE to your stack's admin user, then re-run."
  else
    echo "STOPPED: cannot reach $CAMUNDA_URL. Is the compose stack up?"
  fi
  rm -rf "$EVID"   # this run has no usable evidence
  exit 1
fi

# --------------------------------------------------------------------------- setup

if (( DO_SETUP )); then
  section "Setup (admin): groups, users, memberships, authorizations, models"
  for g in time-method-analyst production-team; do
    api "setup-group-$g" admin POST /v2/groups "$(jq -nc --arg g "$g" '{groupId:$g, name:$g}')"
    ok || [[ "$STATUS" == "409" ]] || echo "  ! group $g not created; see $LAST_FILE"
  done
  api setup-user-tma admin POST /v2/users \
      "$(jq -nc --arg u "$TMA_USER" --arg p "$DEMO_PASSWORD" '{username:$u, password:$p, name:"BPA-25 spike analyst", email:"bpa25-tma@example.com"}')"
  ok || [[ "$STATUS" == "409" ]] || echo "  ! user $TMA_USER not created; see $LAST_FILE"
  api setup-user-svc admin POST /v2/users \
      "$(jq -nc --arg u "$SVC_USER" --arg p "$BPA_SERVICE_PASSWORD" '{username:$u, password:$p, name:"BPA service (technical)", email:"bpa-service@example.com"}')"
  ok || [[ "$STATUS" == "409" ]] || echo "  ! user $SVC_USER not created; see $LAST_FILE"
  [[ "$STATUS" == "409" ]] && echo "  i $SVC_USER already exists: its password must match BPA_SERVICE_PASSWORD"
  # Without both users every later check fails with 401/404, so stop here with the reason.
  for f in "$EVID"/*-setup-user-tma.json "$EVID"/*-setup-user-svc.json; do
    s="$(jq -r '.response.status' "$f")"
    if [[ ! "$s" =~ ^2 && "$s" != "409" ]]; then
      echo
      echo "STOPPED: user creation failed ($s): $(jq -r '.response.body.detail // .response.body' "$f")"
      echo "Evidence: $f"
      exit 1
    fi
  done

  api setup-member-tma admin PUT "/v2/groups/time-method-analyst/users/$TMA_USER"
  ok || [[ "$STATUS" == "409" ]] || echo "  ! membership not created; see $LAST_FILE"

  # bpa-service: candidate least-privilege set (ADR 0005 S25). Scoped to the two spike processes.
  SVC_PD_PERMS='["READ_USER_TASK","UPDATE_USER_TASK","COMPLETE_USER_TASK","UPDATE_PROCESS_INSTANCE"]'
  if [[ -n "$EXTRA_SVC_PERMS" ]]; then
    SVC_PD_PERMS="$(jq -c --arg x "$EXTRA_SVC_PERMS" '. + ($x|split(",")|map(gsub(" ";""))) | unique' <<<"$SVC_PD_PERMS")"
  fi
  for proc in "$TASK_PROC" "$REASSIGN_PROC"; do
    api "setup-auth-svc-$proc" admin POST /v2/authorizations \
        "$(jq -nc --arg o "$SVC_USER" --arg r "$proc" --argjson p "$SVC_PD_PERMS" \
           '{ownerId:$o, ownerType:"USER", resourceType:"PROCESS_DEFINITION", resourceId:$r, permissionTypes:$p}')"
    ok || echo "  ! authorization not created; see $LAST_FILE"
  done
  api setup-auth-svc-message admin POST /v2/authorizations \
      "$(jq -nc --arg o "$SVC_USER" '{ownerId:$o, ownerType:"USER", resourceType:"MESSAGE", resourceId:"*", permissionTypes:["CREATE"]}')"
  ok || echo "  ! authorization not created; see $LAST_FILE"
  # Spike persona: only what check 2 (user acting for themselves) needs.
  api setup-auth-tma admin POST /v2/authorizations \
      "$(jq -nc --arg o "$TMA_USER" --arg r "$TASK_PROC" \
         '{ownerId:$o, ownerType:"USER", resourceType:"PROCESS_DEFINITION", resourceId:$r, permissionTypes:["READ_USER_TASK","UPDATE_USER_TASK","COMPLETE_USER_TASK"]}')"
  ok || echo "  ! authorization not created; see $LAST_FILE"

  deploy models/bpa25-spike-task-api.bpmn
  ok || { verdict "Setup" BLOCKED "Deploy of task-api model failed ($STATUS)"; }
  deploy models/bpa25-spike-reassign.bpmn
  ok || { verdict "Setup" BLOCKED "Deploy of reassign model failed ($STATUS)"; }
  echo "  bpa-service PROCESS_DEFINITION permissions used: $SVC_PD_PERMS; MESSAGE: CREATE"
fi

# --------------------------------------------------------------------------- 3. login and groups

section "3. Read the user's groups at login (basic auth)"
api check3-me-tma tma GET /v2/authentication/me
if ok && jq -e '.groups | index("time-method-analyst")' <<<"$BODY" >/dev/null 2>&1; then
  verdict "3a Groups at login" PASS "groups = $(jq -c '.groups' <<<"$BODY")"
else
  verdict "3a Groups at login" FAIL "status $STATUS; groups not returned as expected"
fi
api check3-me-badpw tmabad GET /v2/authentication/me
if [[ "$STATUS" == "401" ]]; then
  verdict "3b Wrong password" PASS "401, so the backend can refuse the login"
else
  verdict "3b Wrong password" FAIL "expected 401, got $STATUS"
fi
api check3-me-svc svc GET /v2/authentication/me
ok && verdict "3c bpa-service login" PASS "groups = $(jq -c '.groups' <<<"$BODY")" \
   || verdict "3c bpa-service login" FAIL "status $STATUS"

# --------------------------------------------------------------------------- start an instance

section "Start a spike instance (admin)"
WP="BPA25-WP-$RUN_ID"
api start-task-instance admin POST /v2/process-instances \
    "$(jq -nc --arg p "$TASK_PROC" --arg wp "$WP" '{processDefinitionId:$p, variables:{workpackNumber:$wp}}')"
PI="$(jget '.processInstanceKey')"
if [[ -z "$PI" ]]; then
  verdict "Setup" BLOCKED "Could not start $TASK_PROC (status $STATUS); checks 1, 2, 4, 5 skipped"
fi

# --------------------------------------------------------------------------- 1. search by candidate group

TASK1=""
find_task1() {
  api check1-search-by-group svc POST /v2/user-tasks/search \
      "$(jq -nc --arg pi "$PI" '{filter:{candidateGroup:"time-method-analyst", processInstanceKey:$pi, state:"CREATED"}}')"
  TASK1="$(jget '.items[0].userTaskKey')"
  [[ -n "$TASK1" ]]
}
if [[ -n "$PI" ]]; then
  section "1. Search user tasks by candidate group"
  if poll "$POLL_SECONDS" find_task1; then
    verdict "1a Search by group" PASS "task $TASK1 found under candidateGroup=time-method-analyst"
  else
    verdict "1a Search by group" FAIL "no task found within ${POLL_SECONDS}s (last status $STATUS)"
  fi
  api check1-search-other-group svc POST /v2/user-tasks/search \
      "$(jq -nc --arg pi "$PI" '{filter:{candidateGroup:"project-engineer", processInstanceKey:$pi, state:"CREATED"}}')"
  if ok && [[ "$(jget '.items | length')" == "0" ]]; then
    verdict "1b Other group sees nothing" PASS "candidateGroup=project-engineer returns 0 items"
  else
    verdict "1b Other group sees nothing" FAIL "status $STATUS, items $(jget '.items | length')"
  fi
fi

# --------------------------------------------------------------------------- 2. assign then complete (user acts)

if [[ -n "$TASK1" ]]; then
  section "2. Assign to the logged-in user, then complete with variables (user calls)"
  api check2-assign tma POST "/v2/user-tasks/$TASK1/assignment" \
      "$(jq -nc --arg u "$TMA_USER" '{assignee:$u, allowOverride:true}')"
  A2="$STATUS"
  api check2-complete tma POST "/v2/user-tasks/$TASK1/completion" \
      '{"variables":{"estimatedManHours":12.5,"spikeNote":"BPA-25 check 2"}}'
  C2="$STATUS"
  read_task1() { api check2-read-task admin GET "/v2/user-tasks/$TASK1"; [[ "$(jget '.state')" == "COMPLETED" ]]; }
  poll "$POLL_SECONDS" read_task1
  ASSIGNEE2="$(jget '.assignee')"; STATE2="$(jget '.state')"
  if [[ "$A2" =~ ^2 && "$C2" =~ ^2 && "$STATE2" == "COMPLETED" && "$ASSIGNEE2" == "$TMA_USER" ]]; then
    verdict "2 Assign + complete (user)" PASS "assign $A2, complete $C2; task state $STATE2, assignee $ASSIGNEE2"
  else
    verdict "2 Assign + complete (user)" FAIL "assign $A2, complete $C2; task state '${STATE2}', assignee '${ASSIGNEE2}'"
  fi
  api check2-audit-log admin POST /v2/audit-logs/search \
      "$(jq -nc --arg pi "$PI" '{filter:{processInstanceKey:$pi}}')"
  verdict "2 Completer recorded" RECORD "audit-log search status $STATUS; read who is recorded as actor, then confirm in Operate (screenshot)"
fi

# --------------------------------------------------------------------------- 4. BPMN error loop-back

JOB=""
TASK2=""
JOB_LABEL="check4-activate"
activate_job() {
  api "$JOB_LABEL" svc POST /v2/jobs/activation \
      '{"type":"bpa25-save-workpack","worker":"bpa25-runner","timeout":60000,"maxJobsToActivate":10,"requestTimeout":2000}'
  JOB="$(jq -r --arg pi "$PI" '.jobs[]? | select((.processInstanceKey|tostring)==$pi) | .jobKey' <<<"$BODY" 2>/dev/null | head -n1)"
  [[ -n "$JOB" ]]
}
find_task2() {
  api check4-find-new-task svc POST /v2/user-tasks/search \
      "$(jq -nc --arg pi "$PI" '{filter:{processInstanceKey:$pi, state:"CREATED", elementId:"UserTask_Spike_EditWorkPack"}}')"
  TASK2="$(jget '.items[0].userTaskKey')"
  [[ -n "$TASK2" && "$TASK2" != "$TASK1" ]]
}
read_vm() {
  api check4-task-variables svc POST "/v2/user-tasks/$TASK2/variables/search" '{}'
  if ok; then
    jq -e '.items[]? | select(.name=="validationMessages") | .value | contains("Phase is required")' <<<"$BODY" >/dev/null 2>&1 && return 0
  fi
  api check4-variables-fallback svc POST /v2/variables/search \
      "$(jq -nc --arg pi "$PI" '{filter:{processInstanceKey:$pi, name:"validationMessages"}}')"
  ok && jq -e '.items[]? | .value | contains("Phase is required")' <<<"$BODY" >/dev/null 2>&1
}
if [[ -n "$TASK1" ]]; then
  section "4. Save worker throws WORKPACK_INVALID; task returns with validationMessages"
  if poll "$POLL_SECONDS" activate_job; then
    api check4-throw-error svc POST "/v2/jobs/$JOB/error" \
        '{"errorCode":"WORKPACK_INVALID","errorMessage":"BPA-25 spike","variables":{"validationMessages":[{"field":"phase","message":"Phase is required"}]}}'
    TE="$STATUS"
    if poll "$POLL_SECONDS" find_task2; then
      if poll "$POLL_SECONDS" read_vm; then
        verdict "4 BPMN error loop-back" PASS "throw $TE; new task $TASK2; validationMessages readable via $(jq -r '.request.path' "$LAST_FILE")"
      else
        verdict "4 BPMN error loop-back" FAIL "throw $TE; task $TASK2 returned but validationMessages not readable (status $STATUS; 403 means bpa-service needs another permission, try EXTRA_SVC_PERMS=READ_PROCESS_INSTANCE)"
      fi
    else
      verdict "4 BPMN error loop-back" FAIL "throw $TE; no new user task within ${POLL_SECONDS}s"
    fi
  else
    verdict "4 BPMN error loop-back" FAIL "no job activated for instance $PI (activation filters unauthorized jobs silently; check UPDATE_PROCESS_INSTANCE)"
  fi
fi

# --------------------------------------------------------------------------- 5. bpa-service on behalf

if [[ -n "$TASK2" ]]; then
  section "5. bpa-service assigns to another user and completes on their behalf"
  api check5-assign-other svc POST "/v2/user-tasks/$TASK2/assignment" \
      "$(jq -nc --arg u "$TMA_USER" '{assignee:$u, allowOverride:true}')"
  A5="$STATUS"
  api check5-complete-on-behalf svc POST "/v2/user-tasks/$TASK2/completion" \
      '{"variables":{"estimatedManHours":14,"spikeNote":"BPA-25 check 5"}}'
  C5="$STATUS"
  read_task2() { api check5-read-task admin GET "/v2/user-tasks/$TASK2"; [[ "$(jget '.state')" == "COMPLETED" ]]; }
  poll "$POLL_SECONDS" read_task2
  ASSIGNEE5="$(jget '.assignee')"
  JOB=""
  JOB_LABEL="check5-activate"
  poll "$POLL_SECONDS" activate_job
  J5="${JOB:-none}"
  [[ -n "$JOB" ]] && api check5-complete-job svc POST "/v2/jobs/$JOB/completion" '{"variables":{}}'
  JC5="$STATUS"
  # Positive correlate to the waiting catch event (also covers "publish a message" for bpa-service).
  M5=""
  correlate_wp() {
    api check5-correlate-received svc POST /v2/messages/correlation \
        "$(jq -nc --arg n "$MSG_RECEIVED" --arg k "$WP" '{name:$n, correlationKey:$k, variables:{}}')"
    M5="$STATUS"; ok
  }
  poll 10 correlate_wp
  if [[ "$A5" =~ ^2 && "$C5" =~ ^2 && "$J5" != "none" && "$JC5" =~ ^2 && "$M5" =~ ^2 ]]; then
    verdict "5a bpa-service calls" PASS "assign $A5, complete $C5, job activated + completed $JC5, correlate $M5"
  else
    verdict "5a bpa-service calls" FAIL "assign $A5, complete $C5, job ${J5}, job completion ${JC5}, correlate $M5"
  fi
  api check5-audit-log admin POST /v2/audit-logs/search \
      "$(jq -nc --arg pi "$PI" '{filter:{processInstanceKey:$pi}}')"
  verdict "5b Completer when bpa-service completes" RECORD "task assignee '$ASSIGNEE5'; read the actor in this audit-log response and in Operate: real user ($TMA_USER) or $SVC_USER?"
fi

# --------------------------------------------------------------------------- 6. message ID (reworded)

section "6. Message ID with correlate and with TTL-0 publish"
api check6a-correlate-with-messageid svc POST /v2/messages/correlation \
    "$(jq -nc --arg n "$MSG_RECEIVED" --arg k "BPA25-NOWAIT-6a-$RUN_ID" '{name:$n, correlationKey:$k, messageId:("sha:"+$k), variables:{}}')"
verdict "6a Correlate accepts messageId?" RECORD "status $STATUS (400 = field rejected; otherwise read whether it was ignored)"
MID="sha256-bpa25:BPA25-NOWAIT-6b-$RUN_ID"
declare -a P6=()
for n in 1 2; do
  api "check6b-publish-ttl0-$n" svc POST /v2/messages/publication \
      "$(jq -nc --arg nm "$MSG_RECEIVED" --arg k "BPA25-NOWAIT-6b-$RUN_ID" --arg id "$MID" '{name:$nm, correlationKey:$k, timeToLive:0, messageId:$id, variables:{}}')"
  P6+=("$STATUS")
done
verdict "6b Same messageId twice at TTL 0" RECORD "first ${P6[0]}, second ${P6[1]} (both 2xx = the ID does not stop a second publish; closes the ADR 0007 open point)"

# --------------------------------------------------------------------------- 7. correlate behaviour

section "7. Correlate: message start, active duplicate, no subscription"
api check7c-correlate-no-subscription svc POST /v2/messages/correlation \
    "$(jq -nc --arg n "$MSG_RECEIVED" --arg k "BPA25-NOWAIT-7c-$RUN_ID" '{name:$n, correlationKey:$k, variables:{}}')"
verdict "7c Nothing waiting" RECORD "status $STATUS, body $(jq -c . <<<"$BODY" 2>/dev/null | cut -c1-200)"

count_active() {
  api check7-count-active admin POST /v2/process-instances/search \
      "$(jq -nc --arg p "$REASSIGN_PROC" '{filter:{processDefinitionId:$p, state:"ACTIVE"}, page:{limit:1}}')"
  jget '.page.totalItems'
}
BEFORE="$(count_active)"; BEFORE="${BEFORE:-0}"

K7="BPA25-WP7-$RUN_ID"
corr_replaced() {
  api "$1" svc POST /v2/messages/correlation \
      "$(jq -nc --arg n "$MSG_REPLACED" --arg k "$K7" '{name:$n, correlationKey:$k, variables:{workpackNumber:$k}}')"
}
corr_replaced check7a-correlate-start
R1="$(jget '.processInstanceKey')"
if ok && [[ -n "$R1" ]]; then
  verdict "7a Correlate starts the instance" PASS "status $STATUS, processInstanceKey $R1"
else
  verdict "7a Correlate starts the instance" FAIL "status $STATUS, no processInstanceKey"
fi

corr_replaced check7b-correlate-while-active
R2="$(jget '.processInstanceKey')"
S7B="$STATUS"; F7B="$LAST_FILE"; B7B="$(jq -c . <<<"$BODY" 2>/dev/null | cut -c1-200)"
seen_one() { local n; n="$(count_active)"; [[ -n "$n" ]] && (( n - BEFORE >= 1 )); }
poll "$POLL_SECONDS" seen_one; sleep 3
AFTER="$(count_active)"; AFTER="${AFTER:-0}"
if [[ ( -z "$R2" || "$R2" == "$R1" ) ]] && (( AFTER - BEFORE == 1 )); then
  verdict "7b No second instance" PASS "second correlate: status $S7B, body $B7B; active instances +1" "$F7B"
else
  verdict "7b No second instance" FAIL "second correlate: status $S7B, key '${R2}'; active instances +$((AFTER - BEFORE))" "$F7B"
fi
verdict "7b Response to record for the C2/C3 code" RECORD "HTTP $S7B: $B7B" "$F7B"

if [[ -n "$R1" ]]; then
  HOLD=""
  find_hold() {
    api check7d-find-hold admin POST /v2/user-tasks/search \
        "$(jq -nc --arg pi "$R1" '{filter:{processInstanceKey:$pi, state:"CREATED"}}')"
    HOLD="$(jget '.items[0].userTaskKey')"; [[ -n "$HOLD" ]]
  }
  if poll "$POLL_SECONDS" find_hold; then
    api check7d-end-first admin POST "/v2/user-tasks/$HOLD/completion" '{"variables":{}}'
    ended() { api check7d-first-state admin GET "/v2/process-instances/$R1"; [[ "$(jget '.state')" == "COMPLETED" ]]; }
    poll "$POLL_SECONDS" ended
    corr_replaced check7d-correlate-after-end
    R3="$(jget '.processInstanceKey')"
    if ok && [[ -n "$R3" && "$R3" != "$R1" ]]; then
      verdict "7d Key free once the instance ended" PASS "new processInstanceKey $R3"
      api cleanup-cancel admin POST "/v2/process-instances/$R3/cancellation" '{}'
    else
      verdict "7d Key free once the instance ended" FAIL "status $STATUS, key '${R3:-}'"
    fi
  else
    verdict "7d Key free once the instance ended" FAIL "hold task of $R1 not found"
  fi
fi

# --------------------------------------------------------------------------- results

{
  echo "## BPA-25 results, run $RUN_ID"
  echo
  echo "Stack: $CAMUNDA_URL, gatewayVersion ${VERSION:-unknown}. Runner: run-bpa25.sh. Evidence folder: \`$EVID/\`."
  echo "bpa-service permissions used: PROCESS_DEFINITION ${SVC_PD_PERMS:-'(setup skipped)'} on the two spike processes; MESSAGE CREATE on *."
  echo
  echo "| Check | Verdict | Finding | Evidence |"
  echo "|---|---|---|---|"
  printf '%s\n' "${VERDICTS[@]}"
  echo
  echo "**Manual evidence still needed:** Operate screenshots of the completed tasks for checks 2 and 5 (who is shown as completer)."
  echo "**RECORD rows** are facts to paste, not pass/fail: they decide ADR 0007 (6b), the correlate contract (6a, 7b, 7c) and D63 (2, 5b)."
} >"$RESULTS"

section "Done"
echo "Results: $RESULTS"
echo "Evidence: $EVID/ ($SEQ files)"
echo "FAIL rows: $(grep -c '\*\*FAIL\*\*' "$RESULTS" || true); RECORD rows: $(grep -c '\*\*RECORD\*\*' "$RESULTS" || true)"
