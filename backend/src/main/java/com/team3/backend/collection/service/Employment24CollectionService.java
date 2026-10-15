package com.team3.backend.collection.service;

import com.team3.backend.collection.client.Employment24Client;
import com.team3.backend.collection.dto.external.Employment24CollectionResult;
import com.team3.backend.collection.dto.external.Employment24SearchRequest;
import com.team3.backend.collection.dto.response.Employment24PageFailureResponse;
import com.team3.backend.collection.exception.Employment24ClientException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class Employment24CollectionService {
    private final Employment24Client client;

    public Employment24CollectionService(Employment24Client client) {
        this.client = client;
    }

    /** 한국 날짜 기준 오늘부터 6개월 후까지, NCS 1차분류 01~24를 순차 조회한다. */
    public Employment24CollectionResult collect() {
        return collect(LocalDate.now(ZoneId.of("Asia/Seoul")), 100, 1000);
    }

    public Employment24CollectionResult collect(LocalDate today, int pageSize, int maxPages) {
        Objects.requireNonNull(today, "수집 기준일이 필요합니다.");
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > 1000) {
            throw new IllegalArgumentException("페이지 크기는 1~100, 최대 조회 페이지 수는 1~1000이어야 합니다.");
        }
        List<JsonNode> records = new ArrayList<>();
        List<Employment24PageFailureResponse> failures = new ArrayList<>();
        List<String> incompleteNcsCodes = new ArrayList<>();
        for (int code = 1; code <= 24; code++) {
            String ncsCode = String.format(Locale.ROOT, "%02d", code);
            Integer totalCount = null;
            boolean completed = false;
            for (int pageNum = 1; pageNum <= maxPages; pageNum++) {
                var request = Employment24SearchRequest.forNextSixMonths(ncsCode, today, pageNum, pageSize);
                try {
                    var page = client.fetchPage(request);
                    records.addAll(page.srchList());
                    if (page.totalCount() != null) {
                        totalCount = page.totalCount();
                    }
                    if (page.srchList().isEmpty()) {
                        completed = true;
                        break;
                    }
                } catch (Employment24ClientException exception) {
                    failures.add(new Employment24PageFailureResponse(request, exception.getErrorCode(),
                            exception.getMessage(), exception.isRetryable()));
                }
                // 실패한 페이지도 순회한 것으로 계산하고, 다음 페이지·NCS 수집을 계속한다.
                if (totalCount != null && (long) pageNum * pageSize >= totalCount) {
                    completed = true;
                    break;
                }
            }
            if (!completed) {
                incompleteNcsCodes.add(ncsCode);
            }
        }
        return new Employment24CollectionResult(records, failures, incompleteNcsCodes);
    }
}
