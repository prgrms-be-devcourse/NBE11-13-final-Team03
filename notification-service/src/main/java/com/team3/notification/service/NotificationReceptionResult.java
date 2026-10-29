package com.team3.notification.service;

import com.team3.notification.domain.entity.ReceptionStatus;
import java.util.UUID;

public record NotificationReceptionResult(
        UUID messageId, ReceptionStatus status, Long notificationId, boolean duplicateMessage
) {
}
