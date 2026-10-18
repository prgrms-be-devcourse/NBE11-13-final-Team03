package com.team3.backend.collection.dto.external;

import com.team3.backend.collection.dto.response.Employment24PageFailureResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 페이지 조회 시점의 원본을 보존한다. 목록 변경으로 같은 항목이 여러 페이지에 나타날 수 있다.
 * 실패 페이지 재조회도 동일한 목록 스냅샷을 보장하지 않으므로, 저장 단계는 외부 식별자 기반 멱등 처리가 필요하다.
 */
public record Employment24CollectionResult(
        List<JsonNode> records,
        List<Employment24PageFailureResponse> pageFailures,
        List<String> incompleteNcsCodes
) {
    public Employment24CollectionResult {
        records = List.copyOf(records);
        pageFailures = List.copyOf(pageFailures);
        incompleteNcsCodes = List.copyOf(incompleteNcsCodes);
    }

    /**
     * 모든 NCS의 페이지 순회를 마쳤는지 나타낸다. 첫 페이지 실패·인증 오류·페이지 상한은 미완료다.
     * 중간 페이지 실패는 pageFailures로 확인한다. true여도 전체 성공이나 중복·누락 없음을 보장하지 않는다.
     */
    public boolean completed() {
        return incompleteNcsCodes.isEmpty();
    }
}
