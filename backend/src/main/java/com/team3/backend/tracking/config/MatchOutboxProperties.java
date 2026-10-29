package com.team3.backend.tracking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("yon.tracking.match.outbox")
public record MatchOutboxProperties(@DefaultValue("false") boolean enabled,@DefaultValue("500") int maxSummaryCandidates) {
    public MatchOutboxProperties {
        if (maxSummaryCandidates<1 || maxSummaryCandidates>500) throw new IllegalArgumentException("Summary transaction budget must be 1..500");
    }
}
