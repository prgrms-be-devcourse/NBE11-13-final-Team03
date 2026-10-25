package com.team3.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;

@ConfigurationProperties("yon.notification.push.slots")
public record NotificationSlotProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String inboxPath, @DefaultValue("10") int usersPerRun,
        @DefaultValue("100") int itemsPerBundle) {
    public NotificationSlotProperties {
        if (usersPerRun < 1 || usersPerRun > 100 || itemsPerBundle < 1 || itemsPerBundle > 500) {
            throw new IllegalArgumentException("Invalid push slot work budget");
        }
        if (enabled) {
            var uri = URI.create(inboxPath);
            var path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || path == null || !path.startsWith("/")
                    || path.startsWith("//") || path.contains("\\") || uri.getQuery() != null || uri.getFragment() != null
                    || path.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Verified internal inbox-path required for push slots");
            }
        }
    }
}
