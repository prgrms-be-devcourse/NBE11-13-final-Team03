// 부하 B: 부하 A와 같은 사용자 부하 + main 시작 COLLECT_DELAY 뒤 두 출처 수집을 관리자 API로 접수한다.
// 외부 API 목은 'd1'(전날 대비 약 10% 변경)을 돌려주므로 운영의 매일 수집과 같은 변경·정규화 작업이 생긴다.
// 수집 시작·종료 시각은 collection_event 지표(event=start|end)로 남아 summarize.py 가 수집 전·중 구간을 나눈다.
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { thresholdsFor, USERS } from '../lib/config.js';
import { visit, visitsPerSecond, vusFor, describeLoad } from '../lib/journeys.js';
import { admins } from '../lib/data.js';
import * as api from '../lib/api.js';

const RATE = visitsPerSecond(USERS);
const WARM = __ENV.WARMUP_DUR || '2m';
const RAMP = __ENV.RAMP_DUR || '3m';
const MAIN = __ENV.MAIN_DUR || '15m';
const DOWN = __ENV.DOWN_DUR || '1m';
const COLLECT_DELAY = __ENV.COLLECT_DELAY || '3m';
const toSec = (d) => (d.endsWith('m') ? Number(d.slice(0, -1)) * 60 : Number(d.replace('s', '')));
const t2 = toSec(WARM) + toSec(RAMP);
const t3 = t2 + toSec(MAIN);
const rate100 = Math.max(1, Math.round(RATE * 100));

const collectionEvents = new Counter('collection_event');
const collectionDuration = new Trend('collection_duration_s');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate', exec: 'run', rate: Math.round(rate100 / 2), timeUnit: '100s',
      duration: WARM, preAllocatedVUs: vusFor(RATE / 2), maxVUs: vusFor(RATE, 2), tags: { phase: 'warmup' },
    },
    ramp: {
      executor: 'ramping-arrival-rate', exec: 'run', startTime: WARM, startRate: Math.round(rate100 / 2), timeUnit: '100s',
      stages: [{ target: rate100, duration: RAMP }], preAllocatedVUs: vusFor(RATE), maxVUs: vusFor(RATE, 2),
      tags: { phase: 'ramp' },
    },
    main: {
      executor: 'constant-arrival-rate', exec: 'run', startTime: `${t2}s`, rate: rate100, timeUnit: '100s',
      duration: MAIN, preAllocatedVUs: vusFor(RATE), maxVUs: vusFor(RATE, 2), tags: { phase: 'main' },
    },
    collector: {
      executor: 'per-vu-iterations', exec: 'collect', vus: 1, iterations: 1,
      startTime: `${t2 + toSec(COLLECT_DELAY)}s`, maxDuration: `${toSec(MAIN) + 600}s`, tags: { phase: 'collector' },
    },
    down: {
      executor: 'ramping-arrival-rate', exec: 'run', startTime: `${t3}s`, startRate: rate100, timeUnit: '100s',
      stages: [{ target: Math.round(rate100 / 4), duration: DOWN }], preAllocatedVUs: vusFor(RATE / 2),
      maxVUs: vusFor(RATE, 2), tags: { phase: 'down' },
    },
  },
  thresholds: thresholdsFor('main'),
};

export function setup() {
  console.log(`load-with-collection ${describeLoad(USERS)} collectAfter=${COLLECT_DELAY}`);
}

export function run() {
  visit();
}

export function collect() {
  const token = admins[0].token;
  const started = Date.now();
  collectionEvents.add(1, { event: 'start' });
  const jobs = {};
  for (const source of ['EMPLOYMENT24', 'YOUTH_POLICY']) {
    jobs[source] = api.startSync(token, source);
  }
  const done = {};
  while (Object.keys(done).length < Object.keys(jobs).length) {
    sleep(5);
    for (const source of Object.keys(jobs)) {
      if (done[source] || !jobs[source]) { done[source] = done[source] || 'NOT_STARTED'; continue; }
      const s = api.syncStatus(token, jobs[source]);
      if (s && s.status && s.status !== 'PENDING' && s.status !== 'RUNNING') {
        done[source] = s.status;
        collectionDuration.add((Date.now() - started) / 1000, { source, status: s.status });
        console.log(`collection ${source} ${s.status} fetched=${s.fetchedCount} updated=${s.updatedCount} ` +
          `new=${s.newCount} after ${((Date.now() - started) / 1000).toFixed(0)}s`);
      }
    }
  }
  collectionEvents.add(1, { event: 'end' });
}
