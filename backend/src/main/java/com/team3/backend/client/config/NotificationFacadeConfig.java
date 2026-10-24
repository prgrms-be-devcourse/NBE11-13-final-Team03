package com.team3.backend.client.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationFacadeProperties.class)
@PropertySource("classpath:notification-http.properties")
public class NotificationFacadeConfig { }
