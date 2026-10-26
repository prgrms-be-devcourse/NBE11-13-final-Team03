package com.team3.notification.admin;

import com.team3.notification.config.AdminNotificationProperties;
import com.team3.notification.sqs.NotificationSqsTopology;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminNotificationSqsConsumerTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final NotificationSqsTopology topology = mock(NotificationSqsTopology.class);
    private final AdminNotificationHandler handler = mock(AdminNotificationHandler.class);
    private final AdminNotificationProperties properties = new AdminNotificationProperties(true,
            "http://127.0.0.1:4566/000000000000/admin", "http://127.0.0.1:4566/000000000000/admin-dlq",
            "ap-northeast-2", "http://127.0.0.1:4566", 0, 60, "http://127.0.0.1:8090/hook", 1000);
    private final AdminNotificationSqsConsumer consumer = new AdminNotificationSqsConsumer(sqs, properties, topology, handler);

    @Test
    void acknowledgesThisReceiptOnlyAfterHandlerReturnsIncludingDuplicates() {
        delivery("receipt-current");
        when(handler.handle("body")).thenReturn(new AdminNotificationHandler.Result(UUID.randomUUID(), true));
        consumer.runOnce();
        var order = inOrder(topology, sqs, handler);
        order.verify(topology).verify();
        order.verify(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        order.verify(handler).handle("body");
        order.verify(sqs).deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl()).receiptHandle("receipt-current").build());
    }

    @Test
    void topologyFailurePreventsReceivingAnything() {
        doThrow(new IllegalStateException("private")).when(topology).verify();
        consumer.runOnce();
        verifyNoInteractions(sqs, handler);
    }

    @Test
    void handlerFailureKeepsMessageForRedelivery() {
        delivery("receipt-current");
        when(handler.handle("body")).thenThrow(new IllegalArgumentException("private"));
        consumer.runOnce();
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void missingReceiptDoesNotInvokeHandler() {
        delivery("");
        consumer.runOnce();
        verifyNoInteractions(handler);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    private void delivery(String receipt) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().body("body").receiptHandle(receipt).build()).build());
    }
}
