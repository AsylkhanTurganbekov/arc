package kz.belesai.arc.snapshot;

import kz.belesai.arc.analytics.AnalyticsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SnapshotService {
    private final AnalyticsService analytics;
    private final JdbcTemplate jdbc;

    public SnapshotService(AnalyticsService analytics, JdbcTemplate jdbc) {
        this.analytics = analytics;
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 86_400_000)
    public void scheduledSnapshot() {
        saveSnapshot();
    }

    public Map<String, Object> saveSnapshot() {
        LocalDate today = LocalDate.now();
        int projectCount = 0;
        for (Map<String, Object> project : analytics.projects()) {
            String key = project.get("key").toString();
            try {
                Map<String, Object> health = analytics.projectHealth(key);
                Map<String, Object> sprint = analytics.sprintHealth(key);
                Map<String, Object> release = analytics.releaseReadiness(key);
                jdbc.update("""
                        INSERT INTO project_metrics_daily
                        (metric_date, project_key, health_score, blocked, stuck, testing, released, total, release_readiness)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (metric_date, project_key) DO UPDATE SET
                          health_score = EXCLUDED.health_score,
                          blocked = EXCLUDED.blocked,
                          stuck = EXCLUDED.stuck,
                          testing = EXCLUDED.testing,
                          released = EXCLUDED.released,
                          total = EXCLUDED.total,
                          release_readiness = EXCLUDED.release_readiness
                        """,
                        today, key, health.get("health_score"), health.get("blocked"), health.get("stuck"),
                        health.get("testing"), (int) sprint.get("done_prod") + (int) sprint.get("done"),
                        sprint.get("total"), release.get("score")
                );
                jdbc.update("""
                        INSERT INTO sprint_metrics_daily
                        (metric_date, project_key, sprint_id, sprint_name, health_score, total, completed)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (metric_date, project_key, sprint_id) DO UPDATE SET
                          sprint_name = EXCLUDED.sprint_name,
                          health_score = EXCLUDED.health_score,
                          total = EXCLUDED.total,
                          completed = EXCLUDED.completed
                        """,
                        today, key, sprint.get("sprint_id"), sprint.get("name"), sprint.get("health_score"),
                        sprint.get("total"), (int) sprint.get("done_prod") + (int) sprint.get("done")
                );
                projectCount++;
            } catch (Exception ignored) {
                // One inaccessible Jira project must not prevent snapshots for the rest of the portfolio.
            }
        }

        List<Map<String, Object>> people = analytics.peopleLoad(null);
        for (Map<String, Object> person : people) {
            jdbc.update("""
                    INSERT INTO employee_metrics_daily
                    (metric_date, employee, load_score, active_tasks, blocked_tasks, project_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (metric_date, employee) DO UPDATE SET
                      load_score = EXCLUDED.load_score,
                      active_tasks = EXCLUDED.active_tasks,
                      blocked_tasks = EXCLUDED.blocked_tasks,
                      project_count = EXCLUDED.project_count
                    """,
                    today, person.get("employee"), person.get("load_score"), person.get("active_tasks"),
                    person.get("blocked_tasks"), person.get("project_count")
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SAVED");
        result.put("date", today.toString());
        result.put("projects", projectCount);
        result.put("employees", people.size());
        return result;
    }
}
