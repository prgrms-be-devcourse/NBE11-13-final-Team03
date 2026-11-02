// 성능(기준선): API 하나씩 낮은 고정 도착률(PERF_RATE 요청/초)로 PERF_DUR 동안 따로 측정한다.
// 다른 API와 섞이지 않으므로 'API 하나가 혼자일 때의 응답 시간'을 본다. 맨 앞 워밍업 구간은 집계에서 뺀다.
import { thresholdsFor } from '../lib/config.js';
import { pickUser, pickOpportunityId, users } from '../lib/data.js';
import * as api from '../lib/api.js';

const RATE = Number(__ENV.PERF_RATE || 5);
const DUR = __ENV.PERF_DUR || '2m';
const WARM = __ENV.WARMUP_DUR || '1m';
const GAP = 5;
const toSec = (d) => (d.endsWith('m') ? Number(d.slice(0, -1)) * 60 : Number(d.replace('s', '')));

// 저장·해제는 같은 사용자·공고 짝으로 번갈아 부르도록 한 반복에서 둘 다 호출하되 해제는 따로 잰다
const ENDPOINTS = {
  search_keyword: () => api.searchKeyword(),
  search_filter: () => api.searchFilter(),
  detail: () => api.detail(pickOpportunityId()),
  regions: () => api.regionsList(),
  users_me: () => api.me(pickUser().token),
  unread_count: () => api.unreadCount(pickUser().token),
  recommendations: () => api.recommendations(pickUser().token),
  eligibility_list: () => api.eligibilityList(pickUser().token),
  eligibility_one: () => api.eligibilityOne(pickUser().token, pickOpportunityId()),
  saved_list: () => api.savedList(pickUser().token),
  behavior_event: () => api.behaviorEvent(pickUser().token, pickOpportunityId(), 'DETAIL_VIEW', 'SEARCH'),
  save_unsave: () => { const u = pickUser(); const id = pickOpportunityId(); api.save(u.token, id); api.unsave(u.token, id); },
};
const SELECTED = (__ENV.PERF_ENDPOINTS || Object.keys(ENDPOINTS).join(',')).split(',');

const scenarios = {
  warmup: {
    executor: 'constant-arrival-rate', exec: 'warm', rate: RATE * 2, timeUnit: '1s', duration: WARM,
    preAllocatedVUs: 10, maxVUs: 50, tags: { phase: 'warmup' },
  },
};
const thresholds = {};
let start = toSec(WARM) + GAP;
for (const ep of SELECTED) {
  scenarios[`ep_${ep}`] = {
    executor: 'constant-arrival-rate', exec: 'single', env: { EP: ep }, startTime: `${start}s`, rate: RATE,
    timeUnit: '1s', duration: DUR, preAllocatedVUs: 10, maxVUs: 60, tags: { phase: 'measure' },
  };
  Object.assign(thresholds, thresholdsFor(`ep_${ep}`));
  start += toSec(DUR) + GAP;
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios,
  thresholds,
};

export function setup() {
  console.log(`perf-baseline rate=${RATE}/s dur=${DUR} endpoints=${SELECTED.join(',')} users=${users.length}`);
}

const names = Object.keys(ENDPOINTS);
export function warm() {
  ENDPOINTS[names[Math.floor(Math.random() * names.length)]]();
}

export function single() {
  ENDPOINTS[__ENV.EP]();
}
