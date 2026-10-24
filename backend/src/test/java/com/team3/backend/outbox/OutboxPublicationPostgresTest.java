package com.team3.backend.outbox;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import com.team3.backend.outbox.publisher.*;
import com.team3.backend.outbox.service.*;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({OutboxPublicationStore.class, OutboxPublicationWorker.class, OutboxPublicationPostgresTest.Beans.class})
class OutboxPublicationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired OutboxPublicationStore store;
    @Autowired OutboxPublicationWorker worker;
    @Autowired OutboxEventRepository outboxes;
    @Autowired NotificationMessagePublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestClock clock;

    @BeforeEach
    void resetDisposableDatabase() {
        jdbc.execute("TRUNCATE outbox_events RESTART IDENTITY");
        clock.now = NOW;
        reset(publisher);
    }

    @Test
    void publishesOnlyAfterCommitAndOutsideAnyDatabaseTransaction() throws Exception {
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            save();
            worker.runOnce(); // Outer transaction is suspended; the uncommitted row is invisible.
            verifyNoInteractions(publisher);
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PUBLISHING");
            return null;
        }).when(publisher).publish(any());
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL AND lease_token IS NULL FROM outbox_events", Boolean.class)).isTrue();
    }

    @Test
    void configurationErrorReturnsToPendingWithoutSpendingAnAttempt() {
        save();
        doThrow(NotificationPublicationException.configuration("HTTP_401")).when(publisher).publish(any());
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events", String.class)).isEqualTo("HTTP_401");
        assertThat(jdbc.queryForObject("SELECT lease_token IS NULL AND next_retry_at > ? FROM outbox_events",
                Boolean.class, java.sql.Timestamp.from(NOW))).isTrue();
    }

    @Test
    void transientFailureWaitsForRetryAndKeepsSameMessageId() {
        save();
        UUID identity = outboxes.findAll().getFirst().getMessageId();
        doThrow(new NotificationPublicationException("HTTP_503", true)).when(publisher).publish(any());
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isEqualTo(1);
        worker.runOnce();
        verify(publisher, times(1)).publish(any());
        reset(publisher);
        clock.now = NOW.plusSeconds(60);
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(outboxes.findAll().getFirst().getMessageId()).isEqualTo(identity);
    }

    @Test
    void permanentErrorsAndExhaustedRetriesAreFailedWithoutLosingTheRow() {
        save();
        doThrow(new NotificationPublicationException("HTTP_401", false)).when(publisher).publish(any());
        worker.runOnce();
        assertThat(state()).isEqualTo("FAILED");
        jdbc.update("UPDATE outbox_events SET status='PENDING', retry_count=2");
        clock.now = NOW.plusSeconds(60);
        doThrow(new NotificationPublicationException("HTTP_IO_FAILURE", true)).when(publisher).publish(any());
        worker.runOnce();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isEqualTo(3);
    }

    @Test
    void repeatedTransientFailuresIncreaseBackoff() {
        save();
        doThrow(new NotificationPublicationException("HTTP_503", true)).when(publisher).publish(any());
        worker.runOnce();
        clock.now = NOW.plusSeconds(60);
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT next_retry_at FROM outbox_events", java.sql.Timestamp.class).toInstant())
                .isEqualTo(NOW.plusSeconds(180));
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isEqualTo(2);
    }

    @Test
    void rolledBackOutboxIsNeverPublished() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            save();
            status.setRollbackOnly();
        });
        worker.runOnce();
        assertThat(outboxes.count()).isZero();
        verifyNoInteractions(publisher);
    }

    @Test
    void publisherCrashAfterRemoteSuccessRecoversAndOldLeaseCannotOverwriteNewOwner() {
        save();
        var crashed = store.claimNext().orElseThrow();
        // Simulates remote commit followed by publisher crash before marking PUBLISHED.
        clock.now = NOW.plusSeconds(61);
        assertThat(store.recoverStale()).isEqualTo(1);
        assertThat(store.published(crashed)).isFalse();
        assertThat(store.failed(crashed, "STALE_WORKER", true)).isFalse();
        clock.now = NOW.plusSeconds(121);
        var resumed = store.claimNext().orElseThrow();
        assertThat(resumed.messageId()).isEqualTo(crashed.messageId());
        assertThat(resumed.leaseToken()).isNotEqualTo(crashed.leaseToken());
        assertThat(store.published(crashed)).isFalse();
        assertThat(store.published(resumed)).isTrue();
    }

    @Test
    void crashedLastAttemptEventuallyFailsRatherThanBeingStuckPublishing() {
        save();
        jdbc.update("UPDATE outbox_events SET retry_count=2");
        store.claimNext().orElseThrow();
        clock.now = NOW.plusSeconds(61);
        store.recoverStale();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(store.claimNext()).isEmpty();
    }

    @Test
    void concurrentClaimersHaveExactlyOneOwner() throws Exception {
        save();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> claim = () -> { start.await(10, TimeUnit.SECONDS); return store.claimNext().isPresent(); };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    private String state() { return jdbc.queryForObject("SELECT status FROM outbox_events", String.class); }

    private void save() {
        try {
            String json = new ClassPathResource("contracts/notification-requested-v1.json")
                    .getContentAsString(StandardCharsets.UTF_8);
            Map<String, Object> envelope = JsonMapper.builder().build().readValue(json, Map.class);
            outboxes.saveAndFlush(OutboxEvent.notification(UUID.fromString((String) envelope.get("messageId")),
                    UUID.fromString((String) envelope.get("sourceEventId")), 7L, 10L, "DEADLINE_SOON", envelope, NOW));
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean TestClock clock() { return new TestClock(); }
        @Bean NotificationPublisherProperties properties() { return HttpNotificationPublisherTest.properties(8081); }
        @Bean NotificationMessagePublisher publisher() { return mock(NotificationMessagePublisher.class); }
    }

    static class TestClock extends Clock {
        volatile Instant now = NOW;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
