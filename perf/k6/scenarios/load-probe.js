// 한계 탐색: 혼합 흐름을 동시 접속자 100명 상당부터 100명씩 올린다(단계당 STEP_DUR).
// 단계별 p95·실패율·dropped_iterations 로 '무릎'(기준값을 처음 넘는 단계)을 summarize.py 가 찾는다.
// 서버가 완전히 무너지면 의미가 없으므로 실패율 20%를 30초 넘게 넘으면 중단한다.
import { visit, visitsPerSecond, vusFor, describeLoad } from '../lib/journeys.js';

const STEPS = (__ENV.PROBE_STEPS || '100,200,300,400,500,600').split(',').map(Number);
const STEP = __ENV.STEP_DUR || '2m';
const WARM = __ENV.WARMUP_DUR || '1m';
const toSec = (d) => (d.endsWith('m') ? Number(d.slice(0, -1)) * 60 : Number(d.replace('s', '')));

const scenarios = {
  warmup: {
    executor: 'constant-arrival-rate', exec: 'run', rate: Math.round(visitsPerSecond(STEPS[0]) * 50), timeUnit: '100s',
    duration: WARM, preAllocatedVUs: vusFor(visitsPerSecond(STEPS[0])), maxVUs: vusFor(visitsPerSecond(STEPS[0]), 2),
    tags: { phase: 'warmup' },
  },
};
let start = toSec(WARM);
for (const users of STEPS) {
  const rate = visitsPerSecond(users);
  scenarios[`step_${users}`] = {
    executor: 'constant-arrival-rate', exec: 'run', startTime: `${start}s`, rate: Math.round(rate * 100),
    timeUnit: '100s', duration: STEP, preAllocatedVUs: vusFor(rate), maxVUs: vusFor(rate, 3),
    tags: { phase: 'probe', users: String(users) },
  };
  start += toSec(STEP);
}

export const options = {
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  scenarios,
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.2', abortOnFail: true, delayAbortEval: '30s' }],
  },
};

export function setup() {
  console.log(`load-probe steps=${STEPS.join(',')} ${STEPS.map((u) => describeLoad(u)).join(' | ')}`);
}

export function run() {
  visit();
}
