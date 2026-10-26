import { Link } from 'react-router-dom'
import type { AuthStatus } from '../../auth/AuthContext'
import { useQuery } from '@tanstack/react-query'
import { recommendationsQuery } from '../../api/recommendations'
import type { RecommendationItem } from '../../api/recommendations'
import { ApiError } from '../../api/ApiError'
import { opportunityDetailQuery } from '../../api/opportunityDetail'
import AsyncState from '../../components/ui/AsyncState'
import HomeOpportunityCard from './HomeOpportunityCard'
import { homeErrorMessage } from './homeQueries'
import { isValidDate, statusLabels } from '../opportunities/searchParams'
import { applicationWindowText, regionText } from '../opportunities/detailPresentation'
import styles from './HomePage.module.css'

function RecommendationCard({ item, disabled }: { item: RecommendationItem; disabled: boolean }) {
  const training = item.type === 'VOCATIONAL_TRAINING'
  const date = training ? item.trainingStartDate : item.applicationEndDate
  const detailOptions = opportunityDetailQuery(String(item.opportunityId))
  const detail = useQuery({ ...detailOptions, enabled: !training })
  const periods = detail.data?.applicationWindows ?? []
  const formatDate = (value: string) => value.replace(/-/g, '.')
  const badges = item.recommendationReasons.badges.filter(badge => !(item.eligibilityStatus === 'ELIGIBLE' && badge === '신청 조건 충족'))
  const sentences = item.recommendationReasons.description.trim().split(/(?<=[.!?;。！？])\s+|\r?\n+/u).filter(Boolean).map(sentence => sentence.replace(/;$/, '.'))
  return <HomeOpportunityCard item={item} source="RECOMMENDATION" allowHide disabled={disabled} recommendation>
    <div className={styles.recommendationInfo}>
      {training ? date && isValidDate(date) && <p className={styles.facts}>교육 시작일 <time dateTime={date}>{formatDate(date)}</time></p>
        : periods.length > 0 ? periods.map((period, index) => <p className={styles.facts} key={index}>신청기간 {applicationWindowText(period).replace(/\b(\d{4})-(\d{2})-(\d{2})\b/g, '$1.$2.$3')}</p>)
          : <p className={styles.facts}>신청기간 {detail.isPending ? '확인 중' : '시작일 미확인'}{date && isValidDate(date) && <> ~ <time dateTime={date}>{formatDate(date)}</time></>}</p>}
      <div className={styles.reasons}>
        {badges.length > 0 && <ul className={styles.badges} aria-label="추천 이유">{badges.map((badge, index) => <li key={index} data-badge={badge}>{badge}</li>)}</ul>}
        <p className={styles.reasonDescription}>{sentences.map((sentence, index) => <span key={index}>{sentence}</span>)}</p>
      </div>
      {item.applicationStatus !== 'OPEN' && <p data-application-status={item.applicationStatus}>모집 상태: {statusLabels[item.applicationStatus]}</p>}
      {item.regions.length > 0 && <ul className={styles.regions}>{item.regions.map((region, index) => <li key={index}>{regionText(region, training)}</li>)}</ul>}
      {!training && date && isValidDate(date) && <p className={styles.help}>여러 신청기간이 있을 수 있어요.<br />전체 일정은 상세에서 확인해 주세요.</p>}
      {training && <p className={styles.help}>교육 시작일은 신청 마감일과 다른 정보예요.</p>}
    </div>
  </HomeOpportunityCard>
}

function RecommendationsSection({ confirmedUserId, authStatus }: { confirmedUserId?: number; authStatus?: AuthStatus }) {
  const options = recommendationsQuery(authStatus && authStatus !== 'authenticated' ? undefined : confirmedUserId)
  const result = useQuery(options)
  let content
  if (authStatus === 'checking') {
    content = <AsyncState status="loading" title="로그인 상태를 확인하고 있어요" />
  } else if (authStatus === 'error') {
    content = <AsyncState status="error" title="인증 상태를 확인하지 못했어요" description="통신 오류일 수 있어요. 헤더의 ‘다시 확인’으로 로그인 상태를 확인해 주세요." />
  } else if (!options.enabled) {
    content = <div className={styles.recommendationIntro}>
      <p>나에게 맞는 기회를 추천받으세요</p>
      <Link className={styles.primaryLink} to="/login?returnTo=%2F">카카오로 로그인하기</Link>
    </div>
  } else if (result.isPending) {
    content = <AsyncState status="loading" title="내 조건에 맞는 기회를 불러오는 중이에요" />
  } else if (result.isError) {
    const error = result.error
    if (error instanceof ApiError && error.status === 404 && error.code === 'ELIGIBILITY_001') {
      content = <div className={styles.recommendationIntro}><p>맞춤 조건을 설정해 주세요</p><Link className={styles.primaryLink} to="/onboarding">맞춤 조건 설정하기</Link></div>
    } else if (error instanceof ApiError && error.status === 401) {
      content = <><AsyncState status="error" title="로그인 상태를 다시 확인해 주세요" description="인증을 확인하지 못했어요. 추천 결과가 없는 상황과는 달라요." /><Link className={styles.textLink} to="/login?returnTo=%2F">카카오 로그인 다시 확인</Link></>
    } else {
      content = <AsyncState status="error" {...homeErrorMessage(error)} onRetry={() => { void result.refetch() }} />
    }
  } else {
    content = <>
      {result.data.length === 0 && <AsyncState status="empty" title="아직 보여드릴 추천 결과가 없어요" description="최신 판정이 아직 준비되지 않았을 수도 있어요. 신청 가능한 기회가 없다는 뜻은 아니에요." />}
      {result.isFetching && <p role="status" className={styles.help}>추천 정보를 새로 확인하고 있어요.</p>}
      <ul className={`${styles.grid} ${styles.recommendationGrid}`}>{Array.from({ length: 3 }, (_, index) => {
        const item = result.data[index]
        return item ? <li key={item.opportunityId}><RecommendationCard item={item} disabled={result.isFetching} /></li>
          : <li key={`empty-${index}`} className={styles.vacantSlot} aria-hidden="true" />
      })}</ul>
    </>
  }
  return <section id="recommendations" className={styles.section} aria-labelledby="recommendations-heading">
    <div className={styles.sectionHeader}><h2 id="recommendations-heading">개인화 추천</h2></div>
    {content}
  </section>
}

export default RecommendationsSection
