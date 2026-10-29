package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;

@ConfigurationProperties("yon.tracking.match")
public record MatchTrackingProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue Possibly possibly, @DefaultValue("") String summaryLink) {
    public record Possibly(@DefaultValue("1") int maxUnresolved, @DefaultValue("true") boolean requireInterest) {
        public Possibly {
            if (maxUnresolved < 0) throw new IllegalArgumentException("Unresolved limit must be non-negative");
        }
    }
    public MatchTrackingProperties {
        if (possibly == null) throw new IllegalArgumentException("Possibly policy required");
        if (summaryLink == null) throw new IllegalArgumentException("Summary link required");
        if (enabled || !summaryLink.isEmpty()) {
            var uri=URI.create(summaryLink); var path=uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority()!=null || path==null || !path.startsWith("/")
                    || path.startsWith("//") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)
                    || uri.getQuery()!=null || uri.getFragment()!=null) {
                throw new IllegalArgumentException("A verified internal recommendation path is required");
            }
        }
    }
}
