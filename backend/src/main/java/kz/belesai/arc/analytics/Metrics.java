package kz.belesai.arc.analytics;

public final class Metrics {
    private Metrics() {}

    public static int clamp(double value) {
        return (int) Math.round(Math.max(0, Math.min(100, value)));
    }

    public static boolean isDeveloperActiveStatus(String status) {
        if (status == null || status.isBlank()) return false;
        return switch (status) {
            case "TESTING", "DEV_DONE", "RELEASED", "DONE" -> false;
            default -> true;
        };
    }

    public static int sprintHealth(int total, int completed, int devDone, int testing, int review,
                                   int inProgress, int blocked, int stuck) {
        if (total <= 0) return 100;
        double progress = (completed + devDone * 0.75 + testing * 0.5 + review * 0.3 + inProgress * 0.2)
                / total * 100.0;
        double blockedPenalty = blocked * 175.0 / total;
        double stuckPenalty = stuck * 80.0 / total;
        return clamp(progress - blockedPenalty - stuckPenalty);
    }

    public static int releaseReadiness(int total, int completed, int devDone, int blocked,
                                       int testing, int criticalBugs, int stuck) {
        if (total <= 0) return 100;
        double delivery = (completed + devDone * 0.75) / total * 100.0;
        double penalty = blocked * 50.0 / total + testing * 10.0 / total
                + criticalBugs * 5.0 + stuck * 15.0 / total;
        return clamp(delivery - penalty);
    }

    public static int projectHealth(int sprint, int blockedHealth, int stuckHealth, int peopleLoadHealth,
                                    int scopeStability, int releaseReadiness) {
        return clamp(sprint * .25 + blockedHealth * .20 + stuckHealth * .15
                + peopleLoadHealth * .15 + scopeStability * .10 + releaseReadiness * .15);
    }
}
