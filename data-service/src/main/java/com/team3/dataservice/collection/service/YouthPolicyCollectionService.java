package com.team3.dataservice.collection.service;

import com.team3.dataservice.collection.client.YouthPolicyClient;
import com.team3.dataservice.collection.dto.external.YouthPolicyCollectionResult;
import com.team3.dataservice.collection.dto.external.CollectionSummary;
import com.team3.dataservice.collection.dto.external.YouthPolicyPage;
import com.team3.dataservice.collection.dto.response.SyncPageFailureResponse;
import com.team3.dataservice.collection.exception.YouthPolicyClientException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class YouthPolicyCollectionService {
    private static final Logger log = LoggerFactory.getLogger(YouthPolicyCollectionService.class);
    private static final int MAX_CONSECUTIVE_PAGE_FAILURES = 3;
    private final YouthPolicyClient client;

    public YouthPolicyCollectionService(YouthPolicyClient client) {
        this.client = client;
    }

    public YouthPolicyCollectionResult collect() {
        return collect(100, 1000);
    }

    public YouthPolicyCollectionResult collect(int pageSize, int maxPages) {
        List<JsonNode> records = new ArrayList<>();
        var summary = collectPages(pageSize, maxPages, (offset, page) -> records.addAll(page));
        return new YouthPolicyCollectionResult(records, summary.pageFailures(), summary.completed());
    }

    /** offset은 전체 수집 결과 내 시작 인덱스다. 처리기가 반환한 뒤 다음 페이지를 조회한다. */
    public CollectionSummary collectPages(int pageSize, int maxPages,
                                          BiConsumer<Integer, List<JsonNode>> pageHandler) {
        if (pageSize < 1 || maxPages < 1) {
            throw new IllegalArgumentException("페이지 크기와 최대 조회 페이지 수는 1 이상이어야 합니다.");
        }
        Objects.requireNonNull(pageHandler, "페이지 처리기가 필요합니다.");
        int fetchedCount = 0;
        List<SyncPageFailureResponse> failures = new ArrayList<>();
        Integer totalCount = null;
        int consecutiveFailures = 0;
        for (int pageNumber = 1; ; pageNumber++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("수집 실행이 중단되었습니다.");
            }
            YouthPolicyPage page = null;
            try {
                page = fetchWithRetry(pageNumber, pageSize);
            } catch (YouthPolicyClientException exception) {
                failures.add(new SyncPageFailureResponse(pageNumber,
                        exception.getErrorCode(), exception.getMessage(), exception.isRetryable()));
                consecutiveFailures++;
                if (pageNumber == 1 || !exception.isRetryable()
                        || consecutiveFailures >= MAX_CONSECUTIVE_PAGE_FAILURES) {
                    return new CollectionSummary(fetchedCount, false, failures, List.of(), List.of());
                }
            }
            if (page != null) {
                consecutiveFailures = 0;
                pageHandler.accept(fetchedCount, page.records());
                fetchedCount += page.records().size();
                if (page.totalCount() != null) {
                    totalCount = page.totalCount();
                }
                if (page.records().isEmpty()) {
                    return new CollectionSummary(fetchedCount, true, failures, List.of(), List.of());
                }
            }
            if (totalCount != null && (long) pageNumber * pageSize >= totalCount) {
                return new CollectionSummary(fetchedCount, true, failures, List.of(), List.of());
            }
            if (pageNumber == maxPages) {
                return new CollectionSummary(fetchedCount, false, failures, List.of(), List.of());
            }
        }
    }

    private YouthPolicyPage fetchWithRetry(int pageNumber, int pageSize) {
        for (int attempt = 1; ; attempt++) {
            CollectionRetry.beforeAttempt(attempt, 2_000L);
            try {
                return client.fetchPage(pageNumber, pageSize);
            } catch (YouthPolicyClientException exception) {
                if (!exception.isRetryable() || attempt >= CollectionRetry.MAX_ATTEMPTS) {
                    throw exception;
                }
                log.warn("source=YOUTH_POLICY page={} errorCode={} attempt={} maxAttempts={} retry=scheduled",
                        pageNumber, exception.getErrorCode(), attempt, CollectionRetry.MAX_ATTEMPTS);
            }
        }
    }
}
