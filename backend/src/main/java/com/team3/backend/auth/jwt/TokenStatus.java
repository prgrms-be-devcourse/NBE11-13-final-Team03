package com.team3.backend.auth.jwt;

public enum TokenStatus {
    VALID,   // 유효한 토큰
    EXPIRED, // 만료된 토큰
    INVALID  // 서명 불일치, 형식 오류 등
}
