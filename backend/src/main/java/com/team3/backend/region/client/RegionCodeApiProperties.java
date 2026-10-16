package com.team3.backend.region.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 행정표준코드관리시스템 법정동코드(StanReginCd) API 설정.
 * serviceKey는 공공데이터포털의 인코딩 키(권장) 또는 디코딩 키를 환경변수 REGION_CODE_API_KEY로 넣는다. (저장소에 커밋 금지)
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "region-code-api")
public class RegionCodeApiProperties {

    private String baseUrl = "https://apis.data.go.kr/1741000/StanReginCd/getStanReginCdList";
    private String serviceKey;
    private int pageSize = 1000;
}
