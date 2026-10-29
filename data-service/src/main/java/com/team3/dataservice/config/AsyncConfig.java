package com.team3.dataservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 수동 수집 실행(ManualSyncWorker, collectionExecutor)과 기동 시 조건 묶음 백필(ConditionSetBackfill)의 @Async용.
 * 백필은 Spring Boot 기본 applicationTaskExecutor를 쓴다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
