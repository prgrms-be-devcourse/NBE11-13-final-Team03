import type { SyncJob, SyncSource, SyncStatus } from '../../api/admin'

/** 대기·실행 중인 작업이 목록에 있을 때만 목록을 다시 조회한다. 완료된 작업만 있으면 폴링하지 않는다. */
export const syncListPollMs = 10_000
export function syncListRefetchInterval(jobs: readonly Pick<SyncJob, 'status'>[] | undefined): number | false {
  return jobs?.some((job) => job.status === 'PENDING' || job.status === 'RUNNING') ? syncListPollMs : false
}

/**
 * 보관 실행 가능 여부의 미리보기 조건. 미리보기를 다시 받는 중이거나 조회에 실패했으면 막는다.
 * 결과가 불확실한 실행 뒤에는 그때의 미리보기 데이터 갱신 횟수(dataUpdateCount)보다 많아져야, 즉 그 뒤에 새 미리보기(새 planToken)를
 * 성공적으로 받아야 다시 실행할 수 있다. 시각 비교는 같은 밀리초에 응답이 오면 영원히 잠길 수 있어 쓰지 않는다.
 */
export function retentionExecutionLocked(state: { uncertainBaseline: number | null; dataUpdateCount: number; isFetching: boolean; isError: boolean }): boolean {
  return state.isFetching || state.isError || (state.uncertainBaseline !== null && state.dataUpdateCount <= state.uncertainBaseline)
}

export const sourceLabels: Record<SyncSource, string> = { YOUTH_POLICY: '온통청년 (청년정책)', EMPLOYMENT24: '고용24 (직업훈련)' }
export const statusLabels: Record<SyncStatus, string> = {
  PENDING: '대기', RUNNING: '실행 중', SUCCESS: '성공', PARTIAL_SUCCESS: '부분 성공', FAILED: '실패', INTERRUPTED: '중단',
}
// 상태 글자색만 바꾼다. 배지 배경은 쓰지 않는다.
export const statusTone: Record<SyncStatus, 'ok' | 'warn' | 'danger' | 'muted'> = {
  PENDING: 'muted', RUNNING: 'muted', SUCCESS: 'ok', PARTIAL_SUCCESS: 'warn', FAILED: 'danger', INTERRUPTED: 'warn',
}
// RetentionAdminService의 미리보기 status는 READY 또는 UserBehaviorEventRetention.Status의 SKIPPED_* 이름이다.
// 설명은 백엔드 판정 조건(plan·verifiedToday)을 그대로 옮긴다. 알 수 없는 값은 원문 코드만 보여 준다. API 값은 바꾸지 않는다.
export type RetentionStatusView = { label: string; description?: string; tone: 'ok' | 'warn' | 'muted' }
const retentionStatuses: Record<string, RetentionStatusView> = {
  READY: { label: '실행 가능', description: '미리보기 계획대로 보관 정리를 실행할 수 있어요.', tone: 'ok' },
  SKIPPED_LOCKED: { label: '다른 정리 진행 중', description: '다른 보관 정리가 잠금을 잡고 있어 이번에는 아무것도 바꾸지 않아요. 잠시 후 다시 확인해 주세요.', tone: 'warn' },
  SKIPPED_NOT_PARTITIONED: { label: '파티션 전환 전', description: '행동 로그 테이블이 수신 시각 기준 월 파티션 구조가 아니라서 정리 대상을 계산하지 않아요.', tone: 'muted' },
  SKIPPED_ROLLBACK_WINDOW: { label: '롤백 대기 기간', description: '파티션 전환 롤백용 복사본이 남아 있어요. 롤백할 수 있도록 복사본을 정리하기 전까지 보관 정리를 멈춰요.', tone: 'warn' },
  SKIPPED_CLOCK_MISMATCH: { label: '서버·DB 날짜 불일치', description: '앱 서버와 DB의 오늘 날짜가 하루를 넘게 달라 기준 월을 정할 수 없어요.', tone: 'warn' },
}
export function retentionStatusView(status: string): RetentionStatusView {
  return retentionStatuses[status] ?? { label: status, tone: 'muted' }
}
// 실행 응답 status(COMPLETED·COMPLETED_WITH_FAILURES) 표시용.
export const retentionResultLabels: Record<string, string> = { COMPLETED: '완료', COMPLETED_WITH_FAILURES: '일부 실패와 함께 완료' }

export const formatCount = (value: number | null | undefined): string => value == null ? '-' : value.toLocaleString('ko-KR')
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024, unit = 0
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++ }
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unit]}`
}
