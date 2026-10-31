import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/ApiError'
import { adminErrorKind, adminKeys, executeRetention, getRetentionPreview, isUncertainExecution } from '../../api/admin'
import type { RetentionPreview, RetentionResult } from '../../api/admin'
import type { AuthUser } from '../../api/auth'
import Button from '../../components/ui/Button'
import AsyncState from '../../components/ui/AsyncState'
import { formatDateTime } from '../../utils/date'
import { useAdminAccess, useAdminAction, useDenyOnForbidden } from './adminAccess'
import { formatBytes, formatCount, retentionExecutionLocked, retentionResultLabels, retentionStatusView } from './adminView'
import AdminError from './AdminError'
import styles from './Admin.module.css'

const range = (from: string | null, to: string | null) => `${from ? formatDateTime(from) : '-'} ~ ${to ? formatDateTime(to) : '-'}`
const resultText = (status: string) => retentionResultLabels[status] ? `${retentionResultLabels[status]} (${status})` : status

/**
 * 실행은 미리보기의 planToken을 그대로 보낸다. 서버가 실행 직전 계획을 다시 만들어 다르면 거부한다(RETENTION_003).
 * 확인 체크는 planToken과 실패 횟수마다 새로 받는다(부모가 key로 다시 만든다). 실패 결과는 부모가 보관한다.
 * locked이면(미리보기 재조회 중·실패, 결과 불확실 뒤 최신 미리보기 미확인) 확인·실행을 막는다. 자동 재전송은 하지 않는다.
 */
function ExecuteRetention({ owner, preview, locked, onExecuted, onFailed }: { owner: AuthUser; preview: RetentionPreview; locked: boolean; onExecuted: (result: RetentionResult) => void; onFailed: (error: unknown) => void }) {
  const [acknowledged, setAcknowledged] = useState(false)
  const { pending, start } = useAdminAction(owner, (token: string) => executeRetention(token))
  const rows = preview.toDrop.reduce((sum, item) => sum + item.estimatedRows, 0)
  const bytes = preview.toDrop.reduce((sum, item) => sum + item.totalBytes, 0)
  const nothing = preview.toDrop.length === 0 && preview.toCreate.length === 0
  const blocked = !preview.policy.adminExecuteEnabled ? '서버 설정에서 관리자 보관 정리 실행이 꺼져 있어요. 미리보기만 확인할 수 있어요.'
    : preview.status !== 'READY' || !preview.planToken ? '현재 미리보기 상태에서는 실행할 수 없어요. 상태를 확인한 뒤 미리보기를 다시 불러와 주세요.'
      : nothing ? '삭제하거나 새로 만들 파티션이 없어 실행할 내용이 없어요.' : null
  return <section className={`${styles.panel} ${styles.dangerZone}`} aria-labelledby="retention-execute-title">
    <h3 id="retention-execute-title">보관 정리 실행</h3>
    {blocked ? <p className={styles.help} data-state={preview.policy.adminExecuteEnabled ? undefined : 'disabled'}>{blocked}</p>
        : <div className={styles.confirm}>
          <p>미리보기 기준으로 <strong>파티션 {preview.toDrop.length}개를 삭제</strong>하고 {preview.toCreate.length}개를 새로 만들어요.
            {preview.toDrop.length > 0 && <> 삭제 대상은 예상 {formatCount(rows)}행, {formatBytes(bytes)}이에요.</>}</p>
          {preview.toDrop.length > 0 && <p className={styles.warning}>삭제한 행동 로그는 복구할 수 없어요.</p>}
          {locked && <p className={styles.help} role="status">최신 미리보기를 확인한 뒤에 실행할 수 있어요.</p>}
          <label className={styles.check}><input type="checkbox" checked={acknowledged} disabled={pending || locked} onChange={(event) => setAcknowledged(event.target.checked)} />
            위 미리보기의 삭제·생성 대상을 확인했고, 삭제된 데이터는 복구할 수 없다는 것을 이해했어요.</label>
          <div className={styles.buttons}>
            <Button className={styles.dangerButton} loading={pending} disabled={!acknowledged || pending || locked} onClick={() => start(preview.planToken!, onExecuted, onFailed)}>보관 정리 실행</Button>
          </div>
        </div>}
  </section>
}

type Outcome = { kind: 'uncertain'; error: unknown; baseline: number } | { kind: 'rejected'; error: unknown }

function uncertainCause(error: unknown): string {
  if (error instanceof ApiError && error.kind === 'timeout') return '응답을 기다리는 시간이 지났어요.'
  if (error instanceof ApiError && error.kind === 'network') return '서버 응답을 받지 못했어요.'
  if (error instanceof ApiError && error.status) return `서버 오류(HTTP ${error.status})가 발생했어요.`
  return '서버 응답 형식을 확인할 수 없어요.'
}

