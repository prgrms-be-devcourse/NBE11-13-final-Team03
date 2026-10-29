package com.team3.notification.admin;

import com.sun.net.httpserver.HttpServer;
import com.team3.notification.config.AdminNotificationProperties;
import com.team3.notification.message.NotificationMessageFingerprint;
import com.team3.notification.sqs.NotificationSqsTopology;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual SDK -> dedicated admin queue -> restricted DB -> HTTP Slack stub -> committed receipt -> delete. */
@Testcontainers
@SpringJUnitConfig(AdminNotificationSqsPostgresTest.Beans.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminNotificationSqsPostgresTest {
    private static final String PASSWORD = "admin-test-password";
    private static final String SOURCE = "yon-admin-integration", DLQ = SOURCE + "-dlq", USER = "yon-user-integration";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.12.0")
            .withExposedPorts(4566).withEnv("SERVICES", "sqs").withEnv("SQS_ENDPOINT_STRATEGY", "off")
            .withEnv("SQS_DELAY_PURGE_RETRY", "0")
            .waitingFor(Wait.forHttp("/_localstack/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));
    private static final List<String> SLACK_BODIES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SLACK_STATUS = new AtomicInteger(200);
    private static volatile CountDownLatch slackEntered = new CountDownLatch(0), slackRelease = new CountDownLatch(0);
    private static HttpServer webhook;
    private static ExecutorService webhookThreads;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(resource("db-boundary/database-boundaries.sql"));
            statement.execute("ALTER ROLE notification_user PASSWORD '" + PASSWORD + "'");
        }
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "notification_user", PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute(resource("db-boundary/notification-inbox.sql"));
            statement.execute(resource("db-boundary/notification-admin.sql"));
        }
        registry.add("admin.test.jdbc-url", POSTGRES::getJdbcUrl);
    }

    private static synchronized String webhookUrl() throws Exception {
        if (webhook == null) {
            webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            webhookThreads = Executors.newCachedThreadPool();
            webhook.setExecutor(webhookThreads);
            webhook.createContext("/webhook-secret", exchange -> {
                SLACK_BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                slackEntered.countDown();
                try { slackRelease.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                var bytes = (SLACK_STATUS.get() == 200 ? "ok" : "private-error").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(SLACK_STATUS.get(), bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            webhook.start();
        }
        return "http://127.0.0.1:" + webhook.getAddress().getPort() + "/webhook-secret";
    }

    @AfterAll static void closeWebhook() {
        if (webhook != null) webhook.stop(0);
        if (webhookThreads != null) webhookThreads.shutdownNow();
    }

    private static String resource(String name) throws Exception { return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8); }
    private static String endpoint() { return "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566); }
    private static String url(String name) { return endpoint() + "/000000000000/" + name; }
    private static String arn(String name) { return "arn:aws:sqs:ap-northeast-2:000000000000:" + name; }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Beans {
        @Bean DataSource dataSource(Environment environment) {
            var source = new DriverManagerDataSource(environment.getRequiredProperty("admin.test.jdbc-url"), "notification_user", PASSWORD);
            var properties = new Properties(); properties.setProperty("currentSchema", "notification,pg_catalog");
            source.setConnectionProperties(properties);
            return source;
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactions(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper mapper() { return JsonMapper.builder().build(); }
        @Bean NotificationMessageFingerprint fingerprints(ObjectMapper mapper) { return new NotificationMessageFingerprint(mapper); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean AdminNotificationProperties properties() throws Exception {
            return new AdminNotificationProperties(true, url(SOURCE), url(DLQ), "ap-northeast-2", endpoint(), 0, 60, webhookUrl(), 1000);
        }
        @Bean(destroyMethod = "close") SqsClient sqs() {
            return spy(SqsClient.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI.create(endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1)).socketTimeout(Duration.ofSeconds(5)))
                    .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(10))
                            .retryPolicy(RetryPolicy.none()).build()).build());
        }
        @Bean(destroyMethod = "close") SlackWebhookSender slack(AdminNotificationProperties properties, ObjectMapper mapper) {
            return new SlackWebhookSender(URI.create(properties.slackWebhookUrl()), mapper);
        }
        @Bean AdminNotificationHandler handler(JdbcTemplate jdbc, ObjectMapper mapper, NotificationMessageFingerprint fingerprints,
                                               SlackWebhookSender slack, Clock clock) {
            return new AdminNotificationHandler(jdbc, mapper, fingerprints, slack, clock);
        }
        @Bean AdminNotificationSqsConsumer consumer(SqsClient sqs, AdminNotificationProperties properties,
                ObjectMapper mapper, Clock clock, AdminNotificationHandler handler) {
            return new AdminNotificationSqsConsumer(sqs, properties, new NotificationSqsTopology(sqs, properties.sqsSettings(), mapper, clock), handler);
        }
    }

    @Autowired SqsClient sqs;
    @Autowired AdminNotificationSqsConsumer consumer;
    @Autowired AdminNotificationHandler handler;
    @Autowired AdminNotificationProperties properties;
    @Autowired ObjectMapper mapper;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    private final List<Message> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void cleanDisposableData() {
        sqs.createQueue(CreateQueueRequest.builder().queueName(DLQ).attributes(Map.of(
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600", QueueAttributeName.REDRIVE_ALLOW_POLICY,
                "{\"redrivePermission\":\"byQueue\",\"sourceQueueArns\":[\"" + arn(SOURCE) + "\"]}")).build());
        sqs.createQueue(CreateQueueRequest.builder().queueName(SOURCE).attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "60", QueueAttributeName.MESSAGE_RETENTION_PERIOD, "345600",
                QueueAttributeName.REDRIVE_POLICY, "{\"deadLetterTargetArn\":\"" + arn(DLQ) + "\",\"maxReceiveCount\":\"2\"}")).build());
        sqs.createQueue(CreateQueueRequest.builder().queueName(USER).build());
        for (var name : List.of(SOURCE, DLQ, USER)) sqs.purgeQueue(PurgeQueueRequest.builder().queueUrl(url(name)).build());
        jdbc.execute("TRUNCATE admin_consumed_messages, consumed_messages, notifications RESTART IDENTITY CASCADE");
        SLACK_BODIES.clear(); SLACK_STATUS.set(200); received.clear();
        slackEntered = new CountDownLatch(0); slackRelease = new CountDownLatch(0);
        doCallRealMethod().when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        doAnswer(invocation -> {
            var result = (ReceiveMessageResponse) invocation.callRealMethod(); received.addAll(result.messages()); return result;
        }).when(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        clearInvocations(sqs);
    }

    private String fixture() throws Exception { return resource("contracts/collection-completed-v1.json"); }
    private void send(String body) { sqs.sendMessage(SendMessageRequest.builder().queueUrl(url(SOURCE)).messageBody(body).build()); }
    private long receipts() { return jdbc.queryForObject("SELECT count(*) FROM admin_consumed_messages", Long.class); }
    private void redeliver() {
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder().queueUrl(url(SOURCE))
                .receiptHandle(received.getLast().receiptHandle()).visibilityTimeout(0).build());
    }

    @Test
    void commitsTheAdminReceiptBeforeDeletingAndDoesNotWriteUserInboxTables() throws Exception {
        send(fixture());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var connection = source.getConnection(); var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT count(*) FROM admin_consumed_messages")) {
                rows.next(); assertThat(rows.getLong(1)).isEqualTo(1);
            }
            return invocation.callRealMethod();
        }).when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        consumer.runOnce();
        assertThat(receipts()).isEqualTo(1);
        assertThat(SLACK_BODIES).hasSize(1);
        assertThat(mapper.readTree(SLACK_BODIES.getFirst()).path("text").asString())
                .contains("데이터 유형: 고용24", "수집일: 2026-10-27", "신규: 1,203건", "갱신: 88건", "변경 없음: 139,201건", "실패: 4건")
                .doesNotContain("실행:", "페이지 실패:", "소요 시간:", "상태:"); // v1에는 실행·상태 정보가 없다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consumed_messages", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    @Test
    void replayWithTheSameMessageIdDoesNotSendSlackTwice() throws Exception {
        send(fixture()); consumer.runOnce(); send(fixture()); consumer.runOnce();
        assertThat(receipts()).isEqualTo(1);
        assertThat(SLACK_BODIES).hasSize(1);
        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void sameDayV2FailureAndSuccessfulRetryBothReachSlackAndEachDeduplicate() throws Exception {
        var first = mapper.readTree(resource("contracts/collection-completed-v2.json"));
        var failed = (tools.jackson.databind.node.ObjectNode) first.path("payload");
        failed.put("syncType", "SCHEDULED"); failed.put("status", "FAILED");
        var failedBody = mapper.writeValueAsString(first);
        var retry = first.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) retry).put("messageId", java.util.UUID.randomUUID().toString());
        var successful = (tools.jackson.databind.node.ObjectNode) retry.path("payload");
        successful.put("jobId", 8L); successful.put("syncType", "MANUAL_RETRY"); successful.put("status", "SUCCESS");
        successful.put("failedCount", 0); successful.put("failedPageCount", 0);
        var retryBody = mapper.writeValueAsString(retry);
        for (var body : List.of(failedBody, retryBody, failedBody, retryBody)) {
            send(body); consumer.runOnce();
        }
        assertThat(receipts()).isEqualTo(2);
        assertThat(SLACK_BODIES).hasSize(2);
        assertThat(mapper.readTree(SLACK_BODIES.getFirst()).path("text").asString())
                .contains("수집일: 2026-10-27", "실행: #7 · 정기 수집", "페이지 실패: 1건", "소요 시간: 7분 00초", "상태: 실패");
        assertThat(mapper.readTree(SLACK_BODIES.getLast()).path("text").asString())
                .contains("수집일: 2026-10-27", "실행: #8 · 수동 재시도", "페이지 실패: 0건", "상태: 성공");
        verify(sqs, times(4)).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    @Test
    void receiptWrittenByTheV1ConsumerStillDeduplicatesAfterUpgrade() throws Exception {
        var id = java.util.UUID.fromString(mapper.readTree(fixture()).path("messageId").asString());
        jdbc.update("INSERT INTO admin_consumed_messages(message_id, event_type, request_fingerprint, consumed_at) VALUES (?, 'COLLECTION_COMPLETED', ?, current_timestamp)",
                id, "779e02075e264933da8d52f41988cf90ac694e9d678f1c2cd49d5280cbefca11");
        send(fixture()); consumer.runOnce();
        assertThat(receipts()).isEqualTo(1);
        assertThat(SLACK_BODIES).isEmpty();
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void slackFailureRollsBackReceiptAndRedeliveryCanSucceed() throws Exception {
        SLACK_STATUS.set(503); send(fixture()); consumer.runOnce();
        assertThat(receipts()).isZero();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        SLACK_STATUS.set(200); redeliver(); consumer.runOnce();
        assertThat(receipts()).isEqualTo(1);
        assertThat(SLACK_BODIES).hasSize(2);
        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void failedQueueDeleteReplaysTheReceiptWithoutAnotherSlackPost() throws Exception {
        doThrow(SdkClientException.create("private")).when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        send(fixture()); consumer.runOnce();
        assertThat(receipts()).isEqualTo(1);
        var oldReceipt = received.getLast().receiptHandle();
        doCallRealMethod().when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        redeliver(); consumer.runOnce();
        assertThat(received.getLast().receiptHandle()).isNotEqualTo(oldReceipt);
        assertThat(SLACK_BODIES).hasSize(1);
    }

    @Test
    void sameIdWithChangedPayloadIsNotAcknowledged() throws Exception {
        send(fixture()); consumer.runOnce(); clearInvocations(sqs);
        send(fixture().replace("\"failedCount\": 4", "\"failedCount\": 5")); consumer.runOnce();
        assertThat(receipts()).isEqualTo(1); assertThat(SLACK_BODIES).hasSize(1);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void invalidMessagesReachOnlyTheAdminDlq() {
        send("{\"eventType\":\"NOTIFICATION_REQUESTED\",\"private\":true}");
        List<Message> dead = List.of();
        for (int attempt = 0; attempt < 8 && dead.isEmpty(); attempt++) {
            int before = received.size(); consumer.runOnce();
            if (received.size() > before) redeliver();
            dead = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url(DLQ)).waitTimeSeconds(0).build()).messages();
        }
        assertThat(dead).hasSize(1);
        assertThat(receipts()).isZero(); assertThat(SLACK_BODIES).isEmpty();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void userQueueMessagesRemainUntouched() throws Exception {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(url(USER)).messageBody("user-message").build());
        send(fixture()); consumer.runOnce();
        var users = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(url(USER)).waitTimeSeconds(0).build()).messages();
        assertThat(users).hasSize(1); assertThat(users.getFirst().body()).isEqualTo("user-message");
        assertThat(SLACK_BODIES).hasSize(1);
    }

    @Test
    void concurrentDuplicatesSendSlackOnce() throws Exception {
        slackEntered = new CountDownLatch(1); slackRelease = new CountDownLatch(1);
        var body = fixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> handler.handle(body));
            assertThat(slackEntered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> handler.handle(body));
            slackRelease.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).duplicate(), second.get(10, TimeUnit.SECONDS).duplicate()))
                    .containsExactlyInAnyOrder(false, true);
        } finally { slackRelease.countDown(); }
        assertThat(receipts()).isEqualTo(1); assertThat(SLACK_BODIES).hasSize(1);
    }

    @Test
    void slackSuccessThenDatabaseCommitFailureCanRedeliverTheSameInformationalMessage() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_admin_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test commit failure'; END; $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_admin_commit AFTER INSERT ON admin_consumed_messages DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_admin_commit()");
        try {
            send(fixture()); consumer.runOnce();
            assertThat(receipts()).isZero(); assertThat(SLACK_BODIES).hasSize(1);
            verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        } finally {
            jdbc.execute("DROP TRIGGER test_admin_commit ON admin_consumed_messages"); jdbc.execute("DROP FUNCTION reject_admin_commit()");
        }
        redeliver(); consumer.runOnce();
        assertThat(receipts()).isEqualTo(1); assertThat(SLACK_BODIES).hasSize(2);
        assertThat(SLACK_BODIES.getFirst()).isEqualTo(SLACK_BODIES.getLast());
    }

    @Test
    void badTopologyStopsBeforeReceiving() throws Exception {
        send(fixture());
        sqs.setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(url(SOURCE))
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "59")).build());
        try {
            var strict = new AdminNotificationSqsConsumer(sqs, properties,
                    new NotificationSqsTopology(sqs, properties.sqsSettings(), mapper, clock), handler);
            clearInvocations(sqs); strict.runOnce();
            verify(sqs, never()).receiveMessage(any(ReceiveMessageRequest.class));
            assertThat(receipts()).isZero(); assertThat(SLACK_BODIES).isEmpty();
        } finally {
            sqs.setQueueAttributes(SetQueueAttributesRequest.builder().queueUrl(url(SOURCE))
                    .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "60")).build());
        }
    }
}
