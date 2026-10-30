package com.team3.backend.auth.unlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class KakaoUnlinkWorker {
    private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkWorker.class);
    private final KakaoUnlinkProcessor processor;
    private final KakaoUnlinkProperties properties;
    public KakaoUnlinkWorker(KakaoUnlinkProcessor processor, KakaoUnlinkProperties properties) {
        this.processor = processor; this.properties = properties;
    }
    @Scheduled(scheduler = "kakaoUnlinkScheduler", fixedDelayString = "${app.kakao-unlink.poll-ms:5000}")
    public void runOnce() {
        try {
            for (int i = 0; i < properties.batchSize(); i++) if (!processor.processNext()) break;
        } catch (RuntimeException failure) {
            // Do not log HTTP exception bodies, tokens or admin key.
            log.error("Kakao unlink poll failed: type={}", failure.getClass().getSimpleName());
        }
    }
}
