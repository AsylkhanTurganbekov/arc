package kz.belesai.arc.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import kz.belesai.arc.analytics.AnalyticsService;
import kz.belesai.arc.snapshot.SnapshotService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
public class ArcController {
    private final AnalyticsService analytics;
    private final SnapshotService snapshots;

    public ArcController(AnalyticsService analytics, SnapshotService snapshots) {
        this.analytics = analytics;
        this.snapshots = snapshots;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return analytics.health(); }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() { return analytics.projects(); }

    @GetMapping("/portfolio/health")
    public Map<String, Object> portfolioHealth() { return analytics.portfolioHealth(); }

    @GetMapping("/projects/{project}/health")
    public Map<String, Object> projectHealth(@PathVariable String project) { return analytics.projectHealth(project); }

    @GetMapping("/projects/{project}/sprint")
    public Map<String, Object> sprintHealth(@PathVariable String project) { return analytics.sprintHealth(project); }

    @GetMapping("/people")
    public List<Map<String, Object>> peopleLoad(@RequestParam(required = false) String project) {
        return project == null || project.isBlank()
                ? analytics.peopleDirectory()
                : analytics.peopleLoad(project);
    }

    @GetMapping("/people/{employee}")
    public Map<String, Object> employeeLoad(@PathVariable String employee) { return analytics.employeeLoad(employee); }

    @PutMapping("/people/{employee}/position")
    public Map<String, Object> updateEmployeePosition(
            @PathVariable String employee,
            @RequestBody Map<String, Object> input
    ) {
        return analytics.updateEmployeePosition(employee, input);
    }

    @PutMapping("/people/position")
    public Map<String, Object> updateEmployeePosition(@RequestBody Map<String, Object> input) {
        Object rawEmployee = input.get("employee");
        return analytics.updateEmployeePosition(rawEmployee == null ? "" : rawEmployee.toString(), input);
    }

    @GetMapping("/projects/{project}/stuck")
    public List<Map<String, Object>> stuckTasks(
            @PathVariable String project,
            @RequestParam(defaultValue = "3") @Min(1) @Max(90) int minDays
    ) { return analytics.stuckTasks(project, minDays); }

    @GetMapping("/projects/{project}/blocked")
    public List<Map<String, Object>> blockedTasks(@PathVariable String project) { return analytics.blockedTasks(project); }

    @GetMapping("/projects/{project}/release-readiness")
    public Map<String, Object> releaseReadiness(@PathVariable String project) { return analytics.releaseReadiness(project); }

    @GetMapping("/projects/{project}/anomalies")
    public Map<String, Object> anomalies(
            @PathVariable String project,
            @RequestParam(defaultValue = "7") @Min(1) @Max(90) int periodDays
    ) { return analytics.projectAnomalies(project, periodDays); }

    @GetMapping("/projects/{project}/delivery-management")
    public Map<String, Object> deliveryManagement(@PathVariable String project) {
        return analytics.deliveryManagement(project);
    }

    @GetMapping("/projects/{project}/weekly-review")
    public Map<String, Object> weeklyReview(@PathVariable String project) { return analytics.weeklyReview(project); }

    @PostMapping("/simulations/resource-move")
    public Map<String, Object> simulate(@RequestBody Map<String, Object> input) {
        return analytics.simulateResourceMove(input);
    }

    @GetMapping("/resource-plan")
    public Map<String, Object> resourcePlan(@RequestParam(defaultValue = "next_week") String period) {
        return analytics.resourcePlan(period);
    }

    @GetMapping("/briefing")
    public Map<String, Object> briefing() { return analytics.briefing(); }

    @PostMapping("/snapshots")
    public Map<String, Object> snapshot() { return snapshots.saveSnapshot(); }

    @PostMapping("/sync/{project}")
    public ResponseEntity<Map<String, Object>> sync(@PathVariable String project) {
        analytics.evictProject(project);
        return ResponseEntity.accepted().body(Map.of("status", "REFRESHED", "project", project.toUpperCase()));
    }
}
