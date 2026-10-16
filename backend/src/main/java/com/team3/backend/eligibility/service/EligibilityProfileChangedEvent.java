package com.team3.backend.eligibility.service;

/** 판정 프로필이 새로 만들어졌거나 판정에 쓰이는 값이 바뀌었을 때 (profileVersion 증가) */
public record EligibilityProfileChangedEvent(Long userId) {
}
