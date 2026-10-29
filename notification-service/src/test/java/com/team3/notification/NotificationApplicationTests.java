package com.team3.notification;

import com.team3.notification.config.NotificationSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithItsOwnSecurityAndSchedulingWithoutBackendConfiguration() {
        assertThat(context.getBeansOfType(NotificationSecurityConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
        assertThat(context.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("notification-service");
    }

    @Test
    void backendClassesAreAbsentFromTheClasspath() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver(context.getClassLoader());
        assertThat(resolver.getResources("classpath*:com/team3/backend/**/*.class")).isEmpty();
    }
}
