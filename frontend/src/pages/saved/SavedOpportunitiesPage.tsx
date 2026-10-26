import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { loginPath } from '../../auth/returnPath'
import type { AuthUser } from '../../api/auth'
import { ApiError } from '../../api/ApiError'
import { correctedSavedPage, savedOpportunitiesQuery } from '../../api/preferences'
import Button from '../../components/ui/Button'
import Card from '../../components/ui/Card'
import AsyncState from '../../components/ui/AsyncState'
import PreferenceActions from '../../components/opportunities/PreferenceActions'
import OpportunityTypeBadge from '../../components/opportunities/OpportunityTypeBadge'
import LoginRequired from '../../components/auth/LoginRequired'
import { classificationLabel, paginationPages } from '../opportunities/searchParams'
import { formatDateTime } from '../../utils/date'
import styles from './SavedOpportunitiesPage.module.css'

function SavedList({ user }: { user: AuthUser }) {
  const [page, setPage] = useState(0)
  const result = useQuery(savedOpportunitiesQuery(user.id, page))
  const data = result.data
  const corrected = data ? correctedSavedPage(page, data) : page
  // 응답으로 현재 페이지가 없어졌을 때만 보정한다. 다른 페이지/계정의 이전 데이터는 사용하지 않는다.
  if (data && !result.isFetching && !result.isError && corrected !== page) setPage(corrected)
  if (result.isPending || corrected !== page) return <AsyncState status="loading" title="저장한 기회를 불러오고 있어요" />
  if (result.isError) return <><AsyncState status="error" title={result.error instanceof ApiError && result.error.status === 401 ? '로그인이 필요해요' : '저장 목록을 불러오지 못했어요'} description="연결과 로그인 상태를 확인하고 다시 시도해 주세요." onRetry={() => { void result.refetch() }} />{result.error instanceof ApiError && result.error.status === 401 && <Link to={loginPath('/saved')}>다시 로그인하기</Link>}</>
  if (!data) return null
  if (!data.content.length) return <><AsyncState status="empty" title="아직 저장한 기회가 없어요" description="관심 있는 공고를 저장하면 이곳에서 다시 확인할 수 있어요." /><Link to="/opportunities">기회 탐색하기</Link></>
  return <>
    <p>저장한 기회 {data.totalElements.toLocaleString('ko-KR')}개 · 저장한 시각이 최신인 순서예요.</p>
    {result.isFetching && <p role="status">최신 저장 상태를 확인하고 있어요.</p>}
    <ul className={styles.list}>{data.content.map((item) => <li key={item.opportunityId}><Card className={styles.card}>
      <div className={styles.cardMeta}>
        <OpportunityTypeBadge type={item.type} />
        <PreferenceActions opportunityId={item.opportunityId} title={item.title} saved source="SAVED" disabled={result.isFetching} showSaveLabel />
      </div>
      <h2><Link to={`/opportunities/${item.opportunityId}`}>{item.title || '제목 미확인'}</Link></h2>
      {classificationLabel({ ...item, detailCategory: null }) && <p className={styles.muted}>{classificationLabel({ ...item, detailCategory: null })}</p>}
      <p className={styles.muted}>저장 시각 <time dateTime={item.savedAt}>{formatDateTime(item.savedAt)}</time></p>
    </Card></li>)}</ul>
    <nav className={styles.pagination} aria-label="저장 목록 페이지">
      <Button variant="secondary" disabled={page === 0 || result.isFetching} onClick={() => setPage(page - 1)}>이전</Button>
      {paginationPages(data.page, data.totalPages).map((number) => <Button key={number} variant={number === page ? 'primary' : 'ghost'} aria-current={number === page ? 'page' : undefined} aria-label={`${number + 1}페이지`} disabled={result.isFetching} onClick={() => setPage(number)}>{number + 1}</Button>)}
      <Button variant="secondary" disabled={!data.hasNext || result.isFetching} onClick={() => setPage(page + 1)}>다음</Button>
    </nav>
  </>
}
function SavedOpportunitiesPage() {
  const auth = useAuth()
  const location = useLocation()
  return <section className={styles.page} aria-labelledby="saved-title"><h1 id="saved-title">저장한 기회</h1>
    {auth.status === 'checking' ? <AsyncState status="loading" title="로그인 상태를 확인하고 있어요" />
      : auth.status === 'error' ? <AsyncState status="error" title="로그인 상태를 확인하지 못했어요" onRetry={auth.retry} />
        : auth.status !== 'authenticated' || !auth.user ? <LoginRequired description="저장한 기회는 로그인 후 확인할 수 있어요." returnTo={location.pathname} />
          : <SavedList key={auth.user.id} user={auth.user} />}
  </section>
}
export default SavedOpportunitiesPage
