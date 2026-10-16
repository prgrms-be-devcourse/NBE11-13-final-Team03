package com.team3.backend.collection.service;

import com.team3.backend.collection.client.YouthPolicyClient;
import com.team3.backend.collection.dto.external.YouthPolicyCollectionResult;
import com.team3.backend.collection.dto.external.YouthPolicyPage;
import com.team3.backend.collection.dto.response.SyncPageFailureResponse;
import com.team3.backend.collection.exception.YouthPolicyClientException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class YouthPolicyCollectionService {
    private final YouthPolicyClient client;

    public YouthPolicyCollectionService(YouthPolicyClient client) {
        this.client = client;
    }

    public YouthPolicyCollectionResult collect() {
        return collect(100, 1000);
    }

    public YouthPolicyCollectionResult collect(int pageSize, int maxPages) {
        if (pageSize < 1 || maxPages < 1) {
            throw new IllegalArgumentException("페이지 크기와 최대 조회 페이지 수는 1 이상이어야 합니다.");
        }
        List<JsonNode> records = new ArrayList<>();
        List<SyncPageFailureResponse> failures = new ArrayList<>();
        Integer totalCount = null;
        for (int pageNumber = 1; ; pageNumber++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("수집 실행이 중단되었습니다.");
            }
            try {
                YouthPolicyPage page = client.fetchPage(pageNumber, pageSize);
                records.addAll(page.records());
                if (page.totalCount() != null) {
                    totalCount = page.totalCount();
                }
                if (page.records().isEmpty()) {
                    return new YouthPolicyCollectionResult(records, failures, true);
                }
            } catch (YouthPolicyClientException exception) {
                failures.add(new SyncPageFailureResponse(pageNumber,
                        exception.getErrorCode(), exception.getMessage(), exception.isRetryable()));
                if (exception.getErrorCode().equals("HTTP_401") || exception.getErrorCode().equals("HTTP_403")) {
                    return new YouthPolicyCollectionResult(records, failures, false);
                }
            }
            if (totalCount != null && (long) pageNumber * pageSize >= totalCount) {
                return new YouthPolicyCollectionResult(records, failures, true);
            }
            if (pageNumber == maxPages) {
                return new YouthPolicyCollectionResult(records, failures, false);
            }
        }
    }
}
