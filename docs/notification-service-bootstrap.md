# 독립 Notification 프로젝트 — 개정안 1단계

## 구성

하나의 Git 저장소에서 backend와 notification-service를 독립 Gradle 프로젝트로 운영한다.
두 프로젝트 모두 Java 25, Spring Boot 4.1.1, Gradle 9.7.1을 사용한다.
루트 Gradle 멀티모듈, 프로젝트 간 의존성, 공유 sourceSet은 두지 않는다.

| 구분 | Application | Notification |
| --- | --- | --- |
| 디렉터리 | backend | notification-service |
| Main | com.team3.backend.BackendApplication | com.team3.notification.NotificationApplication |
| 기본 포트 | 8080 | 8081 (PORT 또는 server.port로 변경) |
| Boot JAR | backend/build/libs/app.jar | notification-service/build/libs/notification-service.jar |
| 이미지 경로 | 기존 GHCR backend 유지 | GHCR notification-service 추가 |

기존 역할 분리 브랜치와 DB 작업은 별도 보존한다. main 기준으로 시작해 이전 app.role,
역할 어노테이션, 역할별 Scan, notificationBootJar를 새 구현에 가져오지 않는다.
backend의 회원/정책/수집/정규화/판정/검색/관심 분야 코드는 main의 구현을 그대로 사용한다.
날짜 추적 브랜치는 이번 단계에 병합하지 않는다.

## 1단계 범위

Notification은 자체 진입점, 기본 Component Scan, Web/Validation/Security, Scheduling 설정,
독립 Wrapper/빌드/Dockerfile/CI를 가진다. 모든 요청은 기본 거부한다.
이 Security는 내부 인증 계약을 구현한 것이 아니다. 내부 API는 외부에 공개하지 않는다.
Scheduler 업무는 없고 Scheduling 인프라만 준비한다.

JPA·DataSource·스키마·계정과 명시적 DDL/마이그레이션은 2단계에서 프로젝트별로 구성한다.
Notification은 현재 DB 없이 기동한다. backend의 기존 DB 설정과 동작은 유지한다.
Outbox, 메시지 계약/Handler, HTTP/SQS, FCM, 업무 Entity는 해당 후속 단계에서 구현한다.

## 환경변수

backend의 기존 .env 항목과 DB 연결을 사용한다. Spring Boot 4용 dotenv 개발 전용 의존성만 재사용한다.
Notification은 현재 필수 외부 비밀값이 없다. 필요한 경우 notification-service/.env를 사용한다.
developmentOnly dotenv는 bootRun 개발 실행에 사용하고 기본 bootJar에서는 제외한다.
운영 JAR에는 환경변수로 설정을 주입한다. .env와 실제 키는 커밋하거나 이미지에 복사하지 않는다.

## 독립 실행·빌드

각 프로젝트 디렉터리에서 자기 Wrapper를 실행한다.

```bash
cd backend
./gradlew clean test bootJar
./gradlew bootRun
```

```bash
cd notification-service
./gradlew clean test bootJar
./gradlew bootRun
```

Notification 기동은 backend 기동이나 backend JAR 빌드를 요구하지 않는다.
Notification의 요청은 현재 모두 403이다. 정상 기동과 기능 API 제공을 구분한다.

## 검증

- backend: 기존 전체 회귀/Security/PostgreSQL 테스트와 Entity/Repository 자동 발견 완전성 검증
- Notification: 기동, 스케줄링 인프라, 익명·인증 사용자 요청 기본 거부
- 양쪽: 상대 프로젝트 클래스가 테스트 runtime classpath에 없는지 검증
- 두 Boot JAR: 각각의 Start-Class와 상대 프로젝트 클래스/개발 전용 dotenv 부재 확인
- Docker: 각자의 디렉터리만 빌드 컨텍스트로 사용

두 JAR은 필요한 의존성이 다르므로 BOOT-INF/lib 목록이 같을 필요가 없다.
PostgreSQL 조건부 테스트는 YON_TEST_DB_URL 등 테스트 전용 DB 설정과 실제 실행 여부를 확인한다.

저장소 루트에서 Docker 빌드는 다음과 같다.

```bash
docker build -t yon-application:phase1 backend
docker build -t yon-notification:phase1 notification-service
```

backend 기존 테스트/이미지 workflow는 유지하고 notification-service.yml을 독립 추가한다.
PR에서 Notification 테스트/빌드, main push에서 검증된 JAR의 이미지 발행을 수행한다.
이미지에는 같은 commit SHA를 태그/라벨로 기록한다. 실제 서버 배포/외부 네트워크 개방은 수행하지 않는다.
