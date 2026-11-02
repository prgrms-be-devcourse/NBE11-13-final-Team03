// 사용자 API 호출. 요청마다 ep(API 이름)·slo(read|heavy|admin) 태그와 경로 묶음 name 태그를 단다.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './config.js';
import { pick, keywords, regions, categories } from './data.js';

const OK_OR_CONFLICT = http.expectedStatuses({ min: 200, max: 299 }, 409);
const OK_OR_NOT_FOUND = http.expectedStatuses({ min: 200, max: 299 }, 404);

function uuidHex() {
  let s = '';
  for (let i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16);
  return s;
}

// 인증·CSRF: access_token 쿠키에 미리 발급한 토큰을 넣고,
// CSRF는 같은 임의 값을 XSRF-TOKEN 쿠키와 X-XSRF-TOKEN 헤더로 보내는 double-submit 방식.
// 요청마다 새 쿠키 저장소를 써서 이전 방문(다른 사용자)의 쿠키가 섞이지 않게 한다.
function params(ep, slo, name, token, extra) {
  const cookies = {};
  const headers = { Accept: 'application/json' };
  if (token) cookies.access_token = { value: token, replace: true };
  if (extra && extra.write) {
    const x = uuidHex();
    cookies['XSRF-TOKEN'] = { value: x, replace: true };
    headers['X-XSRF-TOKEN'] = x;
    headers['Content-Type'] = 'application/json';
  }
  const p = { headers, cookies, jar: new http.CookieJar(), tags: { ep, slo, name } };
  if (extra && extra.responseCallback) p.responseCallback = extra.responseCallback;
  return p;
}

function ok(res, ep, allowed) {
  const pass = allowed ? allowed.includes(res.status) : res.status >= 200 && res.status < 300;
  check(res, { [`${ep} ok`]: () => pass });
  return pass;
}

function json(res) {
  try { return res.json(); } catch (e) { return null; }
}

