// 테스트와 무관한 Chrome 백그라운드 서비스(업데이트 확인, 기본 앱 설치, 최적화 힌트 모델 등)를 끈다.
// 새 프로필마다 이 작업이 돌아 동시 실행 중 시작·종료가 수십 초 늦어지고 실행 시간 제한을 넘겼다. 웹 페이지 동작은 바꾸지 않는다.
export const chromeServiceFlags = [
  '--disable-component-update',
  '--disable-default-apps',
  '--disable-component-extensions-with-background-pages',
  '--disable-breakpad',
  '--disable-client-side-phishing-detection',
  '--disable-domain-reliability',
  '--metrics-recording-only',
  '--disable-features=OptimizationHints,MediaRouter,Translate',
]
