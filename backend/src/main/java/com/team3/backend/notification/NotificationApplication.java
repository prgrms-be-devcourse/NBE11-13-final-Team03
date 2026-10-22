package com.team3.backend.notification;

import com.team3.backend.global.role.ApplicationRoleInitializer;
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
        application.addInitializers(new ApplicationRoleInitializer("notification"));
        application.run(args);
    }
}
