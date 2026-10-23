package com.team3.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients(basePackages = "com.team3.backend.client")
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BackendApplication.class);
        application.setAdditionalProfiles("database-boundary");
        application.run(args);
    }

}
