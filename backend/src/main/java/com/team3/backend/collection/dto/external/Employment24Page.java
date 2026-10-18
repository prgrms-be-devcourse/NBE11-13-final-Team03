package com.team3.backend.collection.dto.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 과정별 원본 필드를 보존한다. 누락된 목록은 빈 목록으로 바꾸지 않고 거부한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Employment24Page(
        @JsonProperty("scn_cnt") Integer totalCount,
        String pageNum,
        String pageSize,
        List<JsonNode> srchList
) {
    public Employment24Page {
        srchList = List.copyOf(srchList);
    }
}
