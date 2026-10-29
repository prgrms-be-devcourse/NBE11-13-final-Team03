package com.team3.backend.tracking.service;

import com.team3.backend.tracking.config.EventFanoutProperties;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class EventFanoutPropertiesTest {
    @Test
    void rejectsOversizedPagesAndUnboundedOrInvalidRetrySettings() {
        assertThatThrownBy(() -> properties(501, Duration.ofMinutes(30), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(0, Duration.ofMinutes(30), 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(500, Duration.ZERO, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(500, Duration.ofMinutes(30), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(properties(500, Duration.ofMinutes(30), 5).enabled()).isFalse();
    }

    private EventFanoutProperties properties(int size, Duration lease, int attempts) {
        return new EventFanoutProperties(false, size, 10, lease, Duration.ofMinutes(1), attempts);
    }
}
