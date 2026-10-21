package com.team3.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(
        basePackages = "com.team3.backend",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.CUSTOM,
                        classes = TypeExcludeFilter.class
                ),
                @ComponentScan.Filter(
                        type = FilterType.CUSTOM,
                        classes = AutoConfigurationExcludeFilter.class
                ),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = "com\\.team3\\.backend\\.notification\\..*"
                )
        }
)
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}