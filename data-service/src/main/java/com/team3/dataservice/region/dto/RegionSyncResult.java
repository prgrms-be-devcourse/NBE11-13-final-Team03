package com.team3.dataservice.region.dto;

import java.util.List;

/**
 * missingCodes: DB에는 있지만 이번 API 응답에 없는 코드. 다른 테이블이 참조할 수 있어 삭제하지 않고 보고만 한다.
 */
public record RegionSyncResult(int created, int updated, int unchanged, List<String> missingCodes) {
}
