package com.team3.notification.push;

public interface PushSender {
    /** FCM acceptance ID, not proof that a browser displayed/read the notification. */
    String send(PushMessage message);
}
