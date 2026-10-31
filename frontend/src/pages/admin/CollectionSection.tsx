import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { adminKeys, getSync, isIsoDate, listSyncs, parseAdminId, retryableStatuses, retrySync, seoulToday, startSync, syncLimitError, syncSources } from '../../api/admin'
import type { SyncJob, SyncLimits, SyncSource, SyncStart } from '../../api/admin'
import type { AuthUser } from '../../api/auth'
import Button from '../../components/ui/Button'
import AsyncState from '../../components/ui/AsyncState'
import { formatDateTime } from '../../utils/date'
import { useAdminAccess, useAdminAction, useDenyOnForbidden } from './adminAccess'
import { formatCount, sourceLabels, statusLabels, statusTone, syncListRefetchInterval } from './adminView'
import AdminError from './AdminError'
import styles from './Admin.module.css'

function Status({ job }: { job: Pick<SyncJob, 'status'> }) {
  return <span className={styles.statusText} data-tone={statusTone[job.status]}>{statusLabels[job.status]}</span>
}
const time = (value: string | null) => value ? <time dateTime={value}>{formatDateTime(value)}</time> : '-'

// 열기 버튼은 섹션 제목 줄에 있고, 이 패널은 열렸을 때만 마운트된다. 확인 단계와 중복 실행 방지는 그대로다.
function StartSync({ owner, onStarted, onClose }: { owner: AuthUser; onStarted: (result: SyncStart) => void; onClose: () => void }) {
  const [source, setSource] = useState<SyncSource>('YOUTH_POLICY')
  const [pageSize, setPageSize] = useState('')
  const [maxPages, setMaxPages] = useState('')
  const [confirming, setConfirming] = useState<{ source: SyncSource; limits: SyncLimits } | null>(null)
  const { mutation, pending, start } = useAdminAction(owner, ({ source: target, limits }: { source: SyncSource; limits: SyncLimits }) => startSync(target, limits))
  useDenyOnForbidden(mutation.error)
  const limits: SyncLimits = { ...(pageSize ? { pageSize: Number(pageSize) } : {}), ...(maxPages ? { maxPages: Number(maxPages) } : {}) }
  const invalid = syncLimitError(limits)
  const review = (event: FormEvent) => { event.preventDefault(); if (!invalid) setConfirming({ source, limits }) }
  return <section className={styles.actionPanel} aria-labelledby="start-sync-title">
    <h3 id="start-sync-title">수집 시작</h3>
    {confirming ? <div className={styles.confirm} role="group" aria-labelledby="start-sync-confirm">
      <p id="start-sync-confirm"><strong>{sourceLabels[confirming.source]}</strong> 수집을 시작할까요?</p>
      <p className={styles.help}>외부 API를 실제로 호출하고 수집 결과를 저장해요. 같은 출처가 이미 대기·실행 중이면 서버가 거부해요.
        {' '}페이지 크기 {confirming.limits.pageSize ?? '서버 기본값'} · 최대 페이지 {confirming.limits.maxPages ?? '서버 기본값'}</p>
      <div className={styles.buttons}>
        <Button loading={pending} disabled={pending} onClick={() => start(confirming, (result) => { setConfirming(null); onStarted(result) })}>수집 시작 확인</Button>
        <Button variant="ghost" disabled={pending} onClick={() => setConfirming(null)}>취소</Button>
      </div>
    </div> : <form className={styles.form} onSubmit={review}>
      <fieldset className={styles.radioGroup}><legend>출처</legend>
        {syncSources.map((value) => <label key={value}><input type="radio" name="sync-source" value={value} checked={source === value} onChange={() => setSource(value)} />{sourceLabels[value]}</label>)}
      </fieldset>
      <div className={styles.fields}>
        <label>페이지 크기 (선택, 1~100)<input type="number" inputMode="numeric" min={1} max={100} value={pageSize} onChange={(event) => setPageSize(event.target.value)} placeholder="서버 기본값" /></label>
        <label>최대 페이지 수 (선택, 1~1000)<input type="number" inputMode="numeric" min={1} max={1000} value={maxPages} onChange={(event) => setMaxPages(event.target.value)} placeholder="서버 기본값" /></label>
      </div>
      {invalid && <p className={styles.fieldError} role="alert">{invalid}</p>}
      <div className={styles.buttons}><Button type="submit" disabled={!!invalid}>다음: 실행 확인</Button><Button variant="ghost" onClick={onClose}>닫기</Button></div>
    </form>}
    {mutation.isError && <AdminError error={mutation.error} />}
  </section>
}

