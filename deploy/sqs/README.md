# Notification SQS Standard / DLQ

`queues.cloudformation.yaml`는 수동 검토·배포용 템플릿이다. 기존 배포 workflow/compose에 연결하지 않는다.
파일 추가는 실제 AWS 리소스 생성·IAM 적용·운영 발송 활성화를 뜻하지 않는다.

## 운영자가 확정할 입력

- QueueName: Standard 큐 이름. DLQ는 `<QueueName>-dlq`다.
- VisibilityTimeoutSeconds: Consumer `yon.notification.sqs.visibility-timeout-seconds`와 동일하게 설정한다(코드 기본 120초).
- MaxReceiveCount: 재전달 허용 횟수. DB 장애 시 정상 메시지도 DLQ로 갈 수 있음을 고려한다.
- SourceRetentionSeconds / DlqRetentionSeconds: DLQ가 더 길어야 한다. 자동 재발행 시점·소비 기록·Tombstone 보관 기간과 함께 확정한다.

재시도 횟수·보존 기간에는 템플릿 기본값을 두지 않는다. 값이 없으면 배포 입력이 필요하며 임의로 운영 정책을 확정하지 않는다.
CloudFormation의 개별 파라미터 범위 검사만으로 두 보존 기간의 대소 관계를 확인할 수 없으므로 운영자가 확인하고,
Consumer도 poll 전에 실제 큐 속성을 검증한다.

큐는 암호화(SSE-SQS)하고 TLS가 아닌 요청을 거부한다. FIFO 순서 보장·중복 제거 기능은 사용하지 않는다.
삭제/교체 시 큐를 Retain하여 대기 메시지를 실수로 지우지 않는다. 스택 삭제 후 재생성 시 남은 큐는 import 등 별도 절차가 필요하다.

## IAM 최소 권한

EC2 Instance Role로 주입하며 저장소·이미지에 자격증명을 넣지 않는다.

| 역할 | Resource | Action |
| --- | --- | --- |
| Application Publisher | source ARN | sqs:SendMessage |
| Notification Consumer | source ARN | sqs:ReceiveMessage, sqs:DeleteMessage, sqs:GetQueueAttributes |
| Notification 구성 확인 | DLQ ARN | sqs:GetQueueAttributes |

EC2 한 대에서 두 서비스를 같은 Instance Role로 실행하면 권한의 합집합이 적용되어 컨테이너별 IAM 분리가 되지 않는다.
서비스별 IAM 분리는 배포 담당자와 별도로 결정해야 하며 이번 템플릿이 이를 해결했다고 보지 않는다.
큐 생성·속성 변경·DLQ 수동 재처리는 애플리케이션 Role에 부여하지 않는다. KMS CMK를 쓰도록 변경하면 KMS 권한도 별도 검토한다.

## 배포·전환

1. 기존 Publisher/Receiver와 SQS Consumer 및 FCM 플래그를 끈 상태에서 Notification DDL/호환 수신부를 먼저 배포한다.
2. 승인된 입력으로 큐/DLQ를 구성하고 IAM·속성·보안 정책을 확인한다.
3. Notification에 source/DLQ URL·region을 주입한다. source visibility와 Consumer 값을 맞춘다.
4. 검증된 환경에서 Consumer를 켜고 구성 검증·DB 접근·중복 처리를 확인한다.
5. Application Publisher를 중지하고 기존 처리 중 lease가 끝났는지 확인한 뒤 transport를 `sqs`로 바꾸어 켠다.
6. HTTP/SQS Publisher를 동시에 실행하지 않는다. 메시지 본문과 application messageId는 변경하지 않는다.
7. SQS의 PUBLISHED는 큐 수락이며 알림 저장 완료가 아니다. source 적체·재전달·DLQ·소비 기록을 함께 확인한다.

현재 실제 플래그는 끈 상태를 유지하며 FCM/푸시 정책 확정과 실제 발송 검증은 별개다.
HTTP로 롤백해도 이미 SQS에 수락된 메시지는 사라지지 않는다. Consumer를 통한 drain/보류·중복 처리·소비 기록 보존 계획을 먼저 정한다.
PUBLISHED Outbox 전체를 일괄 PENDING으로 바꾸거나 큐를 purge하지 않는다.

## DLQ 복구

- source 적체, DLQ Visible/NotVisible, 가장 오래된 메시지 나이, 소비 실패 로그를 확인한다.
- 탈퇴 USER_WITHDRAWN이 DLQ에 있으면 Tombstone 적용이 지연되므로 우선 복구한다. 메시지 본문/토큰을 공용 로그에 남기지 않는다.
- 원인(DB·IAM·스키마·미지원 버전·identity 충돌)을 먼저 해결한다. 수정하지 않은 poison 메시지를 무한 재주입하지 않는다.
- 재처리는 승인된 운영자만 제한된 속도로 수행한다. 자동 DLQ Worker는 없다.
- 수동 메시지 복사 시 application messageId/sourceEventId/correlationId를 포함한 원본 본문을 유지하고, source 수락 후에만 해당 DLQ receiptHandle로 삭제한다.
- SQS 자체 messageId가 바뀌어도 애플리케이션 멱등 키는 바꾸지 않는다. 만료 메시지는 기존 만료 규칙에 따라 소비·억제된다.
- Standard 큐의 DLQ 보존 계산은 원래 enqueue 시각을 유지한다. DLQ 도착 시점부터 보존 기간이 새로 시작한다고 가정하지 않는다.

Tombstone·소비 기록 정리 주기는 queue/DLQ 보존과 운영 재발행 한도가 확정되기 전 자동화하지 않는다.
실제 AWS 배포, IAM 격리, CloudFormation 배포 검증, DLQ redrive 작업은 아직 실행하지 않았다.

공식 참고: [SQS DLQ](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html),
[보존 기간](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/setting-up-dead-letter-queue-retention.html),
[Queue CloudFormation](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-sqs-queue.html).
