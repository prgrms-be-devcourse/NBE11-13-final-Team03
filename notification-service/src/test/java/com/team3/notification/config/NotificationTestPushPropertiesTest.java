package com.team3.notification.config;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class NotificationTestPushPropertiesTest {
    @Test
    void disabledNeedsNeitherKeyNorAllowlist() {
        assertThat(new NotificationTestPushProperties(false, "", null).allowedUserIds()).isEmpty();
    }

    @Test
    void enablingRequiresBothDedicatedKeyAndPositiveAllowlistAndRedactsKey() {
        assertThatThrownBy(() -> new NotificationTestPushProperties(true, "", Set.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationTestPushProperties(true, "private-key", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationTestPushProperties(true, "private-key", Set.of(0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationTestPushProperties(true, "key\n", Set.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new NotificationTestPushProperties(true, "private-key", Set.of(1L)).toString())
                .doesNotContain("private-key");
    }
}
