package kz.belesai.arc.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import kz.belesai.arc.jira.JiraClient;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AnalyticsService {
    private static final Pattern SPRINT_ID = Pattern.compile("(?:^|[,\\[])id=(\\d+)");
    private static final Pattern SPRINT_NAME = Pattern.compile("(?:^|,)name=([^,\\]]+)");
    private static final Set<String> COMPLETED = Set.of("DONE", "RELEASED");

    private final JiraClient jira;
    private final JdbcTemplate jdbc;
    private final Map<String, ContextCache> contextCache = new ConcurrentHashMap<>();
    private final Map<String, HistoricalMetricCache> historicalMetricCache = new ConcurrentHashMap<>();

    public AnalyticsService(JiraClient jira, JdbcTemplate jdbc) {
        this.jira = jira;
        this.jdbc = jdbc;
    }

    public Map<String, Object> health() {
        int count = jira.projects().isArray() ? jira.projects().size() : 0;
        return linkedMap(
                "status", "UP",
                "jira", "CONNECTED",
                "projects", count,
                "analytics_engine", "READY",
                "timestamp", Instant.now().toString()
        );
    }

    public List<Map<String, Object>> projects() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode project : jira.projects()) {
            if (project.path("archived").asBoolean(false)) continue;
            result.add(linkedMap(
                    "id", project.path("id").asText(),
                    "key", project.path("key").asText(),
                    "name", project.path("name").asText(),
                    "archived", false
            ));
        }
        return result;
    }

    public Map<String, Object> portfolioHealth() {
        List<Map<String, Object>> projectHealth = projects().parallelStream()
                .map(project -> projectHealth(project.get("key").toString()))
                .sorted(Comparator.comparingInt(item -> (int) item.get("health_score")))
                .toList();
        int score = projectHealth.isEmpty() ? 100 : (int) Math.round(projectHealth.stream()
                .mapToInt(item -> (int) item.get("health_score")).average().orElse(100));
        long critical = projectHealth.stream().filter(item -> "CRITICAL".equals(item.get("status"))).count();
        long attention = projectHealth.stream().filter(item -> "ATTENTION".equals(item.get("status"))).count();
        long healthy = projectHealth.size() - critical - attention;
        return linkedMap(
                "health_score", score,
                "status", score < 55 ? "CRITICAL" : score < 75 ? "ATTENTION" : "ON_TRACK",
                "critical_projects", critical,
                "at_risk_projects", attention,
                "healthy_projects", healthy,
                "projects", projectHealth,
                "calculated_at", Instant.now().toString()
        );
    }

    public Map<String, Object> projectHealth(String projectKey) {
        ProjectContext context = context(projectKey);
        Map<String, Object> sprint = sprintHealth(projectKey);
        Map<String, Object> release = releaseReadiness(projectKey);
        int total = (int) sprint.get("total");
        int blocked = (int) sprint.get("blocked");
        int stuck = (int) sprint.get("stuck");
        int blockedHealth = Metrics.clamp(100 - ratio(blocked, total) * 200);
        int stuckHealth = Metrics.clamp(100 - ratio(stuck, total) * 120);
        List<Map<String, Object>> people = peopleLoad(projectKey);
        double averageLoad = people.stream().mapToInt(person -> (int) person.get("load_score")).average().orElse(50);
        int peopleLoadHealth = Metrics.clamp(100 - Math.max(0, averageLoad - 70) * 2);
        long newTasks = context.scope().stream().filter(issue ->
                parseInstant(issue.path("fields").path("created").asText()).isAfter(Instant.now().minus(7, ChronoUnit.DAYS))
        ).count();
        int scopeStability = Metrics.clamp(100 - ratio((int) newTasks, total) * 150);
        int score = Metrics.projectHealth(
                (int) sprint.get("health_score"), blockedHealth, stuckHealth,
                peopleLoadHealth, scopeStability, (int) release.get("score")
        );
        return linkedMap(
                "project", context.name(),
                "project_key", context.key(),
                "health_score", score,
                "status", score < 55 ? "CRITICAL" : score < 75 ? "ATTENTION" : "ON_TRACK",
                "blocked", blocked,
                "stuck", stuck,
                "testing", sprint.get("test"),
                "release_readiness", release.get("score"),
                "score_breakdown", linkedMap(
                        "sprint_health", sprint.get("health_score"),
                        "blocker_health", blockedHealth,
                        "stuck_health", stuckHealth,
                        "people_load_health", peopleLoadHealth,
                        "scope_stability", scopeStability,
                        "release_readiness", release.get("score")
                ),
                "calculated_at", Instant.now().toString()
        );
    }

    public Map<String, Object> sprintHealth(String projectKey) {
        ProjectContext context = context(projectKey);
        Map<String, Integer> counts = statusCounts(context.scope());
        int total = context.scope().size();
        int completed = counts.getOrDefault("DONE", 0) + counts.getOrDefault("RELEASED", 0);
        int devDone = counts.getOrDefault("DEV_DONE", 0);
        int testing = counts.getOrDefault("TESTING", 0);
        int review = counts.getOrDefault("REVIEW", 0);
        int inProgress = counts.getOrDefault("IN_PROGRESS", 0);
        int blocked = counts.getOrDefault("BLOCKED", 0);
        int stuck = approximateStuck(context.scope(), 3);
        int score = Metrics.sprintHealth(total, completed, devDone, testing, review, inProgress, blocked, stuck);
        double weightedDone = completed + devDone * .75 + testing * .5 + review * .3 + inProgress * .2;
        return linkedMap(
                "project", context.key(),
                "sprint_id", context.sprintId(),
                "name", context.sprintName(),
                "state", context.sprintState(),
                "total", total,
                "blocked", blocked,
                "in_progress", inProgress,
                "review", review,
                "test", testing,
                "done_dev", devDone,
                "done_prod", counts.getOrDefault("RELEASED", 0),
                "done", counts.getOrDefault("DONE", 0),
                "todo", counts.getOrDefault("TODO", 0),
                "stuck", stuck,
                "progress_percent", Metrics.clamp(total == 0 ? 100 : weightedDone / total * 100),
                "health_score", score,
                "status_breakdown", counts
        );
    }

    public List<Map<String, Object>> peopleLoad(String projectKey) {
        List<ProjectContext> contexts;
        if (projectKey == null || projectKey.isBlank()) {
            contexts = projects().parallelStream().map(project -> context(project.get("key").toString())).toList();
        } else {
            contexts = List.of(context(projectKey));
        }

        Map<String, PersonAccumulator> people = new LinkedHashMap<>();
        for (ProjectContext context : contexts) {
            // People load is a sprint-level signal. Project backlog and old sprints
            // must not introduce people who are not part of the current team focus.
            for (JsonNode issue : context.scope()) {
                String state = normalizeStatus(issue.path("fields").path("status").path("name").asText());
                if (COMPLETED.contains(state)) continue;
                JsonNode assigneeNode = issue.path("fields").path("assignee");
                String assignee = assigneeNode.path("displayName").asText("");
                if (assignee.isBlank()) continue;
                String accountName = assigneeNode.path("name").asText("");
                if ("administrator".equalsIgnoreCase(assignee)
                        || "admin".equalsIgnoreCase(accountName)
                        || !assigneeNode.path("active").asBoolean(true)) continue;
                String personKey = accountName.isBlank() ? assignee.toLowerCase(Locale.ROOT) : accountName.toLowerCase(Locale.ROOT);
                PersonAccumulator person = people.computeIfAbsent(personKey, ignored -> new PersonAccumulator(assignee, accountName));
                person.projects.merge(context.key(), 1, Integer::sum);
                person.role = detectRole(issue, person.role);
                person.taskKeys.add(issue.path("key").asText());
                if ("TESTING".equals(state)) {
                    person.testingTasks++;
                    continue;
                }
                if ("DEV_DONE".equals(state)) {
                    person.doneDevTasks++;
                    continue;
                }
                if (!Metrics.isDeveloperActiveStatus(state)) continue;
                person.activeTasks++;
                person.activeProjects.add(context.key());
                if ("BLOCKED".equals(state)) person.blockedTasks++;
                if (isHighPriority(issue)) person.highPriority++;
            }
        }

        List<Map<String, Object>> result = people.values().stream()
                .map(this::toPersonMap)
                .sorted(Comparator.comparingInt(item -> -(int) item.get("load_score")))
                .toList();
        Map<String, String> directory = employeePositions();
        result.forEach(person -> {
            String storedPosition = directory.get(person.get("employee").toString().toLowerCase(Locale.ROOT));
            if (storedPosition != null) {
                person.put("role", storedPosition);
                person.put("role_source", "DIRECTORY");
            }
        });
        return result;
    }

    public List<Map<String, Object>> peopleDirectory() {
        Map<String, Map<String, Object>> workloadByEmployee = new LinkedHashMap<>();
        for (Map<String, Object> person : peopleLoad(null)) {
            String username = person.getOrDefault("jira_username", "").toString().trim();
            String key = username.isBlank()
                    ? "name:" + person.get("employee").toString().toLowerCase(Locale.ROOT)
                    : "user:" + username.toLowerCase(Locale.ROOT);
            workloadByEmployee.put(key, person);
        }

        Map<String, String> positions = employeePositions();
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode user : jira.users()) {
            if (!user.path("active").asBoolean(true)) continue;
            String employee = user.path("displayName").asText(user.path("name").asText("")).trim();
            String username = user.path("name").asText(user.path("key").asText("")).trim();
            if (employee.isBlank()) continue;
            String normalized = employee.toLowerCase(Locale.ROOT);
            String identity = username.isBlank() ? "name:" + normalized : "user:" + username.toLowerCase(Locale.ROOT);
            if (!seen.add(identity)) continue;

            Map<String, Object> current = workloadByEmployee.remove(identity);
            if (current == null) current = workloadByEmployee.remove("name:" + normalized);
            Map<String, Object> person = current == null
                    ? emptyDirectoryPerson(employee, username)
                    : new LinkedHashMap<>(current);
            person.put("jira_username", username);
            String storedPosition = positions.get(normalized);
            if (storedPosition != null) {
                person.put("role", storedPosition);
                person.put("role_source", "DIRECTORY");
            }
            result.add(person);
        }

        result.addAll(workloadByEmployee.values());
        result.sort(Comparator
                .<Map<String, Object>>comparingInt(person -> -(int) person.get("load_score"))
                .thenComparing(person -> person.get("employee").toString(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public Map<String, Object> employeeLoad(String employee) {
        return peopleLoad(null).stream()
                .filter(person -> employee.equalsIgnoreCase(person.get("employee").toString())
                        || employee.equalsIgnoreCase(person.getOrDefault("jira_username", "").toString()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    }

    public Map<String, Object> updateEmployeePosition(String employee, Map<String, Object> input) {
        String normalizedEmployee = employee == null ? "" : employee.trim();
        Object rawPosition = input.get("position");
        String position = rawPosition == null ? "" : rawPosition.toString().trim();
        if (normalizedEmployee.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employee is required");
        }
        if (position.length() < 2 || position.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "position must contain 2 to 80 characters");
        }
        jdbc.update("""
                INSERT INTO employee_directory (employee, position, updated_at)
                VALUES (?, ?, now())
                ON CONFLICT (employee) DO UPDATE SET
                  position = EXCLUDED.position,
                  updated_at = now()
                """, normalizedEmployee, position);
        return linkedMap(
                "employee", normalizedEmployee,
                "position", position,
                "status", "SAVED"
        );
    }

    public List<Map<String, Object>> blockedTasks(String projectKey) {
        return context(projectKey).scope().stream()
                .filter(issue -> "BLOCKED".equals(normalizeStatus(issue.path("fields").path("status").path("name").asText())))
                .map(issue -> taskMap(issue, null))
                .toList();
    }

    public List<Map<String, Object>> stuckTasks(String projectKey, int minDays) {
        ProjectContext context = context(projectKey);
        return context.scope().stream()
                .filter(issue -> !COMPLETED.contains(normalizeStatus(issue.path("fields").path("status").path("name").asText())))
                .parallel()
                .map(issue -> enrichStuck(issue, minDays))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(item -> -(int) item.get("stuck_days")))
                .toList();
    }

    public Map<String, Object> releaseReadiness(String projectKey) {
        ProjectContext context = context(projectKey);
        Map<String, Integer> counts = statusCounts(context.scope());
        int total = context.scope().size();
        int completed = counts.getOrDefault("DONE", 0) + counts.getOrDefault("RELEASED", 0);
        int devDone = counts.getOrDefault("DEV_DONE", 0);
        int blocked = counts.getOrDefault("BLOCKED", 0);
        int testing = counts.getOrDefault("TESTING", 0);
        int stuck = approximateStuck(context.scope(), 3);
        int criticalBugs = (int) context.scope().stream().filter(this::isCriticalBug).count();
        int highBugs = (int) context.scope().stream().filter(issue -> isBug(issue) && isHighPriority(issue)).count();
        int score = Metrics.releaseReadiness(total, completed, devDone, blocked, testing, criticalBugs, stuck);
        String status = score >= 85 && blocked == 0 && criticalBugs == 0
                ? "READY" : score >= 65 && criticalBugs == 0 ? "READY_WITH_RISK" : "NOT_READY";
        return linkedMap(
                "project", context.key(),
                "score", score,
                "status", status,
                "critical_bugs", criticalBugs,
                "high_bugs", highBugs,
                "blocked", blocked,
                "testing", testing,
                "done_dev", devDone,
                "released", completed,
                "stuck", stuck,
                "calculated_at", Instant.now().toString()
        );
    }

    public Map<String, Object> projectAnomalies(String projectKey, int periodDays) {
        Map<String, Object> current = projectHealth(projectKey);
        Optional<Map<String, Object>> baseline = baselineProjectMetric(projectKey, periodDays);
        if (baseline.isEmpty()) {
            return linkedMap(
                    "project", projectKey.toUpperCase(Locale.ROOT),
                    "period_days", periodDays,
                    "baseline_available", false,
                    "anomalies", List.of(),
                    "message", "Накопление истории началось. Для сравнения нужен снимок за выбранный период.",
                    "current", linkedMap(
                            "health_score", current.get("health_score"),
                            "blocked", current.get("blocked"),
                            "testing", current.get("testing")
                    )
            );
        }
        Map<String, Object> before = baseline.get();
        List<Map<String, Object>> anomalies = new ArrayList<>();
        addAnomaly(anomalies, "testing", (int) before.get("testing"), (int) current.get("testing"));
        addAnomaly(anomalies, "blocked", (int) before.get("blocked"), (int) current.get("blocked"));
        addAnomaly(anomalies, "health_score", (int) before.get("health_score"), (int) current.get("health_score"));
        return linkedMap(
                "project", projectKey.toUpperCase(Locale.ROOT),
                "period_days", periodDays,
                "baseline_available", true,
                "baseline_source", before.get("baseline_source"),
                "history_coverage_percent", before.get("history_coverage_percent"),
                "baseline", before,
                "current", current,
                "anomalies", anomalies,
                "message", anomalies.isEmpty()
                        ? "За выбранный период существенных отклонений не обнаружено."
                        : "Обнаружены изменения, превысившие порог A.R.C."
        );
    }

    public Map<String, Object> deliveryManagement(String projectKey) {
        ProjectContext context = context(projectKey);
        Map<String, Object> sprint = sprintHealth(projectKey);
        int total = Math.max(1, context.scope().size());
        int newThisWeek = (int) context.scope().stream().filter(issue ->
                parseInstant(issue.path("fields").path("created").asText()).isAfter(Instant.now().minus(7, ChronoUnit.DAYS))
        ).count();
        int unassigned = (int) context.scope().stream().filter(issue ->
                issue.path("fields").path("assignee").isMissingNode() || issue.path("fields").path("assignee").isNull()
        ).count();
        int planningStability = Metrics.clamp(100 - ratio(newThisWeek, total) * 130);
        int scopeControl = Metrics.clamp(100 - ratio(newThisWeek, total) * 180);
        int blockerResolution = Metrics.clamp(100 - ratio((int) sprint.get("blocked"), total) * 220);
        int taskHygiene = Metrics.clamp(100 - ratio(unassigned, total) * 160);
        int predictability = Metrics.clamp((int) sprint.get("health_score") * .55 + (int) sprint.get("progress_percent") * .45);
        return linkedMap(
                "project", context.key(),
                "title", "Delivery Management",
                "planning_stability", planningStability,
                "scope_control", scopeControl,
                "blocker_resolution", blockerResolution,
                "task_hygiene", taskHygiene,
                "delivery_predictability", predictability,
                "facts", linkedMap("new_tasks_7d", newThisWeek, "unassigned", unassigned, "total", total)
        );
    }

    public Map<String, Object> weeklyReview(String projectKey) {
        String key = projectKey.toUpperCase(Locale.ROOT);
        Map<String, Object> currentHealth = projectHealth(key);
        Map<String, Object> currentSprint = sprintHealth(key);
        Optional<Map<String, Object>> baseline = baselineProjectMetric(key, 7);
        if (baseline.isEmpty()) {
            return linkedMap(
                    "project", key,
                    "baseline_available", false,
                    "period", "last_7_days",
                    "current", currentHealth,
                    "message", "Первый weekly baseline будет доступен после семи дней накопления снимков."
            );
        }
        Map<String, Object> before = baseline.get();
        int releasedNow = (int) currentSprint.get("done_prod") + (int) currentSprint.get("done");
        int releasedBefore = (int) before.get("released");
        return linkedMap(
                "project", key,
                "baseline_available", true,
                "baseline_source", before.get("baseline_source"),
                "baseline_at", before.get("baseline_at"),
                "history_coverage_percent", before.get("history_coverage_percent"),
                "period", "last_7_days",
                "completed", Math.max(0, releasedNow - releasedBefore),
                "new_tasks", (int) context(key).scope().stream().filter(issue ->
                        parseInstant(issue.path("fields").path("created").asText())
                                .isAfter(Instant.now().minus(7, ChronoUnit.DAYS))
                ).count(),
                "blocked_before", before.get("blocked"),
                "blocked_now", currentHealth.get("blocked"),
                "test_before", before.get("testing"),
                "test_now", currentHealth.get("testing"),
                "health_before", before.get("health_score"),
                "health_now", currentHealth.get("health_score")
        );
    }

    public Map<String, Object> simulateResourceMove(Map<String, Object> input) {
        String employee = required(input, "employee");
        String from = required(input, "from_project").toUpperCase(Locale.ROOT);
        String to = required(input, "to_project").toUpperCase(Locale.ROOT);
        int capacity = Integer.parseInt(input.getOrDefault("capacity_percent", 50).toString());
        if (capacity < 10 || capacity > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capacity_percent must be 10..100");
        }
        int fromBefore = (int) projectHealth(from).get("health_score");
        int toBefore = (int) projectHealth(to).get("health_score");
        int fromAfter = Metrics.clamp(fromBefore - capacity * .22);
        int toAfter = Metrics.clamp(toBefore + capacity * .30 * ((100 - toBefore) / 50.0));
        return linkedMap(
                "simulation", true,
                "employee", employee,
                "capacity_percent", capacity,
                "before", linkedMap(from, fromBefore, to, toBefore),
                "after", linkedMap(from, fromAfter, to, toAfter),
                "estimated_delay_from", Math.max(1, (int) Math.round(capacity / 8.0)),
                "estimated_gain_to", Math.max(1, (int) Math.round((toAfter - toBefore) * .8)),
                "assumptions", List.of(
                        "Текущий scope и сроки не меняются",
                        "Эффект рассчитан Analytics Engine и не записывается в Jira",
                        "Context switching учтён как 10% потери переводимой capacity"
                )
        );
    }

    public Map<String, Object> resourcePlan(String period) {
        List<Map<String, Object>> people = peopleLoad(null);
        List<Map<String, Object>> projects = ((List<Map<String, Object>>) portfolioHealth().get("projects"));
        List<Map<String, Object>> risks = projects.stream().filter(item -> (int) item.get("health_score") < 75).toList();
        List<Map<String, Object>> candidates = people.stream().filter(person -> (int) person.get("load_score") < 85).limit(8).toList();
        List<Map<String, Object>> recommendations = new ArrayList<>();
        for (int index = 0; index < Math.min(risks.size(), candidates.size()); index++) {
            Map<String, Object> target = risks.get(index);
            Map<String, Object> person = candidates.get(index);
            int free = (int) person.get("free_capacity");
            recommendations.add(linkedMap(
                    "employee", person.get("employee"),
                    "role", person.get("role"),
                    "to_project", target.get("project_key"),
                    "capacity_percent", Math.max(10, Math.min(30, free)),
                    "reason", "Проект требует внимания, а у сотрудника ниже текущая нагрузка",
                    "requires_manager_review", true
            ));
        }
        return linkedMap(
                "period", period,
                "generated_by", "ARC_ANALYTICS_ENGINE",
                "recommendations", recommendations,
                "constraints", List.of("skills", "role", "capacity", "priority", "health", "context_switching"),
                "note", "План является рекомендацией и не изменяет Jira."
        );
    }

    public Map<String, Object> briefing() {
        Map<String, Object> portfolio = portfolioHealth();
        List<Map<String, Object>> projectHealth = (List<Map<String, Object>>) portfolio.get("projects");
        List<Map<String, Object>> attention = projectHealth.stream().filter(item -> (int) item.get("health_score") < 75).limit(5).toList();
        List<Map<String, Object>> overloaded = peopleLoad(null).stream().filter(item -> (int) item.get("load_score") >= 85).limit(5).toList();
        List<String> recommendations = new ArrayList<>();
        attention.stream().limit(3).forEach(item -> recommendations.add(
                "Разобрать " + item.get("blocked") + " blockers в " + item.get("project")
        ));
        if (overloaded.isEmpty()) recommendations.add("Критической перегрузки по текущим задачам не обнаружено.");
        else recommendations.add("Не добавлять новые задачи: перегружены " + overloaded.stream()
                .map(item -> item.get("employee").toString()).limit(3).reduce((a, b) -> a + ", " + b).orElse(""));
        return linkedMap(
                "title", "A.R.C. Morning Briefing",
                "generated_at", Instant.now().toString(),
                "portfolio", portfolio,
                "attention", attention,
                "overloaded_people", overloaded,
                "recommendations", recommendations,
                "source", "JIRA_AND_ARC_ANALYTICS"
        );
    }

    public void evictProject(String projectKey) {
        jira.evictProject(projectKey);
        String key = projectKey.toUpperCase(Locale.ROOT);
        contextCache.remove(key);
        historicalMetricCache.keySet().removeIf(cacheKey -> cacheKey.startsWith(key + ":"));
    }

    private ProjectContext context(String projectKey) {
        String key = projectKey.replaceAll("[^A-Za-z0-9_-]", "").toUpperCase(Locale.ROOT);
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "project is required");
        ContextCache cached = contextCache.get(key);
        if (cached != null && Duration.between(cached.createdAt(), Instant.now()).toSeconds() < 150) return cached.value();

        Map<String, Object> project = projects().stream().filter(item -> key.equals(item.get("key"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        List<JsonNode> all = jira.projectIssues(key);
        String sprintField = jira.sprintFieldId();
        SprintRef active = findActiveSprint(all, sprintField);
        List<JsonNode> scope;
        if (active != null) {
            scope = all.stream().filter(issue -> issueHasSprint(issue, sprintField, active.id())).toList();
        } else {
            scope = all.stream().filter(issue -> !COMPLETED.contains(
                    normalizeStatus(issue.path("fields").path("status").path("name").asText()))).toList();
        }
        ProjectContext value = new ProjectContext(
                key,
                project.get("name").toString(),
                all,
                scope,
                active == null ? "unplanned" : active.id(),
                active == null ? "Unplanned active work" : active.name(),
                active == null ? "UNPLANNED" : "ACTIVE"
        );
        contextCache.put(key, new ContextCache(value, Instant.now()));
        return value;
    }

    private SprintRef findActiveSprint(List<JsonNode> issues, String field) {
        for (JsonNode issue : issues) {
            for (String value : sprintValues(issue.path("fields").path(field))) {
                if (value.toUpperCase(Locale.ROOT).contains("STATE=ACTIVE")) {
                    Matcher id = SPRINT_ID.matcher(value);
                    Matcher name = SPRINT_NAME.matcher(value);
                    return new SprintRef(id.find() ? id.group(1) : "active", name.find() ? name.group(1).trim() : "Active sprint");
                }
            }
        }
        return null;
    }

    private boolean issueHasSprint(JsonNode issue, String field, String sprintId) {
        return sprintValues(issue.path("fields").path(field)).stream().anyMatch(value -> {
            Matcher matcher = SPRINT_ID.matcher(value);
            return matcher.find() && sprintId.equals(matcher.group(1));
        });
    }

    private List<String> sprintValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> values.add(value.asText()));
        else if (!node.isMissingNode() && !node.isNull()) values.add(node.asText());
        return values;
    }

    private Map<String, Integer> statusCounts(List<JsonNode> issues) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode issue : issues) {
            String status = normalizeStatus(issue.path("fields").path("status").path("name").asText());
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }

    private String normalizeStatus(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.equals("СДЕЛАТЬ") || value.equals("BACKLOG") || value.equals("SELECTED FOR DEVELOPMENT")) return "TODO";
        if (value.equals("BLOCKED")) return "BLOCKED";
        if (value.equals("В РАБОТЕ") || value.equals("IN PROGESS") || value.equals("IN PROGRESS") || value.equals("ТЕКУЩИЙ СПРИНТ")) return "IN_PROGRESS";
        if (value.equals("IN REVIEW") || value.equals("REVIEW") || value.equals("НА РАССМОТРЕНИИ")) return "REVIEW";
        if (value.equals("TEST") || value.equals("ТЕСТИРОВАНИЕ")) return "TESTING";
        if (value.startsWith("DONE - DEV")) return "DEV_DONE";
        if (value.startsWith("DONE - PROD")) return "RELEASED";
        if (value.equals("ГОТОВО") || value.equals("DONE")) return "DONE";
        if (value.equals("В ОЖИДАНИИ")) return "WAITING";
        return value.isBlank() ? "UNKNOWN" : value.replace(' ', '_');
    }

    private int approximateStuck(List<JsonNode> issues, int minDays) {
        Instant limit = Instant.now().minus(minDays, ChronoUnit.DAYS);
        return (int) issues.stream().filter(issue -> {
            String state = normalizeStatus(issue.path("fields").path("status").path("name").asText());
            return !COMPLETED.contains(state)
                    && parseInstant(issue.path("fields").path("updated").asText()).isBefore(limit);
        }).count();
    }

    private Optional<Map<String, Object>> enrichStuck(JsonNode issue, int minDays) {
        JsonNode full = jira.issueWithChangelog(issue.path("key").asText());
        Instant enteredStatusAt = parseInstant(full.path("fields").path("created").asText());
        int reopens = 0;
        int assigneeChanges = 0;
        for (JsonNode history : full.path("changelog").path("histories")) {
            Instant changedAt = parseInstant(history.path("created").asText());
            for (JsonNode item : history.path("items")) {
                String field = item.path("field").asText("").toLowerCase(Locale.ROOT);
                if (field.equals("status") && changedAt.isAfter(enteredStatusAt)) enteredStatusAt = changedAt;
                if (field.equals("status")
                        && COMPLETED.contains(normalizeStatus(item.path("fromString").asText()))
                        && !COMPLETED.contains(normalizeStatus(item.path("toString").asText()))) reopens++;
                if (field.equals("assignee")) assigneeChanges++;
            }
        }
        int days = (int) Math.max(0, ChronoUnit.DAYS.between(enteredStatusAt, Instant.now()));
        if (days < minDays) return Optional.empty();
        return Optional.of(taskMap(issue, linkedMap(
                "entered_status_at", enteredStatusAt.toString(),
                "stuck_days", days,
                "number_of_reopens", reopens,
                "number_of_assignee_changes", assigneeChanges
        )));
    }

    private Map<String, Object> taskMap(JsonNode issue, Map<String, Object> extra) {
        JsonNode fields = issue.path("fields");
        Map<String, Object> value = linkedMap(
                "key", issue.path("key").asText(),
                "summary", fields.path("summary").asText(),
                "status", fields.path("status").path("name").asText(),
                "arc_status", normalizeStatus(fields.path("status").path("name").asText()),
                "assignee", fields.path("assignee").path("displayName").asText("Unassigned"),
                "priority", fields.path("priority").path("name").asText("None"),
                "updated", fields.path("updated").asText()
        );
        if (extra != null) value.putAll(extra);
        return value;
    }

    private Map<String, Object> toPersonMap(PersonAccumulator person) {
        int load = Metrics.clamp(person.activeTasks * 9 + person.blockedTasks * 15 + person.highPriority * 7
                + Math.max(0, person.activeProjects.size() - 1) * 8);
        int assignedTasks = person.projects.values().stream().mapToInt(Integer::intValue).sum();
        List<Map<String, Object>> allocations = person.projects.entrySet().stream().map(entry -> linkedMap(
                "project", entry.getKey(),
                "allocation", (int) Math.round(entry.getValue() * 100.0 / Math.max(1, assignedTasks))
        )).toList();
        return linkedMap(
                "employee", person.name,
                "jira_username", person.username,
                "role", person.role,
                "role_source", "Не указана".equals(person.role) ? "UNSPECIFIED" : "JIRA",
                "capacity", 100,
                "free_capacity", Math.max(0, 100 - load),
                "projects", allocations,
                "project_count", person.projects.size(),
                "active_tasks", person.activeTasks,
                "testing_tasks", person.testingTasks,
                "done_dev_tasks", person.doneDevTasks,
                "blocked_tasks", person.blockedTasks,
                "high_priority", person.highPriority,
                "load_score", load,
                "status", load >= 85 ? "OVERLOADED" : load >= 70 ? "HIGH" : load >= 40 ? "BALANCED" : "AVAILABLE"
        );
    }

    private Map<String, Object> emptyDirectoryPerson(String employee, String username) {
        return linkedMap(
                "employee", employee,
                "jira_username", username,
                "role", "Не указана",
                "role_source", "UNSPECIFIED",
                "capacity", 100,
                "free_capacity", 100,
                "projects", List.of(),
                "project_count", 0,
                "active_tasks", 0,
                "testing_tasks", 0,
                "done_dev_tasks", 0,
                "blocked_tasks", 0,
                "high_priority", 0,
                "load_score", 0,
                "status", "AVAILABLE"
        );
    }

    private Map<String, String> employeePositions() {
        try {
            Map<String, String> result = new HashMap<>();
            jdbc.query(
                    "SELECT employee, position FROM employee_directory",
                    (rows, rowNumber) -> Map.entry(
                            rows.getString("employee").toLowerCase(Locale.ROOT),
                            rows.getString("position")
                    )
            ).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        } catch (DataAccessException ignored) {
            // Jira team data remains available while the local directory database is recovering.
            return Map.of();
        }
    }

    private String detectRole(JsonNode issue, String current) {
        StringBuilder text = new StringBuilder();
        issue.path("fields").path("components").forEach(component -> text.append(' ').append(component.path("name").asText()));
        issue.path("fields").path("labels").forEach(label -> text.append(' ').append(label.asText()));
        String labels = text.toString().toLowerCase(Locale.ROOT);
        if (labels.contains("backend") || labels.contains("java")) return "Backend Engineering";
        if (labels.contains("frontend") || labels.contains("front")) return "Frontend Engineering";
        if (labels.contains("qa") || labels.contains("test")) return "Quality Engineering";
        if (labels.contains("design")) return "Product Design";
        if (labels.contains("devops")) return "DevOps";
        return current == null ? "Не указана" : current;
    }

    private boolean isHighPriority(JsonNode issue) {
        String priority = issue.path("fields").path("priority").path("name").asText("").toLowerCase(Locale.ROOT);
        return priority.contains("critical") || priority.contains("highest") || priority.contains("high")
                || priority.contains("blocker") || priority.contains("крит") || priority.contains("высок");
    }

    private boolean isBug(JsonNode issue) {
        String type = issue.path("fields").path("issuetype").path("name").asText("").toLowerCase(Locale.ROOT);
        return type.contains("bug") || type.contains("ошиб") || type.contains("баг");
    }

    private boolean isCriticalBug(JsonNode issue) {
        if (!isBug(issue) || !isHighPriority(issue)) return false;
        String priority = issue.path("fields").path("priority").path("name").asText("").toLowerCase(Locale.ROOT);
        return priority.contains("critical") || priority.contains("highest") || priority.contains("blocker") || priority.contains("крит");
    }

    private Optional<Map<String, Object>> baselineProjectMetric(String projectKey, int days) {
        Optional<Map<String, Object>> snapshot = priorProjectMetric(projectKey, days);
        if (snapshot.isPresent()) return snapshot;
        return historicalProjectMetric(projectKey, days);
    }

    private Optional<Map<String, Object>> priorProjectMetric(String projectKey, int days) {
        try {
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT metric_date, health_score, blocked, stuck, testing, released, total, release_readiness " +
                            "FROM project_metrics_daily WHERE project_key = ? AND metric_date <= ? ORDER BY metric_date DESC LIMIT 1",
                    (rs, rowNum) -> linkedMap(
                            "metric_date", rs.getDate("metric_date").toLocalDate().toString(),
                            "baseline_at", rs.getDate("metric_date").toLocalDate().toString(),
                            "baseline_source", "ARC_SNAPSHOT",
                            "history_coverage_percent", 100,
                            "health_score", rs.getInt("health_score"),
                            "blocked", rs.getInt("blocked"),
                            "stuck", rs.getInt("stuck"),
                            "testing", rs.getInt("testing"),
                            "released", rs.getInt("released"),
                            "total", rs.getInt("total"),
                            "release_readiness", rs.getInt("release_readiness")
                    ),
                    projectKey.toUpperCase(Locale.ROOT), LocalDate.now().minusDays(days)
            );
            return rows.stream().findFirst();
        } catch (DataAccessException ignored) {
            return Optional.empty();
        }
    }

    private synchronized Optional<Map<String, Object>> historicalProjectMetric(String projectKey, int days) {
        String key = projectKey.toUpperCase(Locale.ROOT);
        String cacheKey = key + ":" + days;
        HistoricalMetricCache cached = historicalMetricCache.get(cacheKey);
        if (cached != null && Duration.between(cached.createdAt(), Instant.now()).toMinutes() < 5) {
            return Optional.of(cached.value());
        }

        ProjectContext project = context(key);
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        Set<String> scopeKeys = project.scope().stream()
                .map(issue -> issue.path("key").asText())
                .collect(java.util.stream.Collectors.toSet());
        List<JsonNode> jiraHistory;
        try {
            jiraHistory = jira.projectIssuesWithChangelog(key);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }

        List<JsonNode> scopedHistory = jiraHistory.stream()
                .filter(issue -> scopeKeys.contains(issue.path("key").asText()))
                .toList();
        int coverage = scopeKeys.isEmpty() ? 100 : Metrics.clamp(scopedHistory.size() * 100.0 / scopeKeys.size());
        if (!scopeKeys.isEmpty() && scopedHistory.isEmpty()) return Optional.empty();

        List<HistoricalIssue> issues = scopedHistory.stream()
                .map(this::completeChangelog)
                .map(issue -> historicalIssue(issue, cutoff))
                .flatMap(Optional::stream)
                .toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (HistoricalIssue issue : issues) counts.merge(issue.status(), 1, Integer::sum);
        int total = issues.size();
        int completed = counts.getOrDefault("DONE", 0) + counts.getOrDefault("RELEASED", 0);
        int devDone = counts.getOrDefault("DEV_DONE", 0);
        int testing = counts.getOrDefault("TESTING", 0);
        int review = counts.getOrDefault("REVIEW", 0);
        int inProgress = counts.getOrDefault("IN_PROGRESS", 0);
        int blocked = counts.getOrDefault("BLOCKED", 0);
        int stuck = (int) issues.stream().filter(issue ->
                !COMPLETED.contains(issue.status())
                        && ChronoUnit.DAYS.between(issue.enteredStatusAt(), cutoff) >= 3
        ).count();
        int criticalBugs = (int) issues.stream().filter(HistoricalIssue::criticalBug).count();

        int sprintHealth = Metrics.sprintHealth(total, completed, devDone, testing, review, inProgress, blocked, stuck);
        int releaseReadiness = Metrics.releaseReadiness(
                total, completed, devDone, blocked, testing, criticalBugs, stuck
        );
        int blockedHealth = Metrics.clamp(100 - ratio(blocked, total) * 200);
        int stuckHealth = Metrics.clamp(100 - ratio(stuck, total) * 120);
        int peopleLoadHealth = historicalPeopleLoadHealth(issues);
        long newTasks = issues.stream().filter(issue ->
                issue.createdAt().isAfter(cutoff.minus(7, ChronoUnit.DAYS))
        ).count();
        int scopeStability = Metrics.clamp(100 - ratio((int) newTasks, total) * 150);
        int healthScore = Metrics.projectHealth(
                sprintHealth, blockedHealth, stuckHealth, peopleLoadHealth, scopeStability, releaseReadiness
        );

        Map<String, Object> value = linkedMap(
                "metric_date", cutoff.atOffset(ZoneOffset.ofHours(6)).toLocalDate().toString(),
                "baseline_at", cutoff.toString(),
                "baseline_source", "JIRA_CHANGELOG",
                "history_coverage_percent", coverage,
                "health_score", healthScore,
                "blocked", blocked,
                "stuck", stuck,
                "testing", testing,
                "released", completed,
                "total", total,
                "release_readiness", releaseReadiness
        );
        historicalMetricCache.put(cacheKey, new HistoricalMetricCache(value, Instant.now()));
        return Optional.of(value);
    }

    private JsonNode completeChangelog(JsonNode issue) {
        JsonNode changelog = issue.path("changelog");
        if (changelog.path("total").asInt(0) <= changelog.path("histories").size()) return issue;
        try {
            return jira.issueWithChangelog(issue.path("key").asText());
        } catch (RuntimeException ignored) {
            return issue;
        }
    }

    private Optional<HistoricalIssue> historicalIssue(JsonNode issue, Instant cutoff) {
        JsonNode fields = issue.path("fields");
        Instant createdAt = parseInstant(fields.path("created").asText());
        if (createdAt.isAfter(cutoff)) return Optional.empty();

        List<JsonNode> histories = new ArrayList<>();
        issue.path("changelog").path("histories").forEach(histories::add);
        histories.sort(Comparator.comparing(history -> parseInstant(history.path("created").asText())));

        String status = normalizeStatus(fields.path("status").path("name").asText());
        String assignee = fields.path("assignee").path("displayName").asText("");
        Instant enteredStatusAt = createdAt;
        boolean initialStatusFound = false;
        boolean initialAssigneeFound = false;

        for (JsonNode history : histories) {
            for (JsonNode item : history.path("items")) {
                String field = item.path("field").asText("").toLowerCase(Locale.ROOT);
                if (field.equals("status") && !initialStatusFound) {
                    String initial = item.path("fromString").asText("");
                    if (!initial.isBlank()) status = normalizeStatus(initial);
                    initialStatusFound = true;
                }
                if (field.equals("assignee") && !initialAssigneeFound) {
                    assignee = item.path("fromString").asText("");
                    initialAssigneeFound = true;
                }
            }
        }

        for (JsonNode history : histories) {
            Instant changedAt = parseInstant(history.path("created").asText());
            if (changedAt.isAfter(cutoff)) break;
            for (JsonNode item : history.path("items")) {
                String field = item.path("field").asText("").toLowerCase(Locale.ROOT);
                if (field.equals("status")) {
                    String next = item.path("toString").asText("");
                    if (!next.isBlank()) status = normalizeStatus(next);
                    enteredStatusAt = changedAt;
                }
                if (field.equals("assignee")) assignee = item.path("toString").asText("");
            }
        }

        return Optional.of(new HistoricalIssue(
                status,
                assignee,
                createdAt,
                enteredStatusAt,
                isHighPriority(issue),
                isCriticalBug(issue)
        ));
    }

    private int historicalPeopleLoadHealth(List<HistoricalIssue> issues) {
        Map<String, int[]> people = new LinkedHashMap<>();
        for (HistoricalIssue issue : issues) {
            if (COMPLETED.contains(issue.status()) || issue.assignee().isBlank()
                    || "administrator".equalsIgnoreCase(issue.assignee())) continue;
            int[] load = people.computeIfAbsent(issue.assignee(), ignored -> new int[3]);
            if (!Metrics.isDeveloperActiveStatus(issue.status())) continue;
            load[0]++;
            if ("BLOCKED".equals(issue.status())) load[1]++;
            if (issue.highPriority()) load[2]++;
        }
        double averageLoad = people.values().stream()
                .mapToInt(load -> Metrics.clamp(load[0] * 9 + load[1] * 15 + load[2] * 7))
                .average()
                .orElse(50);
        return Metrics.clamp(100 - Math.max(0, averageLoad - 70) * 2);
    }

    private void addAnomaly(List<Map<String, Object>> anomalies, String metric, int before, int now) {
        int delta = now - before;
        double percent = before == 0 ? (now == 0 ? 0 : 100) : delta * 100.0 / before;
        if (Math.abs(percent) >= 25 || Math.abs(delta) >= 3) {
            anomalies.add(linkedMap(
                    "metric", metric,
                    "before", before,
                    "now", now,
                    "delta", delta,
                    "change_percent", (int) Math.round(percent),
                    "severity", Math.abs(percent) >= 75 ? "HIGH" : "MEDIUM"
            ));
        }
    }

    private String required(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.toString();
    }

    private double ratio(int value, int total) {
        return total <= 0 ? 0 : value * 100.0 / total;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return Instant.EPOCH;
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX")).toInstant();
            } catch (DateTimeParseException second) {
                return Instant.EPOCH;
            }
        }
    }

    private static Map<String, Object> linkedMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) map.put(values[index].toString(), values[index + 1]);
        return map;
    }

    private record SprintRef(String id, String name) {}
    private record ProjectContext(String key, String name, List<JsonNode> all, List<JsonNode> scope,
                                  String sprintId, String sprintName, String sprintState) {}
    private record ContextCache(ProjectContext value, Instant createdAt) {}
    private record HistoricalMetricCache(Map<String, Object> value, Instant createdAt) {}
    private record HistoricalIssue(String status, String assignee, Instant createdAt, Instant enteredStatusAt,
                                   boolean highPriority, boolean criticalBug) {}

    private static final class PersonAccumulator {
        private final String name;
        private final String username;
        private final Map<String, Integer> projects = new LinkedHashMap<>();
        private final Set<String> activeProjects = new LinkedHashSet<>();
        private final Set<String> taskKeys = new LinkedHashSet<>();
        private String role = "Не указана";
        private int activeTasks;
        private int testingTasks;
        private int doneDevTasks;
        private int blockedTasks;
        private int highPriority;

        private PersonAccumulator(String name, String username) {
            this.name = name;
            this.username = username;
        }
    }
}
