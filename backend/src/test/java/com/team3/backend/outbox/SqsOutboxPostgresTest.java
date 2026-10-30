package com.team3.backend.outbox;

import org.junit.jupiter.api.Tag;

import com.team3.backend.outbox.config.NotificationPublisherProperties;
import com.team3.backend.outbox.domain.entity.OutboxEvent;
import com.team3.backend.outbox.domain.repository.OutboxEventRepository;
import com.team3.backend.outbox.publisher.*;
import com.team3.backend.outbox.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real SDK + disposable SQS/PostgreSQL. Application tests never depend on Notification Java classes. */
@Testcontainers
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({OutboxPublicationStore.class, OutboxPublicationWorker.class, SqsOutboxPostgresTest.Beans.class})
@Tag("postgres")
class SqsOutboxPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final String SOURCE = "yon-integration-outbox";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.12.0")
            .withExposedPorts(4566).withEnv("SERVICES", "sqs").withEnv("SQS_ENDPOINT_STRATEGY", "off")
            .withEnv("SQS_DELAY_PURGE_RETRY", "0")
            .withEnv("SQS_DELAY_RECENTLY_DELETED", "0")
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    private static String endpoint() { return "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566); }
    private static String queueUrl() { return endpoint() + "/000000000000/" + SOURCE; }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean TestClock sqsClock() { return new TestClock(); }
        @Bean NotificationPublisherProperties properties() {
            return new NotificationPublisherProperties(true, "sqs", NotificationPublisherProperties.UNSET_BASE_URL, "",
                    10, 3, Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofSeconds(2));
        }
        @Bean(destroyMethod = "close") SqsClient localSqsClient() {
            return spy(SqsClient.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                            .socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10))
                            .retryPolicy(RetryPolicy.none()).build()).build());
        }
        @Bean NotificationMessagePublisher publisher(SqsClient sqs) {
            return new SqsNotificationPublisher(sqs,
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")), queueUrl(), MAPPER);
        }
    }
    static class TestClock extends Clock {
        volatile Instant now = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Instant instant() { return now; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
    @Autowired SqsClient sqs;
    @Autowired TestClock clock;
    @Autowired OutboxPublicationStore store;
    @Autowired OutboxPublicationWorker worker;
    @Autowired OutboxEventRepository outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void resetDisposableContainerDataOnly() {
        doCallRealMethod().when(sqs).sendMessage(any(SendMessageRequest.class));
        sqs.createQueue(CreateQueueRequest.builder().queueName(SOURCE)
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "120")).build());
        sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl()).build());
        jdbc.execute("TRUNCATE outbox_events RESTART IDENTITY");
        clock.now = NOW;
        clearInvocations(sqs);
    }

    @Test
    void publishesOnlyCommittedRowsOutsideDatabaseTransaction() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            saveNotification(); worker.runOnce();
            verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state()).isEqualTo("PUBLISHING");
            return invocation.callRealMethod();
        }).when(sqs).sendMessage(any(SendMessageRequest.class));
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(drain()).hasSize(1);
    }

    @Test
    void rolledBackOutboxNeverReachesQueue() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            saveNotification(); status.setRollbackOnly();
        });
        worker.runOnce();
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
        assertThat(outboxes.count()).isZero();
        assertThat(drain()).isEmpty();
    }

    @Test
    void acceptedMessageSurvivesDatabaseAcknowledgementFailureAndLeaseRecoveryKeepsIdentity() {
        saveNotification();
        OutboxPublication stale;
        jdbc.execute("ALTER TABLE outbox_events ADD CONSTRAINT reject_sqs_published CHECK (status <> 'PUBLISHED')");
        try {
            assertThatThrownBy(worker::runOnce).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(state()).isEqualTo("PUBLISHING");
            var row = outboxes.findAll().getFirst();
            stale = new OutboxPublication(row.getId(), row.getMessageId(), row.getLeaseToken(), MAPPER.writeValueAsString(row.getPayload()));
        } finally { jdbc.execute("ALTER TABLE outbox_events DROP CONSTRAINT reject_sqs_published"); }

        clock.now = NOW.plusSeconds(61);
        assertThat(store.recoverStale()).isEqualTo(1);
        assertThat(store.published(stale)).isFalse();
        clock.now = NOW.plusSeconds(121);
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        var copies = drain();
        assertThat(copies).hasSize(2);
        assertThat(copies.getFirst().body()).isEqualTo(copies.getLast().body());
        assertThat(MAPPER.readTree(copies.getFirst().body()).path("messageId").asText()).isEqualTo(stale.messageId().toString());
    }

    @Test
    void lostSqsAcknowledgementRetriesWithSameBodyAndMessageId() {
        saveNotification();
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var response = invocation.callRealMethod();
            if (first.getAndSet(false)) throw SdkClientException.create("Injected response loss after local queue acceptance");
            return response;
        }).when(sqs).sendMessage(any(SendMessageRequest.class));
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isEqualTo(1);
        clock.now = NOW.plusSeconds(60);
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        var copies = drain();
        assertThat(copies).hasSize(2);
        assertThat(copies.getFirst().body()).isEqualTo(copies.getLast().body());
    }

    @Test
    void absentQueueDefersWithoutSpendingMessageAttempt() {
        saveNotification();
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl()).build());
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events", String.class)).isEqualTo("SQS_CONFIGURATION_ERROR");
    }

    @Test
    void notificationAndBothControlMessagesReachStandardQueueWithCorrelation() {
        saveNotification();
        outboxes.saveAndFlush(OutboxEvent.userWithdrawn(8L, NOW));
        outboxes.saveAndFlush(OutboxEvent.userSessionEnded(9L, "44444444-4444-4444-8444-444444444444", NOW));
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status='PUBLISHED'", Long.class)).isEqualTo(3);
        var messages = drain();
        assertThat(messages).hasSize(3);
        assertThat(messages.stream().map(message -> MAPPER.readTree(message.body()).path("eventType").asText()))
                .containsExactlyInAnyOrder("NOTIFICATION_REQUESTED", "USER_WITHDRAWN", "USER_SESSION_ENDED");
        for (var message : messages) {
            assertThat(message.messageAttributes().get("correlationId").stringValue())
                    .isEqualTo(MAPPER.readTree(message.body()).path("correlationId").asText());
        }
    }

    private String state() { return jdbc.queryForObject("SELECT status FROM outbox_events", String.class); }
    private void saveNotification() {
        try {
            String json = new ClassPathResource("contracts/notification-requested-v1.json").getContentAsString(StandardCharsets.UTF_8);
            Map<String, Object> fields = MAPPER.readValue(json, Map.class);
            outboxes.saveAndFlush(OutboxEvent.notification(UUID.fromString((String) fields.get("messageId")),
                    UUID.fromString((String) fields.get("sourceEventId")), 7L, 10L, "DEADLINE_SOON", fields, NOW));
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private List<Message> drain() {
        var messages = new ArrayList<Message>();
        for (int attempt = 0; attempt < 10; attempt++) {
            var batch = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl()).maxNumberOfMessages(10)
                    .waitTimeSeconds(0).messageAttributeNames("All").build()).messages();
            if (batch.isEmpty()) break;
            messages.addAll(batch);
        }
        return messages;
    }
}