function SyncDetail({ owner, jobId, onRetried }: { owner: AuthUser; jobId: number; onRetried: (result: SyncStart) => void }) {
  // 대기·실행 중인 작업은 상태가 바뀔 때까지 5초마다 다시 조회한다.
  const detail = useQuery({ queryKey: adminKeys.sync(owner.id, jobId), queryFn: ({ signal }) => getSync(jobId, signal),
    refetchInterval: (query) => query.state.data && ['PENDING', 'RUNNING'].includes(query.state.data.status) ? 5000 : false })
  // 상세에서 작업 종료를 먼저 확인하면 목록 상태도 한 번만 다시 받는다(목록 폴링 주기를 기다리지 않게).
  const client = useQueryClient()
  const status = detail.data?.status
  const previous = useRef(status)
  useEffect(() => {
    const was = previous.current
    previous.current = status
    if ((was === 'PENDING' || was === 'RUNNING') && status && status !== 'PENDING' && status !== 'RUNNING') void client.invalidateQueries({ queryKey: ['admin', owner.id, 'syncs'] })
  }, [client, owner.id, status])
  const [confirming, setConfirming] = useState(false)
  const { mutation, pending, start } = useAdminAction(owner, (id: number) => retrySync(id))
  useDenyOnForbidden(detail.error)
  useDenyOnForbidden(mutation.error)
  if (detail.isPending) return <AsyncState status="loading" title={`수집 #${jobId} 상세를 불러오고 있어요`} />
  if (detail.isError) return <AdminError error={detail.error} onRetry={() => { void detail.refetch() }} />
  const job = detail.data
  const counts: [string, number | null][] = [['전체(출처 제공)', job.totalCount], ['가져옴', job.fetchedCount], ['성공', job.successCount], ['레코드 실패', job.failedRecordCount],
    ['처리 대기', job.pendingCount], ['페이지 실패', job.failedPageCount], ['신규', job.newCount], ['변경', job.updatedCount], ['변경 없음', job.unchangedCount]]
  return <section className={styles.panel} aria-labelledby="sync-detail-title">
    <div className={styles.panelHead}><h3 id="sync-detail-title">수집 #{job.jobId} 상세</h3><Status job={job} /></div>
    <dl className={styles.meta}>
      <div><dt>출처</dt><dd>{sourceLabels[job.source]}</dd></div>
      <div><dt>시작</dt><dd>{time(job.startedAt)}</dd></div>
      <div><dt>완료</dt><dd>{time(job.completedAt)}</dd></div>
    </dl>
    <dl className={styles.counts}>{counts.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{formatCount(value)}</dd></div>)}</dl>
    {job.failureReason && <p className={styles.failure}><strong>실패 사유</strong> {job.failureReason}</p>}
    {job.pageFailures.length > 0 && <div className={styles.tableWrap}><table><caption>페이지 실패 {job.pageFailures.length}건</caption>
      <thead><tr><th scope="col">페이지</th><th scope="col">코드</th><th scope="col">내용</th><th scope="col">재시도 가능</th></tr></thead>
      <tbody>{job.pageFailures.map((item, index) => <tr key={index}><td>{item.pageNumber}</td><td>{item.errorCode}</td><td className={styles.wrap}>{item.errorMessage}</td><td>{item.retryable ? '예' : '아니요'}</td></tr>)}</tbody></table></div>}
    {job.employment24PageFailures.length > 0 && <div className={styles.tableWrap}><table><caption>고용24 조회 조건 실패 {job.employment24PageFailures.length}건</caption>
      <thead><tr><th scope="col">조회 조건</th><th scope="col">코드</th><th scope="col">내용</th><th scope="col">재시도 가능</th></tr></thead>
      <tbody>{job.employment24PageFailures.map((item, index) => <tr key={index}><td className={styles.wrap}><code>{JSON.stringify(item.request)}</code></td><td>{item.errorCode}</td><td className={styles.wrap}>{item.errorMessage}</td><td>{item.retryable ? '예' : '아니요'}</td></tr>)}</tbody></table></div>}
    {job.incompleteNcsCodes.length > 0 && <p className={styles.help}>수집이 끝나지 않은 NCS 코드: {job.incompleteNcsCodes.join(', ')}</p>}
    {job.recordFailures.length > 0 && <div className={styles.tableWrap}><table><caption>레코드 실패 {job.recordFailures.length}건</caption>
      <thead><tr><th scope="col">외부 ID</th><th scope="col">위치</th><th scope="col">결과</th><th scope="col">내용</th><th scope="col">원본</th></tr></thead>
      <tbody>{job.recordFailures.map((item) => <tr key={item.recordResultId}><td>{item.externalId}</td><td>{item.pageNumber ?? '-'} / {item.itemIndex ?? '-'}</td><td>{item.status}{item.resultType ? ` · ${item.resultType}` : ''}</td>
        <td className={styles.wrap}>{item.errorCode} {item.errorMessage}</td><td>{item.rawRecordId ? <Link to={`/admin/raw-records?id=${item.rawRecordId}`}>#{item.rawRecordId}</Link> : '-'}</td></tr>)}</tbody></table></div>}
    {retryableStatuses.includes(job.status) && <div className={styles.actionPanel}>
      {confirming ? <div className={styles.confirm} role="group" aria-labelledby="retry-confirm">
        <p id="retry-confirm">수집 #{job.jobId}을(를) 다시 실행할까요? 같은 출처의 수집이 새로 시작돼요.</p>
        <div className={styles.buttons}>
          <Button loading={pending} disabled={pending} onClick={() => start(job.jobId, (result) => { setConfirming(false); onRetried(result) })}>재시도 확인</Button>
          <Button variant="ghost" disabled={pending} onClick={() => setConfirming(false)}>취소</Button>
        </div>
      </div> : <Button variant="secondary" disabled={pending} onClick={() => { mutation.reset(); setConfirming(true) }}>실패 작업 재시도…</Button>}
      {mutation.isError && <AdminError error={mutation.error} />}
    </div>}
  </section>
}

export default function CollectionSection() {
  const { owner } = useAdminAccess()
  const client = useQueryClient()
  const [params, setParams] = useSearchParams()
  const requested = params.get('date') ?? ''
  const date = isIsoDate(requested) ? requested : seoulToday()
  const selected = parseAdminId(params.get('job'))
  // 대기·실행 중인 작업이 있을 때만 10초마다 다시 조회한다. 날짜가 바뀌거나 화면을 떠나면 이 Query의 관찰이 끝나 폴링도 멈춘다.
  const jobs = useQuery({ queryKey: adminKeys.syncs(owner.id, date), queryFn: ({ signal }) => listSyncs(date, signal), refetchInterval: (query) => syncListRefetchInterval(query.state.data) })
  const [notice, setNotice] = useState<string | null>(null)
  const [starting, setStarting] = useState(false)
  useDenyOnForbidden(jobs.error)
  const show = (next: { date?: string; job?: number | null }) => {
    const value = new URLSearchParams()
    value.set('date', next.date ?? date)
    const job = next.job === undefined ? selected : next.job
    if (job) value.set('job', String(job))
    setParams(value, { replace: true })
  }
  // 시작·재시도는 서버가 새 실행을 만들었다는 202만 확인한다. 오늘 이력을 다시 불러와 새 실행을 선택한다.
  const started = (result: SyncStart) => {
    setNotice(`수집 #${result.jobId}을(를) 시작했어요 (${statusLabels[result.status]}).`)
    setStarting(false)
    const today = seoulToday()
    void client.invalidateQueries({ queryKey: adminKeys.syncs(owner.id, today) })
    show({ date: today, job: result.jobId })
  }
  return <div className={styles.section}>
    {/* 신규 수집 실행은 섹션 단위 작업이라 제목 줄 오른쪽에 두고, 이력 카드의 조회 도구(날짜·새로고침)와 분리한다. */}
    <div className={styles.sectionHeadRow}>
      <div className={styles.sectionHead}><h2>데이터 수집</h2><p className={styles.help}>날짜별 수집 실행 이력을 조회하고, 필요할 때 수집을 시작하거나 실패한 작업을 다시 실행해요.</p></div>
      {!starting && <Button variant="secondary" onClick={() => { setNotice(null); setStarting(true) }}>수집 시작</Button>}
    </div>
    {starting && <StartSync owner={owner} onStarted={started} onClose={() => setStarting(false)} />}
    {notice && <p className={styles.notice} role="status">{notice}</p>}
    <section className={styles.panel} aria-labelledby="sync-list-title">
      <div className={styles.panelHead}>
        <h3 id="sync-list-title">수집 이력</h3>
        <div className={styles.toolbar}>
          <label className={styles.inline}>날짜<input type="date" value={date} onChange={(event) => { if (isIsoDate(event.target.value)) show({ date: event.target.value, job: null }) }} /></label>
          <Button variant="ghost" disabled={jobs.isFetching} onClick={() => { void jobs.refetch() }}>새로고침</Button>
        </div>
      </div>
      {jobs.isPending ? <AsyncState status="loading" title="수집 이력을 불러오고 있어요" />
        : jobs.isError ? <AdminError title="수집 이력을 불러오지 못했어요" error={jobs.error} onRetry={() => { void jobs.refetch() }} />
          : jobs.data.length === 0 ? <p className={styles.empty}>{date}에 실행된 수집이 없어요.</p>
            : <div className={styles.tableWrap}><table>
              <thead><tr><th scope="col">실행</th><th scope="col">출처</th><th scope="col">상태</th><th scope="col">시작</th><th scope="col">완료</th><th scope="col" className={styles.number}>성공</th><th scope="col" className={styles.number}>레코드 실패</th><th scope="col" className={styles.number}>페이지 실패</th></tr></thead>
              <tbody>{jobs.data.map((job) => <tr key={job.jobId} className={job.jobId === selected ? styles.selectedRow : undefined}>
                <td><button type="button" className={styles.linkButton} aria-current={job.jobId === selected ? 'true' : undefined} aria-label={`수집 #${job.jobId} 상세 보기`} onClick={() => show({ job: job.jobId })}>#{job.jobId}</button></td>
                <td>{sourceLabels[job.source]}</td><td><Status job={job} /></td><td>{time(job.startedAt)}</td><td>{time(job.completedAt)}</td>
                <td className={styles.number}>{formatCount(job.successCount)}</td><td className={styles.number}>{formatCount(job.failedRecordCount)}</td><td className={styles.number}>{formatCount(job.failedPageCount)}</td>
              </tr>)}</tbody></table></div>}
    </section>
    {selected && <SyncDetail key={selected} owner={owner} jobId={selected} onRetried={started} />}
  </div>
}
