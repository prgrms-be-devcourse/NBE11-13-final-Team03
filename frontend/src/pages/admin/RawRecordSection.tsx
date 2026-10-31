import type { FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { adminKeys, getRawRecord, parseAdminId } from '../../api/admin'
import Button from '../../components/ui/Button'
import AsyncState from '../../components/ui/AsyncState'
import { formatDateTime } from '../../utils/date'
import { useAdminAccess, useDenyOnForbidden } from './adminAccess'
import { sourceLabels } from './adminView'
import AdminError from './AdminError'
import styles from './Admin.module.css'

// 원본 레코드 ID로 읽기만 한다. 저장(POST raw-records)은 제공하지 않는다.
export default function RawRecordSection() {
  const { owner } = useAdminAccess()
  const [params, setParams] = useSearchParams()
  const requested = params.get('id')
  const rawId = parseAdminId(requested)
  const result = useQuery({ queryKey: adminKeys.raw(owner.id, rawId ?? 0), queryFn: ({ signal }) => getRawRecord(rawId!, signal), enabled: rawId !== null })
  useDenyOnForbidden(result.error)
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const value = String(new FormData(event.currentTarget).get('rawRecordId') ?? '').trim()
    setParams(value ? { id: value } : {}, { replace: true })
  }
  const time = (value: string | null) => value ? <time dateTime={value}>{formatDateTime(value)}</time> : '-'
  return <div className={styles.section}>
    <div className={styles.sectionHead}><h2>원본 레코드</h2><p className={styles.help}>외부 API에서 받은 원본을 레코드 ID로 조회해요. 수집 상세의 레코드 실패에서 바로 이동할 수도 있어요.</p></div>
    <section className={styles.panel} aria-labelledby="raw-title">
      <h3 id="raw-title" className={styles.srOnly}>원본 레코드 조회</h3>
      <form className={styles.toolbar} role="search" aria-label="원본 레코드 조회" onSubmit={submit}>
        <label className={styles.inline}>레코드 ID<input key={requested} name="rawRecordId" type="number" inputMode="numeric" min={1} defaultValue={requested ?? ''} required /></label>
        <Button type="submit" variant="secondary">조회</Button>
      </form>
      {requested !== null && rawId === null ? <p className={styles.fieldError} role="alert">레코드 ID는 1 이상의 정수예요.</p>
        : rawId === null ? <p className={styles.empty}>조회할 레코드 ID를 입력해 주세요.</p>
          : result.isPending ? <AsyncState status="loading" title={`원본 레코드 #${rawId}를 불러오고 있어요`} />
            : result.isError ? <AdminError title="원본 레코드를 불러오지 못했어요" error={result.error} onRetry={() => { void result.refetch() }} />
              : <>
                <dl className={styles.meta}>
                  <div><dt>레코드 ID</dt><dd>#{result.data.rawRecordId}</dd></div>
                  <div><dt>출처</dt><dd>{sourceLabels[result.data.source]}</dd></div>
                  <div><dt>외부 ID</dt><dd>{result.data.externalId}</dd></div>
                  <div><dt>최초 수집</dt><dd>{time(result.data.collectedAt)}</dd></div>
                  <div><dt>마지막 확인</dt><dd>{time(result.data.lastSeenAt)}</dd></div>
                  <div><dt>내용 해시</dt><dd><code>{result.data.payloadHash}</code></dd></div>
                </dl>
                <h4 className={styles.subTitle}>원본 내용</h4>
                <pre className={styles.payload} tabIndex={0} aria-label="원본 JSON">{JSON.stringify(result.data.payload, null, 2)}</pre>
              </>}
    </section>
  </div>
}
