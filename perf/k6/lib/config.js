// 공통 설정. 값은 run.sh 가 넘기는 환경변수(-e)로 바꾼다.
export const BASE_URL = __ENV.BASE_URL || 'http://backend:8080';
export const DATA_DIR = __ENV.DATA_DIR || '/perf/data';

// 화면 사이 대기 시간(초). 방문 길이와 도착률 환산에 같이 쓴다.
export const THINK_MIN = Number(__ENV.THINK_MIN || 1);
export const THINK_MAX = Number(__ENV.THINK_MAX || 3);
export const THINK_AVG = (THINK_MIN + THINK_MAX) / 2;

// 동시 접속자 목표 (11/01 결정: 100명)
export const USERS = Number(__ENV.USERS || 100);

// 기준값(SLO, 11/01 확정). 추천·판정 목록은 heavy, 나머지 사용자 API는 read 로 묶는다.
export const SLO = {
  read: Number(__ENV.SLO_READ_P95_MS || 500),
  heavy: Number(__ENV.SLO_HEAVY_P95_MS || 1000),
  errorRate: Number(__ENV.SLO_ERROR_RATE || 0.01),
};

export function thresholdsFor(scenario) {
  return {
    [`http_req_duration{scenario:${scenario},slo:read}`]: [`p(95)<${SLO.read}`],
    [`http_req_duration{scenario:${scenario},slo:heavy}`]: [`p(95)<${SLO.heavy}`],
    [`http_req_failed{scenario:${scenario}}`]: [`rate<${SLO.errorRate}`],
  };
}
