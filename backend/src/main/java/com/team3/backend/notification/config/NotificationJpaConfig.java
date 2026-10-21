package com.team3.backend.notification.config;

import com.team3.backend.global.role.NotificationRole;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@NotificationRole
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = {
        "com.team3.backend.notification"
})
@EnableJpaRepositories(basePackages = {
        "com.team3.backend.notification"
})
public class NotificationJpaConfig {
}