export default function RetentionSection() {
  const { owner, deny } = useAdminAccess()
  const client = useQueryClient()
  // 미리보기는 DB를 바꾸지 않는다. 확인 중 토큰이 바뀌지 않도록 포커스 재조회는 하지 않는다.
  const preview = useQuery({ queryKey: adminKeys.retention(owner.id), queryFn: ({ signal }) => getRetentionPreview(signal), refetchOnWindowFocus: false })
  useDenyOnForbidden(preview.error)
  const data = preview.data
  const view = retentionStatusView(data?.status ?? '')
  // 실행 결과·실패는 미리보기가 새 계획으로 바뀌거나 확인 체크를 초기화해도 남도록 여기서 보관한다.
  const [result, setResult] = useState<RetentionResult | null>(null)
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [attempt, setAttempt] = useState(0)
  const refreshPreview = () => { void client.invalidateQueries({ queryKey: adminKeys.retention(owner.id) }).catch(() => {}) }
  // 미리보기 데이터 갱신 횟수. 결과 불확실 뒤에는 이 값이 늘어나야(새 미리보기를 받아야) 다시 실행할 수 있다.
  const dataUpdateCount = client.getQueryState(adminKeys.retention(owner.id))?.dataUpdateCount ?? 0
  const locked = retentionExecutionLocked({ uncertainBaseline: outcome?.kind === 'uncertain' ? outcome.baseline : null, dataUpdateCount, isFetching: preview.isFetching, isError: preview.isError })
  const executed = (value: RetentionResult) => { setOutcome(null); setResult(value); refreshPreview() }
  // 실패하면 확인 체크를 초기화하고, 로그인 문제가 아니면 미리보기를 새로 받는다. 같은 요청은 다시 보내지 않는다.
  const failed = (error: unknown) => {
    setAttempt((value) => value + 1)
    const kind = adminErrorKind(error)
    if (kind === 'denied') { deny(); return }
    setOutcome(isUncertainExecution(error) ? { kind: 'uncertain', error, baseline: client.getQueryState(adminKeys.retention(owner.id))?.dataUpdateCount ?? 0 } : { kind: 'rejected', error })
    if (kind !== 'login') refreshPreview()
  }
  return <div className={styles.section}>
    <div className={styles.sectionHead}><h2>행동 로그 보관</h2><p className={styles.help}>월 파티션 보관 정책과 정리 대상을 미리 확인해요. 미리보기는 데이터를 바꾸지 않아요.</p></div>
    <section className={styles.panel} aria-labelledby="retention-preview-title">
      <div className={styles.panelHead}>
        <h3 id="retention-preview-title">보관 정리 미리보기</h3>
        <Button variant="ghost" disabled={preview.isFetching} onClick={() => { void preview.refetch() }}>다시 불러오기</Button>
      </div>
      {preview.isPending ? <AsyncState status="loading" title="보관 정리 미리보기를 불러오고 있어요" />
        : preview.isError ? <AdminError title="보관 정리 미리보기를 불러오지 못했어요" error={preview.error} onRetry={() => { void preview.refetch() }} />
          : data && <>
            {/* 상태는 따로 강조하고, 원래 API 코드는 보조 정보로 그대로 보여 준다. */}
            <div className={styles.statusBlock} data-tone={view.tone}>
              <p className={styles.statusLabel}>{view.label}</p>
              {view.description && <p className={styles.help}>{view.description}</p>}
              <p className={styles.code}>상태 코드 <code>{data.status}</code></p>
            </div>
            <dl className={styles.infoGrid}>
              <div><dt>기준일</dt><dd>{data.baseDate ?? '-'}</dd></div>
              <div><dt>보관 시작일</dt><dd>{data.keepFrom ?? '-'}</dd></div>
              <div><dt>보관 기간</dt><dd>{data.policy.retainedMonths}개월 (이번 달 포함)</dd></div>
              <div><dt>미리 만들 파티션</dt><dd>{data.policy.futureMonths}개월</dd></div>
              <div><dt>자동 정리</dt><dd>{data.policy.schedulerEnabled ? (data.policy.schedulerDryRun ? '켜짐 · 점검 모드' : '켜짐') : '꺼짐'}</dd></div>
              <div><dt>정리 일정</dt><dd><code>{data.policy.schedule}</code></dd></div>
              <div><dt>관리자 실행</dt><dd><span className={styles.statusText} data-tone={data.policy.adminExecuteEnabled ? 'ok' : 'muted'}>{data.policy.adminExecuteEnabled ? '허용' : '서버에서 꺼짐'}</span></dd></div>
            </dl>
            {data.defaultPartitionHasRows && <p className={styles.warning}>기본 파티션에 행이 있어요. 월 파티션 범위를 벗어난 데이터가 있는지 확인해 주세요.</p>}
            {/* 건너뜀 상태에서는 서버가 대상을 계산하지 않아 빈 목록을 준다. '대상 없음'과 구분해 안내한다. */}
            <h4 className={styles.subTitle}>삭제 대상 {data.toDrop.length}개</h4>
            {data.toDrop.length === 0 ? <p className={styles.empty}>{data.status === 'READY' ? '보관 기간을 지난 파티션이 없어 삭제할 대상이 없어요.' : '현재 상태에서는 삭제 대상을 계산하지 않아요.'}</p>
              : <div className={styles.tableWrap}><table aria-label="삭제 대상 파티션">
                <thead><tr><th scope="col">파티션</th><th scope="col">범위</th><th scope="col" className={styles.number}>예상 행 수</th><th scope="col" className={styles.number}>크기</th></tr></thead>
                <tbody>{data.toDrop.map((item) => <tr key={item.name}><td><code>{item.name}</code></td><td>{range(item.from, item.to)}</td><td className={styles.number}>{formatCount(item.estimatedRows)}</td><td className={styles.number}>{formatBytes(item.totalBytes)}</td></tr>)}</tbody></table></div>}
            <h4 className={styles.subTitle}>생성 대상 {data.toCreate.length}개</h4>
            {data.toCreate.length === 0 ? <p className={styles.empty}>{data.status === 'READY' ? '미리 만들어 둘 파티션이 모두 있어 새로 만들 대상이 없어요.' : '현재 상태에서는 생성 대상을 계산하지 않아요.'}</p>
              : <div className={styles.tableWrap}><table aria-label="생성 대상 파티션">
                <thead><tr><th scope="col">파티션</th><th scope="col">범위</th><th scope="col">충돌</th></tr></thead>
                <tbody>{data.toCreate.map((item) => <tr key={item.name}><td><code>{item.name}</code></td><td>{range(item.from, item.to)}</td><td>{item.conflict ? <span className={styles.statusText} data-tone="warn">있음</span> : '없음'}</td></tr>)}</tbody></table></div>}
            {data.ignored.length > 0 && <p className={styles.help}>정책 범위를 판단할 수 없어 제외한 파티션: {data.ignored.map((item) => `${item.name}${item.bound ? ` (${item.bound})` : ''}`).join(', ')}</p>}
          </>}
    </section>
    {result && <section className={styles.panel} aria-labelledby="retention-result-title" role="status">
      <h3 id="retention-result-title">최근 실행 결과</h3>
      <p><strong>{resultText(result.status)}</strong>{result.executedAt && <> · <time dateTime={result.executedAt}>{formatDateTime(result.executedAt)}</time></>}</p>
      <dl className={styles.meta}>
        <div><dt>삭제한 파티션</dt><dd>{result.dropped.join(', ') || '없음'}</dd></div>
        <div><dt>만든 파티션</dt><dd>{result.created.join(', ') || '없음'}</dd></div>
        <div><dt>실패</dt><dd>{result.failed.join(', ') || '없음'}</dd></div>
        <div><dt>제외</dt><dd>{result.ignored.join(', ') || '없음'}</dd></div>
      </dl>
    </section>}
    {outcome?.kind === 'uncertain' && <section className={`${styles.panel} ${styles.uncertain}`} role="alert" aria-labelledby="retention-uncertain-title">
      <h3 id="retention-uncertain-title">실행 결과를 확인하지 못했어요</h3>
      <p>{uncertainCause(outcome.error)} 파티션은 하나씩 따로 반영되므로 <strong>일부 정리가 이미 진행됐을 수 있어요.</strong> 실패로 단정하지 말고 아래 순서로 확인해 주세요.</p>
      <ol className={styles.steps}>
        <li>미리보기를 자동으로 다시 불러와요. 삭제·생성 대상이 줄었는지 확인해 주세요.</li>
        <li>이전 실행이 아직 끝나지 않았다면 서버가 새 실행을 거부해요. 잠시 뒤 미리보기를 다시 확인해 주세요.</li>
        <li>결과가 계속 분명하지 않으면 서버 로그의 <code>BEHAVIOR_RETENTION_EXECUTE</code> 기록과 DB의 파티션 목록을 확인해 주세요.</li>
      </ol>
      <p className={styles.help}>{locked ? '최신 미리보기를 확인하기 전까지는 다시 실행할 수 없어요.' : '최신 미리보기를 확인했어요. 대상을 다시 검토한 뒤에만 실행해 주세요.'}</p>
    </section>}
    {outcome?.kind === 'rejected' && <AdminError error={outcome.error} />}
    {data && <ExecuteRetention key={`${data.planToken ?? data.status}:${attempt}`} owner={owner} preview={data} locked={locked} onExecuted={executed} onFailed={failed} />}
  </div>
}
