package com.team3.backend.outbox;

import com.team3.backend.client.NotificationClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;

/** Same annotation registration as BackendApplication, without starting unrelated application services. */
@TestConfiguration(proxyBeanMethods = false)
@EnableFeignClients(clients = NotificationClient.class)
class NotificationFeignTestConfiguration { }
