package com.team3.backend.region.service;

import com.team3.backend.global.role.ApiRole;
import com.team3.backend.region.dto.RegionSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * region-code-api.sync-on-startup=true일 때만 서버 시작 시 지역 코드를 한 번 동기화한다. (기본값 false)
 * 동기화가 실패해도 서버 기동은 막지 않고 오류만 기록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ApiRole
@ConditionalOnProperty(name = "region-code-api.sync-on-startup", havingValue = "true")
public class RegionSyncRunner implements ApplicationRunner {

    private final RegionSyncService regionSyncService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            RegionSyncResult result = regionSyncService.sync();
            if (!result.missingCodes().isEmpty()) {
                log.warn("API 응답에 없는 기존 지역 코드: {}", result.missingCodes());
            }
        } catch (RuntimeException e) {
            log.error("지역 코드 동기화 실패", e);
        }
    }
}
