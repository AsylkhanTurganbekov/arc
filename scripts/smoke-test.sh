#!/usr/bin/env bash
set -euo pipefail

arc_base_url="${ARC_BASE_URL:-http://localhost:3000}"

check_json() {
  local name="$1"
  local path="$2"
  local filter="$3"
  local payload
  payload="$(curl -fsS --max-time 180 "${arc_base_url}${path}")"
  jq -e "${filter}" >/dev/null <<<"${payload}"
  printf 'OK  %s\n' "${name}"
}

check_json "system health" "/api/health" '.status == "UP" and .jira == "CONNECTED"'
check_json "morning briefing" "/api/briefing" '.portfolio.projects | length > 0'
check_json "Kindy sprint" "/api/projects/KIN/sprint" '.project == "KIN" and .total > 0'
check_json "Kindy people" "/api/people?project=KIN" 'length > 0 and all(.[]; .employee != "Адилет Абдуллов" and (.active_tasks | type == "number") and (.testing_tasks | type == "number") and (.done_dev_tasks | type == "number"))'
check_json "portfolio people directory" "/api/people" 'length > 0 and all(.[]; .employee != "administrator" and (.employee | length) > 0 and (.role | length) > 0 and (.projects | type == "array"))'
check_json "Kindy stuck tasks" "/api/projects/KIN/stuck?minDays=3" 'type == "array"'
check_json "Kindy release" "/api/projects/KIN/release-readiness" '.project == "KIN" and (.score | type == "number")'
check_json "Kindy anomalies" "/api/projects/KIN/anomalies?periodDays=7" '.project == "KIN" and .baseline_available == true and (.baseline_source == "JIRA_CHANGELOG" or .baseline_source == "ARC_SNAPSHOT")'
check_json "Kindy delivery" "/api/projects/KIN/delivery-management" '.project == "KIN"'
check_json "Kindy weekly" "/api/projects/KIN/weekly-review" '.project == "KIN" and .baseline_available == true and (.baseline_source == "JIRA_CHANGELOG" or .baseline_source == "ARC_SNAPSHOT")'
check_json "BeketOS sprint" "/api/projects/BEK/sprint" '.project == "BEK" and .name == "BeketOS Спринт 2"'
check_json "resource planner" "/api/resource-plan?period=next_week" '.generated_by == "ARC_ANALYTICS_ENGINE"'
check_json "AI service" "/ai/health" '.status == "UP"'

simulation="$(curl -fsS --max-time 60 -X POST "${arc_base_url}/api/simulations/resource-move" \
  -H 'Content-Type: application/json' \
  -d '{"employee":"Адема Амангелды","from_project":"KIN","to_project":"BEK","capacity_percent":30}')"
jq -e '.simulation == true and .after.KIN != null and .after.BEK != null' >/dev/null <<<"${simulation}"
printf 'OK  what-if simulation\n'

frontend_html="$(curl -fsS --max-time 30 "${arc_base_url}/")"
grep -q 'A.R.C.' <<<"${frontend_html}"
printf 'OK  frontend\n'
printf '\nA.R.C. smoke test passed.\n'
