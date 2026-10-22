package com.team3.backend.eligibility.config;

import com.team3.backend.global.role.ApiRole;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** 판정 결과 비동기 최신화(EligibilityResultRefresher)용. 실행기는 Spring Boot 기본 applicationTaskExecutor를 쓴다 */
@Configuration
@EnableAsync
@ApiRole
public class EligibilityAsyncConfig {
}
