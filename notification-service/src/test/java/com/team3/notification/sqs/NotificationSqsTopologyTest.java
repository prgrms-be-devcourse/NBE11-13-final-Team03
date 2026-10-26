package com.team3.notification.sqs;

import com.team3.notification.config.NotificationSqsProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationSqsTopologyTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final Clock clock = mock(Clock.class);
    private final NotificationSqsProperties properties = new NotificationSqsProperties(true,
            "https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications",
            "https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications-dlq", "ap-northeast-2", "", 10, 120);
    private final String sourceArn = properties.expectedArn(properties.queueUrl());
    private final String dlqArn = properties.expectedArn(properties.dlqUrl());

    @Test
    void validatesStandardTopologyAndRechecksAfterOneMinute() {
        var now = Instant.parse("2026-10-24T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        attributes(source(), dlq());
        var topology = topology();
        topology.verify();
        topology.verify();
        verify(sqs, times(2)).getQueueAttributes(any(GetQueueAttributesRequest.class));
        when(clock.instant()).thenReturn(now.plusSeconds(60));
        topology.verify();
        verify(sqs, times(4)).getQueueAttributes(any(GetQueueAttributesRequest.class));
    }

    @Test
    void rejectsMissingOrWrongDlqAndInvalidReceiveCount() {
        for (String policy : new String[]{"{}", "{\"deadLetterTargetArn\":\"wrong\",\"maxReceiveCount\":\"5\"}",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"0\"}", "not-json"}) {
            var source = source(); source.put(QueueAttributeName.REDRIVE_POLICY, policy);
            assertInvalid(source, dlq());
        }
    }

    @Test
    void rejectsFifoQueueIdentityMismatchVisibilityMismatchAndInsufficientDlqRetention() {
        var source = source(); source.put(QueueAttributeName.FIFO_QUEUE, "true"); assertInvalid(source, dlq());
        var dlq = dlq(); dlq.put(QueueAttributeName.FIFO_QUEUE, "true"); assertInvalid(source(), dlq);
        source = source(); source.put(QueueAttributeName.QUEUE_ARN, "wrong"); assertInvalid(source, dlq());
        source = source(); source.put(QueueAttributeName.VISIBILITY_TIMEOUT, "30"); assertInvalid(source, dlq());
        dlq = dlq(); dlq.put(QueueAttributeName.MESSAGE_RETENTION_PERIOD, "345600"); assertInvalid(source(), dlq);
    }

    @Test
    void requiresExplicitDlqSourceAllowPolicyAndNeverCachesFailure() {
        var dlq = dlq(); dlq.remove(QueueAttributeName.REDRIVE_ALLOW_POLICY);
        assertInvalid(source(), dlq);
        dlq = dlq(); dlq.put(QueueAttributeName.REDRIVE_ALLOW_POLICY, "{\"redrivePermission\":\"allowAll\"}");
        assertInvalid(source(), dlq);

        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T00:00:00Z"));
        attributes(source(), dlq);
        var topology = topology();
        assertThatThrownBy(topology::verify).hasMessage("SQS_TOPOLOGY_INVALID");
        attributes(source(), dlq());
        assertThatCode(topology::verify).doesNotThrowAnyException();
    }

    private void assertInvalid(Map<QueueAttributeName, String> source, Map<QueueAttributeName, String> dlq) {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T00:00:00Z"));
        attributes(source, dlq);
        assertThatThrownBy(topology()::verify).hasMessage("SQS_TOPOLOGY_INVALID").hasNoCause();
    }

    private NotificationSqsTopology topology() {
        return new NotificationSqsTopology(sqs, properties, JsonMapper.builder().build(), clock);
    }

    private void attributes(Map<QueueAttributeName, String> source, Map<QueueAttributeName, String> dlq) {
        doAnswer(invocation -> {
            var request = invocation.getArgument(0, GetQueueAttributesRequest.class);
            return GetQueueAttributesResponse.builder().attributes(request.queueUrl().equals(properties.queueUrl()) ? source : dlq).build();
        }).when(sqs).getQueueAttributes(any(GetQueueAttributesRequest.class));
    }

    private Map<QueueAttributeName, String> source() {
        return new HashMap<>(Map.of(QueueAttributeName.QUEUE_ARN, sourceArn, QueueAttributeName.FIFO_QUEUE, "false",
                QueueAttributeName.REDRIVE_POLICY, "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}",
                QueueAttributeName.VISIBILITY_TIMEOUT, "120", QueueAttributeName.MESSAGE_RETENTION_PERIOD, "345600"));
    }

    private Map<QueueAttributeName, String> dlq() {
        return new HashMap<>(Map.of(QueueAttributeName.QUEUE_ARN, dlqArn, QueueAttributeName.FIFO_QUEUE, "false",
                QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600", QueueAttributeName.REDRIVE_ALLOW_POLICY,
                "{\"redrivePermission\":\"byQueue\",\"sourceQueueArns\":[\"" + sourceArn + "\"]}"));
    }
}
