package com.team3.backend.auth.unlink;

import org.junit.jupiter.api.Tag;

import com.team3.backend.auth.oauth2.*;
import com.team3.backend.auth.service.KakaoLoginTransaction;
import com.team3.backend.user.domain.entity.*;
import com.team3.backend.user.domain.repository.UserRepository;
import com.team3.backend.user.service.UserService;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties={"app.scheduling.enabled=false","app.kakao-unlink.enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("postgres")
class KakaoUnlinkPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Beans {
        @Bean KakaoUnlinkClient testUnlinkClient() { return mock(KakaoUnlinkClient.class); }
        @Bean KakaoUnlinkProcessor testProcessor(JdbcTemplate jdbc,KakaoUnlinkClient client,Clock clock,PlatformTransactionManager transactions) {
            return new KakaoUnlinkProcessor(jdbc,client,new KakaoUnlinkProperties(false,"",3,
                    Duration.ofSeconds(1),Duration.ofSeconds(5),1),clock,transactions);
        }
    }
    @Autowired UserService withdrawal;
    @Autowired UserRepository users;
    @Autowired KakaoLoginTransaction login;
    @Autowired KakaoUnlinkClient client;
    @Autowired KakaoUnlinkProcessor processor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void resetDisposableDatabaseOnly() {
        reset(client);
        jdbc.execute("TRUNCATE users,outbox_events,kakao_unlink_tasks RESTART IDENTITY CASCADE");
    }
    private long user(long kakao) {
        return users.saveAndFlush(User.builder().kakaoId(kakao).role(Role.USER).email("test@example.com").build()).getId();
    }
    private String status() { return jdbc.queryForObject("SELECT status FROM kakao_unlink_tasks",String.class); }
    private void due() { jdbc.update("UPDATE kakao_unlink_tasks SET next_attempt_at=now()-interval '1 second'"); }
    private User login(long kakao,Instant requestedAt) {
        return login.login(new KakaoUserInfo(Map.of("id",kakao,"kakao_account",Map.of("email","test@example.com"),
                "properties",Map.of("nickname","test"))),AuthProvider.KAKAO,false,requestedAt);
    }

    @Test void withdrawalAtomicallyKeepsJobAndNotificationControlAfterUserDeletion() {
        long user=user(123);
        withdrawal.withdraw(user);
        assertThat(users.existsById(user)).isFalse();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type='USER_WITHDRAWN'",Long.class)).isEqualTo(1);
        verifyNoInteractions(client);
    }
    @Test void deletionFailureRollsBackJobAndNotificationControlTogether() {
        long user=user(123);
        jdbc.execute("CREATE TABLE deletion_blocker(user_id bigint REFERENCES users(id))");
        jdbc.update("INSERT INTO deletion_blocker VALUES (?)",user);
        try {
            assertThatThrownBy(()->withdrawal.withdraw(user)).isInstanceOf(RuntimeException.class);
            assertThat(users.existsById(user)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM kakao_unlink_tasks",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Long.class)).isZero();
        } finally { jdbc.execute("DROP TABLE deletion_blocker"); }
    }
    @Test void loginBlockedUntilReceiptAndOldUserInfoCannotRecreateRevokedIdentity() {
        withdrawal.withdraw(user(123));
        assertThatThrownBy(()->login(123,Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
        Instant stale=Instant.now().minusSeconds(1);
        assertThat(processor.processNext()).isTrue();
        assertThat(status()).isEqualTo("COMPLETED");
        assertThatThrownBy(()->login(123,stale)).isInstanceOf(OAuth2AuthenticationException.class);
        assertThat(login(123,Instant.now().plusSeconds(1)).getKakaoId()).isEqualTo(123);
        assertThat(processor.processNext()).isFalse();
        verify(client,times(1)).unlink(123);
    }
    @Test void retryUsesBackoffAndLimitWithoutReleasingGate() {
        withdrawal.withdraw(user(123));
        doThrow(new KakaoUnlinkClient.Failure("KAKAO_HTTP_503",true)).when(client).unlink(123);
        processor.processNext();
        assertThat(status()).isEqualTo("PENDING");
        assertThat(processor.processNext()).isFalse();
        due(); processor.processNext(); due(); processor.processNext();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM kakao_unlink_tasks",Integer.class)).isEqualTo(3);
        assertThatThrownBy(()->login(123,Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
    }
    @Test void permanentErrorStopsAndPendingWorkSurvivesProcessorReplacement() {
        withdrawal.withdraw(user(123));
        doThrow(new KakaoUnlinkClient.Failure("KAKAO_HTTP_401",false)).when(client).unlink(123);
        processor.processNext();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(processor.processNext()).isFalse();
        // Operator reconciliation/requeue, not a new withdrawal or lost in-memory queue.
        // Requeued work is already due; DB now() can be ahead of the replacement JVM clock.
        jdbc.update("UPDATE kakao_unlink_tasks SET status='PENDING',next_attempt_at=TIMESTAMP '1970-01-01 00:00:00',attempt_count=0");
        reset(client);
        new KakaoUnlinkProcessor(jdbc,client,new KakaoUnlinkProperties(false,"",3,
                Duration.ofSeconds(1),Duration.ofSeconds(5),1),Clock.systemUTC(),transactions).processNext();
        assertThat(status()).isEqualTo("COMPLETED");
    }
    @Test void concurrentWorkersNeverUnlinkSameIdentityTogether() throws Exception {
        withdrawal.withdraw(user(123));
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return null;})
                .when(client).unlink(123);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->processor.processNext());
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(()->processor.processNext()).get(5,TimeUnit.SECONDS)).isFalse();
            } finally { release.countDown(); }
            assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();
        }
        verify(client,times(1)).unlink(123);
    }
    @Test void loginWaitsForWithdrawalCommitThenRejectsRejoin() throws Exception {
        long user=user(123);
        var written=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var withdraw=pool.submit(()->new TransactionTemplate(transactions).executeWithoutResult(tx->{
                withdrawal.withdraw(user); written.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch(InterruptedException e){Thread.currentThread().interrupt(); throw new IllegalStateException(e);}
            }));
            assertThat(written.await(10,TimeUnit.SECONDS)).isTrue();
            var rejoin=pool.submit(()->login(123,Instant.now()));
            release.countDown(); withdraw.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->rejoin.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(OAuth2AuthenticationException.class);
        } finally { release.countDown(); }
    }
    @Test void secondWithdrawalReusesCompletedJobWithFreshAttempts() {
        withdrawal.withdraw(user(123)); processor.processNext();
        long rejoined=login(123,Instant.now().plusSeconds(1)).getId();
        withdrawal.withdraw(rejoined);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM kakao_unlink_tasks",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kakao_unlink_tasks",Long.class)).isEqualTo(1);
    }
    @Test void jobPersistenceFailureLeavesMemberAndNoNotificationControl() {
        long user=user(123);
        jdbc.execute("ALTER TABLE kakao_unlink_tasks ADD CONSTRAINT reject_test_job CHECK(kakao_id<>123)");
        try {
            assertThatThrownBy(()->withdrawal.withdraw(user)).isInstanceOf(RuntimeException.class);
            assertThat(users.existsById(user)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events",Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE kakao_unlink_tasks DROP CONSTRAINT reject_test_job"); }
    }
    @Test void ambiguousNetworkFailureAndInterruptedDispatchNeverAutomaticallyRetryOrUnblockLogin() {
        withdrawal.withdraw(user(123));
        doThrow(new KakaoUnlinkClient.Failure("KAKAO_OUTCOME_UNKNOWN",false)).when(client).unlink(123);
        processor.processNext();
        assertThat(status()).isEqualTo("FAILED"); due();
        assertThat(processor.processNext()).isFalse();
        assertThatThrownBy(()->login(123,Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
        // A crash after durable dispatch but before recording the external result stays blocked.
        jdbc.update("UPDATE kakao_unlink_tasks SET status='PROCESSING'");
        assertThat(processor.processNext()).isFalse();
        assertThatThrownBy(()->login(123,Instant.now())).isInstanceOf(OAuth2AuthenticationException.class);
        verify(client,times(1)).unlink(123);
    }
}
