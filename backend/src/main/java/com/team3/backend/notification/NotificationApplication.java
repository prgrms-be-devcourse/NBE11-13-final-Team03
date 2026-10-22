package com.team3.backend.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication(scanBasePackages = {
        "com.team3.backend.notification",
        "com.team3.backend.global"
})
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(NotificationApplication.class);
        application.setDefaultProperties(Map.of("app.role", "notification"));
        application.run(args);
    }
}
