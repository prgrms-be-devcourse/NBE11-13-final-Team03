# 수집 정제 요청 알림

`collection.normalization.enabled` (환경변수 `NORMALIZATION_ENABLED`, 기본 `true`)로 활성화한다.
PostgreSQL 이외의 DB에서는 시작하지 않는다. 수집 저장·정제 요청 등록 코드는 변경하지 않는다.

앱 시작 시 `raw_normalization_request` INSERT 트리거와 함수를 반복 적용한다.
실행 계정에는 해당 테이블의 트리거 생성 및 함수 생성/교체 권한이 필요하다.
채널은 `raw_normalization_requested`, payload는 등록된 정제 요청의 `raw_normalization_request.id`를 문자열로 보낸다.
예: 요청 ID가 123이면 `pg_notify('raw_normalization_requested', '123')` 형태다.
원본 레코드 ID가 아니라 정제 요청 ID이며 트리거에서 `NEW.id::text`로 전달한다.
NOTIFY는 원본과 요청이 커밋된 뒤 전달되며 롤백 시 전달되지 않는다.
전체 수집 완료를 기다리지 않고 기존 항목별 요청 커밋 시 신호를 보낸다.

같은 앱의 소비자는 `NormalizationRequestsAvailable` Spring 이벤트를 구독하고
기존 방식대로 PENDING 요청을 조회·선점한다. 별도 프로세스는 같은 DB에서
`LISTEN raw_normalization_requested`를 실행한다.
DB 채널 구독자는 payload의 요청 ID로 처리 대상을 조회할 수 있다.
같은 앱의 Spring 이벤트는 기존처럼 ID 없는 깨우기 신호를 유지한다.
요청 처리, 상태 변경, 정규화 서비스 연결은 정제 영역의 책임이며 여기서는 수행하지 않는다.

수신 연결은 하나를 유지한다. 연결 풀에는 수집과 소비자 처리를 위한 여유 연결이 필요하다.
수신 실패 시 5초 후 재연결하고, LISTEN 등록 후와 30초 대기 종료 후에도 이벤트를 발행한다.
소비자는 이벤트 횟수에 의존하지 말고 영속 요청 큐를 기준으로 멱등 처리해야 한다.
앱 종료 시 수신 연결을 닫는다. 비활성화하더라도 이미 설치된 트리거는 유지된다.

PostgreSQL 동작 근거: https://www.postgresql.org/docs/18/sql-listen.html
