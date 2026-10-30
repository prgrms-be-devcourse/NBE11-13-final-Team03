package com.team3.backend.user.service;

import org.junit.jupiter.api.Tag;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.outbox.domain.entity.*;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.user.domain.entity.*;
import com.team3.backend.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class UserWithdrawalOutboxPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired OutboxEventRepository outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void disposableDatabaseOnly() {
        jdbc.execute("TRUNCATE users, outbox_events RESTART IDENTITY CASCADE");
    }

    @Test
    void withdrawalCancelsOnlyPendingNotificationRequestsAndDurableControlSurvivesDeletion() {
        long user = user(101), other = user(102);
        long pending = notification(user), publishing = notification(user), published = notification(user);
        long otherPending = notification(other);
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHING' WHERE id = ?", publishing);
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?", published);
        service.withdraw(user);
        assertThat(users.existsById(user)).isFalse();
        assertThat(state(pending)).isEqualTo("SKIPPED");
        assertThat(state(publishing)).isEqualTo("PUBLISHING");
        assertThat(state(published)).isEqualTo("PUBLISHED");
        assertThat(state(otherPending)).isEqualTo("PENDING");
        var control = outboxes.findAll().stream().filter(o -> o.getEventType().equals("USER_WITHDRAWN")).toList();
        assertThat(control).hasSize(1);
        assertThat(control.getFirst().getUserId()).isEqualTo(user);
        assertThat(control.getFirst().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(control.getFirst().getPayload()).doesNotContainKeys("expiresAt", "title", "opportunityId");
        assertThat(control.getFirst().getNextRetryAt()).isNotNull();
    }

    @Test
    void failureToPersistControlRollsBackCancellationAndUserDeletion() {
        long user = user(201), pending = notification(user);
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_withdrawal CHECK(event_type <> 'USER_WITHDRAWN')");
        try {
            assertThatThrownBy(() -> service.withdraw(user)).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_withdrawal"); }
        assertThat(users.existsById(user)).isTrue();
        assertThat(state(pending)).isEqualTo("PENDING");
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void failureDeletingUserRollsBackAlreadyFlushedControlAndCancellation() {
        long user = user(301), pending = notification(user);
        jdbc.execute("""
                CREATE FUNCTION reject_withdrawal_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test delete failure'; END; $$;
                CREATE TRIGGER reject_withdrawal_delete BEFORE DELETE ON users
                FOR EACH ROW EXECUTE FUNCTION reject_withdrawal_delete();
                """);
        try {
            assertThatThrownBy(() -> service.withdraw(user)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_withdrawal_delete ON users; DROP FUNCTION reject_withdrawal_delete()");
        }
        assertThat(users.existsById(user)).isTrue();
        assertThat(state(pending)).isEqualTo("PENDING");
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void concurrentWithdrawalsProduceOneControlAndOneNotFoundWithoutDuplicateDeletion() throws Exception {
        long user = user(401);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> task = () -> {
                start.await();
                try { service.withdraw(user); return "withdrawn"; }
                catch (BusinessException missing) { return missing.getErrorCode().getCode(); }
            };
            var a = pool.submit(task); var b = pool.submit(task); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .contains("withdrawn").doesNotHaveDuplicates();
        }
        assertThat(users.existsById(user)).isFalse();
        assertThat(outboxes.count()).isEqualTo(1);
    }

    @Test
    void fanoutUserLockKeepsCommittedOutboxVisibleToFollowingWithdrawal() throws Exception {
        long user = user(501);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var fanout = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                users.lockNotificationRecipients(List.of(user));
                long id = notification(user);
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return id;
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var withdrawal = pool.submit(() -> service.withdraw(user));
                release.countDown();
                long id = fanout.get(10, TimeUnit.SECONDS);
                withdrawal.get(10, TimeUnit.SECONDS);
                assertThat(state(id)).isEqualTo("SKIPPED");
            } finally { release.countDown(); }
        }
    }

    private long user(long kakaoId) {
        return users.saveAndFlush(User.builder().kakaoId(kakaoId).role(Role.USER).build()).getId();
    }
    private long notification(long user) {
        return outboxes.saveAndFlush(OutboxEvent.notification(UUID.randomUUID(), UUID.randomUUID(), user,
                10L, "DEADLINE_SOON", Map.of("fixture", true), clock.instant())).getId();
    }
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id); }
}
