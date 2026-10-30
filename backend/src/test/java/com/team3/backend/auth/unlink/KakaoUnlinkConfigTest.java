package com.team3.backend.auth.unlink;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class KakaoUnlinkConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(KakaoUnlinkConfig.class)
            .withBean(JdbcTemplate.class,()->mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class,()->mock(PlatformTransactionManager.class))
            .withBean(Clock.class,Clock::systemUTC)
            .withBean(ObjectMapper.class,ObjectMapper::new);
    @Test void disabledDoesNotCreateExternalClientOrWorker() {
        context.run(c->assertThat(c).doesNotHaveBean(KakaoUnlinkClient.class).doesNotHaveBean(KakaoUnlinkWorker.class));
    }
    @Test void enabledWiresDedicatedWorkerWithoutSendingRequests() {
        context.withPropertyValues("app.kakao-unlink.enabled=true","app.kakao-unlink.admin-key=test-key")
                .run(c->assertThat(c).hasNotFailed().hasSingleBean(KakaoUnlinkWorker.class).hasSingleBean(KakaoUnlinkClient.class));
    }
    @Test void enabledWithoutKeyFailsStartup() {
        context.withPropertyValues("app.kakao-unlink.enabled=true").run(c->assertThat(c).hasFailed());
    }
}
