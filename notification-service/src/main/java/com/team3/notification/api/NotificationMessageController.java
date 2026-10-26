package com.team3.notification.api;

import com.team3.notification.security.NotificationInternalKeyFilter;
import com.team3.notification.service.NotificationMessageHandler;
import com.team3.notification.service.NotificationReceptionResult;
import com.team3.notification.message.NotificationMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationMessageController {
    private static final Logger log = LoggerFactory.getLogger(NotificationMessageController.class);
    private final NotificationMessageHandler handler;
    private final NotificationMessageDecoder decoder;

    public NotificationMessageController(NotificationMessageHandler handler, NotificationMessageDecoder decoder) {
        this.handler = handler;
        this.decoder = decoder;
    }

    @PostMapping(path = NotificationInternalKeyFilter.PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public NotificationReceptionResult receive(@RequestBody String json) {
        // receive() commits before this method can return an HTTP receipt.
        if (decoder.isWithdrawal(json)) {
            var receipt = handler.handle(json);
            log.info("Withdrawal consumed: messageId={}, result={}, duplicate={}",
                    receipt.messageId(), receipt.status(), receipt.duplicateMessage());
            return receipt;
        }
        var message = decoder.decode(json);
        var receipt = handler.handle(message);
        log.info("Notification consumed: correlationId={}, messageId={}, sourceEventId={}, result={}, duplicate={}",
                message.correlationId(), receipt.messageId(), message.sourceEventId(), receipt.status(), receipt.duplicateMessage());
        return receipt;
    }
}
