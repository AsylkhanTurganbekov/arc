package kz.belesai.arc.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTest {
    @Test
    void calculatesKindyLikeSprintWithoutUsingLlm() {
        int score = Metrics.sprintHealth(125, 69, 31, 17, 0, 1, 6, 3);
        assertThat(score).isBetween(65, 78);
    }

    @Test
    void clampsScores() {
        assertThat(Metrics.clamp(-8)).isZero();
        assertThat(Metrics.clamp(180)).isEqualTo(100);
    }

    @Test
    void countsOnlyDeveloperWorkAsActive() {
        assertThat(Metrics.isDeveloperActiveStatus("TODO")).isTrue();
        assertThat(Metrics.isDeveloperActiveStatus("IN_PROGRESS")).isTrue();
        assertThat(Metrics.isDeveloperActiveStatus("REVIEW")).isTrue();
        assertThat(Metrics.isDeveloperActiveStatus("BLOCKED")).isTrue();
        assertThat(Metrics.isDeveloperActiveStatus("WAITING")).isTrue();

        assertThat(Metrics.isDeveloperActiveStatus("TESTING")).isFalse();
        assertThat(Metrics.isDeveloperActiveStatus("DEV_DONE")).isFalse();
        assertThat(Metrics.isDeveloperActiveStatus("RELEASED")).isFalse();
        assertThat(Metrics.isDeveloperActiveStatus("DONE")).isFalse();
    }
}
