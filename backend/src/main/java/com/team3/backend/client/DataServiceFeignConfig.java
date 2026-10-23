package com.team3.backend.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * DataServiceClient 전용 설정 (@Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않음).
 * 모든 요청에 DataService 전용 내부 키(yon.data-service.internal-api-key)를 붙인다.
 * 메인의 internal.api-key와 분리한다 (notification-service 전용 키와 같은 방식). 타임아웃은 spring.cloud.openfeign.client.config.data-service에 둔다.
 */
public class DataServiceFeignConfig {

    static final String INTERNAL_KEY_HEADER = "X-INTERNAL-KEY";

    @Bean
    public RequestInterceptor dataServiceInternalKey(@Value("${yon.data-service.internal-api-key}") String internalApiKey) {
        return template -> template.header(INTERNAL_KEY_HEADER, internalApiKey);
    }
}
