// 부하 A: 사용자 혼합 흐름을 동시 접속자 USERS명 상당의 도착률로 유지한다.
// 워밍업(제외) → 증가(제외) → main(측정, 기본 10분) → 감소(제외). 기준값은 main 구간에만 건다.
import { thresholdsFor } from '../lib/config.js';
import { visit, visitsPerSecond, vusFor, describeLoad } from '../lib/journeys.js';
import { USERS } from '../lib/config.js';

const RATE = visitsPerSecond(USERS);
const WARM = __ENV.WARMUP_DUR || '2m';
const RAMP = __ENV.RAMP_DUR || '3m';
const MAIN = __ENV.MAIN_DUR || '10m';
const DOWN = __ENV.DOWN_DUR || '1m';
const toSec = (d) => (d.endsWith('m') ? Number(d.slice(0, -1)) * 60 : Number(d.replace('s', '')));
const t1 = toSec(WARM);
const t2 = t1 + toSec(RAMP);
const t3 = t2 + toSec(MAIN);
const rate100 = Math.max(1, Math.round(RATE * 100)); // timeUnit 100s 로 소수 도착률 표현

export const options = {
  discardResponseBodies: false,
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
    down: {
      executor: 'ramping-arrival-rate', exec: 'run', startTime: `${t3}s`, startRate: rate100, timeUnit: '100s',
      stages: [{ target: Math.round(rate100 / 4), duration: DOWN }], preAllocatedVUs: vusFor(RATE / 2),
      maxVUs: vusFor(RATE, 2), tags: { phase: 'down' },
    },
  },
  thresholds: thresholdsFor('main'),
};

export function setup() {
  console.log(`load-mixed ${describeLoad(USERS)}`);
}

export function run() {
  visit();
}
