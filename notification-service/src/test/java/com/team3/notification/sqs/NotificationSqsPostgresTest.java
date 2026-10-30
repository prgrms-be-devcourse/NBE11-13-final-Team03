package com.team3.notification.sqs;

import com.team3.notification.NotificationApplication;
import com.team3.notification.api.NotificationUserDtos.*;
import com.team3.notification.config.NotificationSqsProperties;
import com.team3.notification.fixture.NotificationMessages;
import com.team3.notification.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.team3.notification.fixture.NotificationPostgres;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Disposable local SQS + restricted PostgreSQL/manual DDL. No real AWS credentials, local DB or Firebase. */
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {NotificationApplication.class, NotificationSqsPostgresTest.Beans.class},
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {"app.scheduling.enabled=false", "yon.notification.sqs.consumer-enabled=false",
                "yon.notification.receiver.enabled=false", "yon.notification.push.enabled=false"})
class NotificationSqsPostgresTest {
    private static final String PASSWORD = NotificationPostgres.PASSWORD;
    private static final String SOURCE = "yon-integration-notifications";
    private static final String DLQ = SOURCE + "-dlq";
    @Container static final PostgreSQLContainer POSTGRES = NotificationPostgres.create();
    @Container static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.12.0")
            .withExposedPorts(4566).withEnv("SERVICES", "sqs").withEnv("SQS_ENDPOINT_STRATEGY", "off")
            .withEnv("SQS_DELAY_PURGE_RETRY", "0")
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        NotificationPostgres.userSchema(POSTGRES, registry);
    }

    private static String endpoint() { return "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566); }
    private static String arn(String name) { return "arn:aws:sqs:ap-northeast-2:000000000000:" + name; }
    private static java.sql.Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notification_user", PASSWORD);
    }
    private static String resource(String name) throws Exception {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean @Primary Clock sqsTestClock() { return Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC); }
        @Bean(destroyMethod = "close")
        SqsClient localSqsClient() {
            return spy(SqsClient.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                            .socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10))
                            .retryPolicy(RetryPolicy.none()).build()).build());
        }
        @Bean @Primary
        NotificationSqsProperties localSqsProperties(SqsClient sqs) {
            sqs.createQueue(CreateQueueRequest.builder().queueName(DLQ).attributes(Map.of(
                    QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600", QueueAttributeName.REDRIVE_ALLOW_POLICY,
                    "{\"redrivePermission\":\"byQueue\",\"sourceQueueArns\":[\"" + arn(SOURCE) + "\"]}")).build());
            sqs.createQueue(CreateQueueRequest.builder().queueName(SOURCE).attributes(Map.of(
                    QueueAttributeName.VISIBILITY_TIMEOUT, "120", QueueAttributeName.MESSAGE_RETENTION_PERIOD, "345600",
                    QueueAttributeName.REDRIVE_POLICY,
                    "{\"deadLetterTargetArn\":\"" + arn(DLQ) + "\",\"maxReceiveCount\":\"2\"}")).build());
            // Use the host-mapped loopback origin, not LocalStack's container-advertised URL/routing prefix.
            String source = endpoint() + "/000000000000/" + SOURCE;
            String dlq = endpoint() + "/000000000000/" + DLQ;
            // Small receive count is test-only. Timing/retention/retry values are NOT production policy decisions.
            return new NotificationSqsProperties(true, source, dlq, "ap-northeast-2", endpoint(), 0, 120);
        }
        @Bean NotificationSqsTopology localSqsTopology(SqsClient sqs, NotificationSqsProperties properties,
                ObjectMapper mapper, Clock clock) { return new NotificationSqsTopology(sqs, properties, mapper, clock); }
        @Bean NotificationSqsConsumer localSqsConsumer(SqsClient sqs, NotificationSqsProperties properties,
                NotificationSqsTopology topology, NotificationMessageHandler handler) {
            return new NotificationSqsConsumer(sqs, properties, topology, handler);
        }
    }

    @Autowired SqsClient sqs;
    @Autowired NotificationSqsProperties properties;
    @Autowired NotificationSqsConsumer consumer;
    @Autowired NotificationSqsTopology topology;
    @Autowired NotificationMessageHandler handler;
    @Autowired NotificationUserService users;
    @Autowired JdbcTemplate jdbc;
    private final List<Message> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetDisposableContainerDataOnly() {
        doCallRealMethod().when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        doAnswer(invocation -> {
            var response = (ReceiveMessageResponse) invocation.callRealMethod();
            received.addAll(response.messages());
            return response;
        }).when(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(properties.queueUrl()).build());
        sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(properties.dlqUrl()).build());
        jdbc.execute("TRUNCATE notification_deliveries, consumed_messages, notifications, notification_endpoints, notification_settings, withdrawn_users RESTART IDENTITY CASCADE");
        received.clear();
        clearInvocations(sqs);
        topology.verify();
    }

    @Test
    void databaseCommitIsVisibleBeforeDeleteAndReplayDoesNotDuplicateNotificationOrDelivery() {
        consent();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var connection = connection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT (SELECT count(*) FROM notifications), (SELECT count(*) FROM consumed_messages), (SELECT count(*) FROM notification_deliveries)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1);
                assertThat(rows.getLong(2)).isEqualTo(1);
                assertThat(rows.getLong(3)).isEqualTo(1);
            }
            return invocation.callRealMethod();
        }).when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        String body = NotificationMessages.json(Map.of());
        send(body); consumer.runOnce();
        send(body); consumer.runOnce();
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("consumed_messages")).isEqualTo(1);
        assertThat(count("notification_deliveries")).isEqualTo(1);
        assertSourceEmpty();
    }

    @Test
    void failedDeleteRedeliversWithNewHandleWithoutDuplicatingCommittedData() {
        consent();
        doThrow(SdkClientException.create("Injected local delete failure")).doCallRealMethod()
                .when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        send(NotificationMessages.json(Map.of()));
        consumer.runOnce();
        assertThat(received).hasSize(1);
        assertThat(count("consumed_messages")).isEqualTo(1);
        String oldHandle = lastReceived().receiptHandle();
        reveal(); consumer.runOnce();
        assertThat(received).hasSize(2);
        assertThat(lastReceived().receiptHandle()).isNotEqualTo(oldHandle);
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("notification_deliveries")).isEqualTo(1);
        assertSourceEmpty();
    }

    @Test
    void storageFailureRollsBackNotificationDeliveryAndReceiptThenRetries() {
        consent();
        rejectConsumption();
        try {
            send(NotificationMessages.json(Map.of())); consumer.runOnce();
            assertThat(received).hasSize(1);
            assertThat(count("notifications")).isZero();
            assertThat(count("notification_deliveries")).isZero();
            assertThat(count("consumed_messages")).isZero();
            verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        } finally { allowConsumption(); }
        reveal(); consumer.runOnce();
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("notification_deliveries")).isEqualTo(1);
        assertSourceEmpty();
    }

    @Test
    void poisonMessageMovesToDlqWithoutBeingAcknowledged() throws Exception {
        String poison = "{\"schemaVersion\":999}";
        send(poison);
        var quarantined = moveToDlq();
        assertThat(quarantined.body()).isEqualTo(poison);
        assertThat(count("notifications")).isZero();
        assertThat(count("consumed_messages")).isZero();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void validMessageInDlqCanBeReplayedAfterStorageRecoveryWithOriginalIdentity() throws Exception {
        consent();
        String body = NotificationMessages.json(Map.of());
        Message quarantined;
        rejectConsumption();
        try {
            send(body); quarantined = moveToDlq();
            assertThat(count("consumed_messages")).isZero();
        } finally { allowConsumption(); }
        assertThat(quarantined.body()).isEqualTo(body);
        // Test-only operator action: accept the original body into source BEFORE deleting the DLQ delivery.
        send(quarantined.body());
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.dlqUrl()).receiptHandle(quarantined.receiptHandle()).build());
        consumer.runOnce();
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("consumed_messages")).isEqualTo(1);
        assertThat(count("notification_deliveries")).isEqualTo(1);
        assertSourceEmpty();
    }

    @Test
    void withdrawalBeforeNotificationSuppressesLateMessageAndCreatesNoDelivery() throws Exception {
        consent();
        send(resource("contracts/user-withdrawn-v1.json")); consumer.runOnce();
        send(NotificationMessages.json(Map.of())); consumer.runOnce();
        assertThat(count("withdrawn_users")).isEqualTo(1);
        assertThat(count("consumed_messages")).isEqualTo(2);
        assertThat(count("notifications")).isZero();
        assertThat(count("notification_deliveries")).isZero();
        assertThat(count("notification_endpoints")).isZero();
        assertSourceEmpty();
    }

    @Test
    void sessionEndThroughSqsDeactivatesThatSessionsEndpointAndIsAcknowledged() throws Exception {
        consent();
        var endpoint = users.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "sqs-session-token", "44444444-4444-4444-8444-444444444444"));
        var kept = users.registerEndpoint(7, new com.team3.notification.api.NotificationUserDtos.EndpointRequest(
                "sqs-other-session-token", "55555555-5555-4555-8555-555555555555"));
        send(resource("contracts/user-session-ended-v1.json")); consumer.runOnce();
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, endpoint.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT active FROM notification_endpoints WHERE id = ?", Boolean.class, kept.id())).isTrue();
        assertThat(count("consumed_messages")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result FROM consumed_messages", String.class)).isEqualTo("SESSION_ENDED");
        assertThat(count("withdrawn_users")).isZero();
        assertSourceEmpty();
    }

    @Test
    void previouslyConsumedHttpHandlerMessageIsAlsoIdempotentWhenReceivedThroughSqs() {
        consent();
        String body = NotificationMessages.json(Map.of());
        handler.handle(body);
        send(body); consumer.runOnce();
        assertThat(count("notifications")).isEqualTo(1);
        assertThat(count("consumed_messages")).isEqualTo(1);
        assertThat(count("notification_deliveries")).isEqualTo(1);
        assertSourceEmpty();
    }

    private void send(String body) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl()).messageBody(body).build());
    }
    private void consent() {
        users.updateSettings(7, new SettingsRequest(true, true));
        users.registerEndpoint(7, new EndpointRequest("local-sqs-test-token"));
    }
    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM notification." + table, Long.class);
    }
    private Message lastReceived() { return received.getLast(); }
    private void reveal() {
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(properties.queueUrl())
                .receiptHandle(lastReceived().receiptHandle()).visibilityTimeout(0).build());
    }
    private void rejectConsumption() {
        jdbc.execute("ALTER TABLE consumed_messages ADD CONSTRAINT reject_sqs_consume CHECK (user_id <> 7)");
    }
    private void allowConsumption() { jdbc.execute("ALTER TABLE consumed_messages DROP CONSTRAINT reject_sqs_consume"); }
    private void assertSourceEmpty() {
        assertThat(sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl())
                .waitTimeSeconds(0).maxNumberOfMessages(1).build()).messages()).isEmpty();
    }
    private Message moveToDlq() throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            int before = received.size();
            consumer.runOnce();
            if (received.size() > before) reveal();
            var quarantined = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.dlqUrl())
                    .waitTimeSeconds(0).maxNumberOfMessages(1).build()).messages();
            if (!quarantined.isEmpty()) return quarantined.getFirst();
            Thread.sleep(100);
        }
        throw new AssertionError("Local test message did not reach its DLQ");
    }
}