function isoDate(offsetDays) {
  const d = new Date(Date.now() + offsetDays * 86400000 + 9 * 3600000); // KST 날짜
  return d.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------- 공개 조회
export function searchKeyword() {
  const kw = pick(keywords);
  const res = http.get(`${BASE_URL}/api/opportunities?keyword=${encodeURIComponent(kw)}&size=20`,
    params('search_keyword', 'read', 'GET /api/opportunities?keyword'));
  ok(res, 'search_keyword');
  return json(res);
}

export function searchFilter() {
  const r = pick(regions);
  const mode = Math.floor(Math.random() * 3);
  let q;
  if (mode === 0) {
    q = `type=YOUTH_POLICY&region=${r.code}&applicationStatus=OPEN&sort=DEADLINE_PRIORITY`;
  } else if (mode === 1) {
    q = `type=VOCATIONAL_TRAINING&region=${r.code}&startDateFrom=${isoDate(0)}&startDateTo=${isoDate(60)}&sort=START_DATE_ASC`;
  } else {
    const c = pick(categories);
    q = `category=${encodeURIComponent(c.category)}&sort=LATEST`;
  }
  const res = http.get(`${BASE_URL}/api/opportunities?${q}&size=20`,
    params('search_filter', 'read', 'GET /api/opportunities?filter'));
  ok(res, 'search_filter');
  return json(res);
}

export function detail(id) {
  const res = http.get(`${BASE_URL}/api/opportunities/${id}`,
    params('detail', 'read', 'GET /api/opportunities/{id}'));
  ok(res, 'detail');
  return res;
}

export function regionsList() {
  const res = http.get(`${BASE_URL}/api/regions`, params('regions', 'read', 'GET /api/regions'));
  ok(res, 'regions');
  return res;
}

// ---------------------------------------------------------------- 로그인 조회
export function me(token) {
  const res = http.get(`${BASE_URL}/api/users/me`, params('users_me', 'read', 'GET /api/users/me', token));
  ok(res, 'users_me');
  return res;
}

export function unreadCount(token) {
  const res = http.get(`${BASE_URL}/api/notifications/unread-count`,
    params('unread_count', 'read', 'GET /api/notifications/unread-count', token));
  ok(res, 'unread_count');
  // 알림 서버 호출이 막히면(동시 호출 8개 초과 등) backend는 200 + available:false 로 답한다 → 따로 센다
  const body = json(res);
  check(res, { 'unread_count available': () => !(body && body.available === false) });
  return res;
}

export function recommendations(token) {
  const res = http.get(`${BASE_URL}/api/users/me/recommendations`,
    params('recommendations', 'heavy', 'GET /api/users/me/recommendations', token));
  ok(res, 'recommendations');
  return json(res);
}

export function eligibilityList(token) {
  const res = http.get(`${BASE_URL}/api/users/me/opportunities/eligibility?size=20`,
    params('eligibility_list', 'heavy', 'GET /api/users/me/opportunities/eligibility', token));
  ok(res, 'eligibility_list');
  return json(res);
}

export function eligibilityOne(token, id) {
  const res = http.get(`${BASE_URL}/api/opportunities/${id}/eligibility`,
    params('eligibility_one', 'read', 'GET /api/opportunities/{id}/eligibility', token));
  ok(res, 'eligibility_one');
  return res;
}

export function savedList(token) {
  const res = http.get(`${BASE_URL}/api/users/me/saved-opportunities?size=20`,
    params('saved_list', 'read', 'GET /api/users/me/saved-opportunities', token));
  ok(res, 'saved_list');
  return res;
}

export function notifications(token) {
  const res = http.get(`${BASE_URL}/api/notifications?size=20`,
    params('notifications', 'read', 'GET /api/notifications', token));
  ok(res, 'notifications');
  return res;
}

// ---------------------------------------------------------------- 쓰기 (CSRF)
export function behaviorEvent(token, id, eventType, source) {
  const res = http.post(`${BASE_URL}/api/opportunities/${id}/events`, JSON.stringify({ eventType, source }),
    params('behavior_event', 'read', 'POST /api/opportunities/{id}/events', token, { write: true }));
  ok(res, 'behavior_event');
  return res;
}

export function save(token, id) {
  const res = http.post(`${BASE_URL}/api/opportunities/${id}/save?source=SEARCH`, null,
    params('save', 'read', 'POST /api/opportunities/{id}/save', token,
      { write: true, responseCallback: OK_OR_CONFLICT }));
  ok(res, 'save', [200, 201, 204, 409]);
  return res;
}

export function unsave(token, id) {
  const res = http.del(`${BASE_URL}/api/opportunities/${id}/save`, null,
    params('unsave', 'read', 'DELETE /api/opportunities/{id}/save', token,
      { write: true, responseCallback: OK_OR_NOT_FOUND }));
  ok(res, 'unsave', [200, 204, 404]);
  return res;
}

export function readAll(token) {
  const res = http.patch(`${BASE_URL}/api/notifications/read-all`, null,
    params('read_all', 'read', 'PATCH /api/notifications/read-all', token, { write: true }));
  ok(res, 'read_all');
  return res;
}

// ---------------------------------------------------------------- 관리자 (수집 동시 시나리오)
export function startSync(adminToken, source) {
  const res = http.post(`${BASE_URL}/api/admin/collection/syncs/${source}?pageSize=100&maxPages=1000`, null,
    params('admin_sync_start', 'admin', 'POST /api/admin/collection/syncs/{source}', adminToken, { write: true }));
  ok(res, 'admin_sync_start', [202]);
  const body = json(res);
  return body ? body.jobId : null;
}

export function syncStatus(adminToken, jobId) {
  const res = http.get(`${BASE_URL}/api/admin/collection/syncs/${jobId}`,
    params('admin_sync_status', 'admin', 'GET /api/admin/collection/syncs/{jobId}', adminToken));
  return json(res);
}
