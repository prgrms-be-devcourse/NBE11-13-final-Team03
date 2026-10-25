package com.team3.dataservice.outbox;

import com.team3.dataservice.outbox.config.CollectionNotificationPublisherProperties;

import com.team3.dataservice.outbox.domain.repository.CollectionOutboxEventRepository;
import com.team3.dataservice.outbox.publisher.*;
import com.team3.dataservice.outbox.service.*;
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
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=update", "spring.jpa.properties.hibernate.default_schema=application", "spring.datasource.hikari.connection-init-sql=SET search_path TO application, pg_catalog"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({OutboxPublicationStore.class, OutboxPublicationWorker.class, CollectionSqsOutboxPostgresTest.Beans.class})
class CollectionSqsOutboxPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final String SOURCE = "yon-collection-admin-integration";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.12.0")
            .withExposedPorts(4566).withEnv("SERVICES", "sqs").withEnv("SQS_ENDPOINT_STRATEGY", "off")
            .withEnv("SQS_DELAY_PURGE_RETRY", "0")
            .withEnv("SQS_DELAY_RECENTLY_DELETED", "0")
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE application_user LOGIN PASSWORD 'test'; CREATE SCHEMA application AUTHORIZATION application_user");
            statement.execute("SET ROLE application_user");
            statement.execute(new ClassPathResource("db-boundary/collection-outbox.sql").getContentAsString(StandardCharsets.UTF_8));
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "application_user");
        registry.add("spring.datasource.password", () -> "test");
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
    private static String endpoint() { return "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566); }
    private static String queueUrl() { return endpoint() + "/000000000000/" + SOURCE; }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean TestClock sqsClock() { return new TestClock(); }
        @Bean CollectionNotificationPublisherProperties properties() {
            return new CollectionNotificationPublisherProperties(true, "ap-northeast-2", queueUrl(), endpoint(),
                    10, 3, Duration.ofMinutes(1), Duration.ofMinutes(1), 5000);
        }
        @Bean(destroyMethod = "close") SqsClient localSqsClient() {
            return spy(SqsClient.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                            .socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10))
                            .retryPolicy(RetryPolicy.none()).build()).build());
        }
        @Bean CollectionEventPublisher publisher(SqsClient sqs) {
            return new SqsCollectionEventPublisher(sqs,
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
    @Autowired CollectionOutboxEventRepository outboxes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private int sequence;

    @BeforeEach
    void resetDisposableContainerDataOnly() {
        doCallRealMethod().when(sqs).sendMessage(any(SendMessageRequest.class));
        sqs.createQueue(CreateQueueRequest.builder().queueName(SOURCE)
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "120")).build());
        sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(queueUrl()).build());
        jdbc.execute("TRUNCATE collection_outbox_events RESTART IDENTITY");
        clock.now = NOW;
        sequence = 0;
        clearInvocations(sqs);
    }

    @Test
    void publishesOnlyCommittedRowsOutsideDatabaseTransaction() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            saveCompletion(); worker.runOnce();
            verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state()).isEqualTo("PROCESSING");
            return invocation.callRealMethod();
        }).when(sqs).sendMessage(any(SendMessageRequest.class));
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        assertThat(drain()).hasSize(1);
    }

    @Test
    void rolledBackOutboxNeverReachesQueue() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            saveCompletion(); status.setRollbackOnly();
        });
        worker.runOnce();
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
        assertThat(outboxes.count()).isZero();
        assertThat(drain()).isEmpty();
    }

    @Test
    void acceptedMessageSurvivesDatabaseAcknowledgementFailureAndLeaseRecoveryKeepsIdentity() {
        saveCompletion();
        OutboxPublication stale;
        jdbc.execute("ALTER TABLE collection_outbox_events ADD CONSTRAINT reject_sqs_published CHECK (status <> 'PUBLISHED')");
        try {
            assertThatThrownBy(worker::runOnce).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(state()).isEqualTo("PROCESSING");
            var row = outboxes.findAll().getFirst();
            stale = new OutboxPublication(row.getId(), row.getMessageId(), row.getLeaseToken(), MAPPER.writeValueAsString(row.getPayload()));
        } finally { jdbc.execute("ALTER TABLE collection_outbox_events DROP CONSTRAINT reject_sqs_published"); }

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
        saveCompletion();
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var response = invocation.callRealMethod();
            if (first.getAndSet(false)) throw SdkClientException.create("Injected response loss after local queue acceptance");
            return response;
        }).when(sqs).sendMessage(any(SendMessageRequest.class));
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM collection_outbox_events", Integer.class)).isEqualTo(1);
        clock.now = NOW.plusSeconds(60);
        worker.runOnce();
        assertThat(state()).isEqualTo("PUBLISHED");
        var copies = drain();
        assertThat(copies).hasSize(2);
        assertThat(copies.getFirst().body()).isEqualTo(copies.getLast().body());
    }

    @Test
    void absentQueueDefersWithoutSpendingMessageAttempt() {
        saveCompletion();
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl()).build());
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM collection_outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM collection_outbox_events", String.class)).isEqualTo("SQS_CONFIGURATION_ERROR");
    }

    @Test
    void transientFailuresIncreaseBackoffAndStopAtMaximumAttempts() {
        saveCompletion();
        doThrow(SdkClientException.create("private network failure")).when(sqs).sendMessage(any(SendMessageRequest.class));
        worker.runOnce();
        assertThat(state()).isEqualTo("PENDING");
        clock.now = NOW.plusSeconds(60);
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT next_retry_at FROM collection_outbox_events", java.sql.Timestamp.class).toInstant())
                .isEqualTo(NOW.plusSeconds(180));
        clock.now = NOW.plusSeconds(180);
        worker.runOnce();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT retry_count FROM collection_outbox_events", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT last_error FROM collection_outbox_events", String.class)).isEqualTo("SQS_IO_FAILURE");
        assertThat(outboxes.count()).isEqualTo(1);
        assertThat(drain()).isEmpty();
    }

    @Test
    void oldLeaseCannotOverwriteAnyResultAfterNewOwnerClaimsIt() {
        saveCompletion();
        var old = store.claimNext().orElseThrow();
        clock.now = NOW.plusSeconds(61);
        assertThat(store.recoverStale()).isEqualTo(1);
        clock.now = NOW.plusSeconds(121);
        var resumed = store.claimNext().orElseThrow();
        assertThat(resumed.messageId()).isEqualTo(old.messageId());
        assertThat(resumed.json()).isEqualTo(old.json());
        assertThat(resumed.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(store.published(old)).isFalse();
        assertThat(store.failed(old, "STALE", true)).isFalse();
        assertThat(store.deferred(old, "STALE")).isFalse();
        assertThat(store.published(resumed)).isTrue();
    }

    @Test
    void crashedLastAttemptBecomesFailedInsteadOfRemainingProcessing() {
        saveCompletion();
        jdbc.update("UPDATE collection_outbox_events SET retry_count=2");
        store.claimNext().orElseThrow();
        clock.now = NOW.plusSeconds(61);
        assertThat(store.recoverStale()).isEqualTo(1);
        assertThat(state()).isEqualTo("FAILED");
        assertThat(store.claimNext()).isEmpty();
    }

    @Test
    void concurrentClaimersGetExactlyOneLease() throws Exception {
        saveCompletion();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return store.claimNext().isPresent();
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertThat(List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void completionsReachAdminStandardQueueWithCorrelation() {
        saveCompletion();
        saveCompletion();
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_outbox_events WHERE status='PUBLISHED'", Long.class)).isEqualTo(2);
        var messages = drain();
        assertThat(messages).hasSize(2);
        assertThat(messages.stream().map(message -> MAPPER.readTree(message.body()).path("eventType").asText()))
                .containsExactlyInAnyOrder("COLLECTION_COMPLETED", "COLLECTION_COMPLETED");
        for (var message : messages) {
            assertThat(message.messageAttributes().get("correlationId").stringValue())
                    .isEqualTo(MAPPER.readTree(message.body()).path("correlationId").asText());
        }
    }

    private String state() { return jdbc.queryForObject("SELECT status FROM collection_outbox_events", String.class); }
    private void saveCompletion() {
        try {
            String json = new ClassPathResource("contracts/collection-completed-v1.json").getContentAsString(StandardCharsets.UTF_8);
            Map<String, Object> fields = MAPPER.readValue(json, Map.class);
            var id = UUID.randomUUID();
            var date = LocalDate.of(2026, 10, 27).plusDays(sequence++);
            fields.put("messageId", id.toString());
            ((Map<String, Object>) fields.get("payload")).put("collectedDate", date.toString());
            String body = MAPPER.writeValueAsString(fields);
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> outboxes.insertIfAbsent(
                    id, "COLLECTION_COMPLETED:EMPLOYMENT24:" + date, 7L, body, NOW));
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
