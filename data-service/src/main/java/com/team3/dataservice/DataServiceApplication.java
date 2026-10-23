package com.team3.dataservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DataServiceApplication {

    /** 실행 환경은 메인 서비스와 같은 DB 경계(application_user, application 스키마)를 쓴다 (docs/database-boundaries.md) */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DataServiceApplication.class);
        application.setAdditionalProfiles("database-boundary");
        application.run(args);
    }

}
