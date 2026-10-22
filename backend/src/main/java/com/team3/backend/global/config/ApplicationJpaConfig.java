package com.team3.backend.global.config;

import com.team3.backend.global.role.ApiRole;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@ApiRole
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = {
        "com.team3.backend.auth",
        "com.team3.backend.collection",
        "com.team3.backend.eligibility",
        "com.team3.backend.interest",
        "com.team3.backend.opportunity",
        "com.team3.backend.preference",
        "com.team3.backend.region",
        "com.team3.backend.tracking",
        "com.team3.backend.user"
})
@EnableJpaRepositories(basePackages = {
        "com.team3.backend.auth",
        "com.team3.backend.collection",
        "com.team3.backend.eligibility",
        "com.team3.backend.interest",
        "com.team3.backend.opportunity",
        "com.team3.backend.preference",
        "com.team3.backend.region",
        "com.team3.backend.search",
        "com.team3.backend.tracking",
        "com.team3.backend.user"
})
public class ApplicationJpaConfig {
}
