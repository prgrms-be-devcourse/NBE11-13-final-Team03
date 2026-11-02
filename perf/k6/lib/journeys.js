// 혼합 흐름: k6 반복 1회 = 사용자 한 명의 방문 1회.
// 화면이 바뀔 때마다 think() 로 1~3초 쉬고, 방문 끝에도 한 번 쉰다(다음 화면을 보는 시간).
import { sleep } from 'k6';
import { THINK_MIN, THINK_MAX, THINK_AVG, USERS } from './config.js';
import { pickUser, pickOpportunityId, pick } from './data.js';
import * as api from './api.js';

export function think() {
  sleep(THINK_MIN + Math.random() * (THINK_MAX - THINK_MIN));
}

function firstId(page) {
  if (page && page.content && page.content.length) return pick(page.content).opportunityId;
  return pickOpportunityId();
}

// 비회원 탐색: 검색(검색어) → 검색(필터·정렬) → 상세 1~2회
export function anonymous() {
  api.searchKeyword();
  think();
  const page = api.searchFilter();
  think();
  api.detail(firstId(page));
  if (Math.random() < 0.5) {
    think();
    api.detail(pickOpportunityId());
  }
  think();
}

// 로그인 탐색: 내 정보 + 안 읽은 알림 수(화면 진입) → 추천 → 판정 목록 → 상세 + 단건 판정 + 행동 이벤트
export function loggedIn() {
  const u = pickUser();
  api.me(u.token);
  api.unreadCount(u.token);
  think();
  const recs = api.recommendations(u.token);
  think();
  api.eligibilityList(u.token);
  think();
  let id = pickOpportunityId();
  if (Array.isArray(recs) && recs.length) id = pick(recs).opportunityId || id;
  else if (recs && Array.isArray(recs.items) && recs.items.length) id = pick(recs.items).opportunityId || id;
  api.detail(id);
  api.eligibilityOne(u.token, id);
  api.behaviorEvent(u.token, id, 'DETAIL_VIEW', 'RECOMMENDATION');
  think();
}

// 저장: 저장 → 저장 목록 → 저장 해제
export function saver() {
  const u = pickUser();
  const id = pickOpportunityId();
  api.save(u.token, id);
  think();
  api.savedList(u.token);
  think();
  api.unsave(u.token, id);
  think();
}

// 알림: 알림 목록 → 모두 읽음
export function notifier() {
  const u = pickUser();
  api.notifications(u.token);
  think();
  api.readAll(u.token);
  think();
}

// 흐름별 비율과 방문당 쉬는 횟수(기대값). 도착률 환산에 쓴다.
export const MIX = [
  { name: 'anonymous', weight: Number(__ENV.MIX_ANON || 40), fn: anonymous, thinks: 3.5, requests: 3.5 },
  { name: 'loggedIn', weight: Number(__ENV.MIX_LOGIN || 45), fn: loggedIn, thinks: 4, requests: 7 },
  { name: 'saver', weight: Number(__ENV.MIX_SAVE || 10), fn: saver, thinks: 3, requests: 3 },
  { name: 'notifier', weight: Number(__ENV.MIX_NOTIF || 5), fn: notifier, thinks: 2, requests: 2 },
];
const TOTAL = MIX.reduce((s, m) => s + m.weight, 0);

export function visit() {
  let x = Math.random() * TOTAL;
  for (const m of MIX) {
    if ((x -= m.weight) < 0) return m.fn();
  }
  return MIX[0].fn();
}

// 방문 1회의 기대 길이(초) = 쉬는 시간 합 + 요청 시간(요청 수 × 가정 응답시간)
export const EXPECTED_REQUEST_SEC = Number(__ENV.EXPECTED_REQUEST_MS || 60) / 1000;
export const VISIT_SECONDS = MIX.reduce((s, m) => s + (m.weight / TOTAL) * (m.thinks * THINK_AVG + m.requests * EXPECTED_REQUEST_SEC), 0);
export const REQUESTS_PER_VISIT = MIX.reduce((s, m) => s + (m.weight / TOTAL) * m.requests, 0);

// 동시 접속자 n명이 계속 방문할 때의 도착률(방문/초)
export function visitsPerSecond(users) {
  return users / VISIT_SECONDS;
}

export function vusFor(rate, factor) {
  return Math.ceil(rate * VISIT_SECONDS * (factor || 1.3)) + 5;
}

export function describeLoad(users) {
  const rate = visitsPerSecond(users || USERS);
  return `users=${users || USERS} visitSec=${VISIT_SECONDS.toFixed(2)} visits/s=${rate.toFixed(2)} ` +
    `req/s≈${(rate * REQUESTS_PER_VISIT).toFixed(1)}`;
}
