package com.team3.backend.collection.dto.external;

import java.time.LocalDate;
import java.util.Objects;

/** NCS 1차분류 하나에 대한 페이지 조회 조건. 인증키는 포함하지 않는다. */
public record Employment24SearchRequest(
        String srchNcs1,
        LocalDate srchTraStDt,
        LocalDate srchTraEndDt,
        int pageNum,
        int pageSize
) {
    public Employment24SearchRequest {
        if (srchNcs1 == null || !srchNcs1.matches("0[1-9]|1[0-9]|2[0-4]")) {
            throw new IllegalArgumentException("NCS 1차분류는 01~24의 두 자리 코드여야 합니다.");
        }
        Objects.requireNonNull(srchTraStDt, "훈련 시작일 조회 하한이 필요합니다.");
        Objects.requireNonNull(srchTraEndDt, "훈련 시작일 조회 상한이 필요합니다.");
        if (srchTraEndDt.isBefore(srchTraStDt)) {
            throw new IllegalArgumentException("조회 상한은 하한보다 빠를 수 없습니다.");
        }
        if (pageNum < 1 || pageNum > 1000 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("페이지 번호는 1~1000, 크기는 1~100이어야 합니다.");
        }
    }

    /** 수집 실행 시 한국 날짜를 한 번 계산해 전달하며, 양 끝 날짜를 포함한다. */
    public static Employment24SearchRequest forNextSixMonths(
            String ncsCode, LocalDate today, int pageNum, int pageSize) {
        Objects.requireNonNull(today, "수집 기준일이 필요합니다.");
        return new Employment24SearchRequest(ncsCode, today, today.plusMonths(6), pageNum, pageSize);
    }
}
