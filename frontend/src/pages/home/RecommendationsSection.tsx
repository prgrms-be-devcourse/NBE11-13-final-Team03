import { Link } from 'react-router-dom'
import type { AuthStatus } from '../../auth/AuthContext'
import { useQuery } from '@tanstack/react-query'
import { recommendationsQuery } from '../../api/recommendations'
import type { RecommendationItem } from '../../api/recommendations'
import { ApiError } from '../../api/ApiError'
import Card from '../../components/ui/Card'
import AsyncState from '../../components/ui/AsyncState'
import HomeOpportunityCard from './HomeOpportunityCard'
import { homeErrorMessage } from './homeQueries'
import { isValidDate, statusLabels } from '../opportunities/searchParams'
import { regionText } from '../opportunities/detailPresentation'
import styles from './HomePage.module.css'

function RecommendationCard({ item }: { item: RecommendationItem }) {
  const training = item.type === 'VOCATIONAL_TRAINING'
  const date = training ? item.trainingStartDate : item.applicationEndDate
  return <HomeOpportunityCard item={item}>
    <div className={styles.recommendationInfo}>
      <p>모집 상태: {statusLabels[item.applicationStatus]}</p>
      {date && isValidDate(date) && <p>{training ? '교육 시작일' : '신청기간 마감일'} <time dateTime={date}>{date}</time></p>}
      {!training && date && isValidDate(date) && <p className={styles.help}>여러 신청기간이 있을 수 있어요. 전체 일정은 상세에서 확인해 주세요.</p>}
      {training && <p className={styles.help}>교육 시작일은 신청 마감일과 다른 정보예요.</p>}
      {item.regions.length > 0 && <ul className={styles.regions}>{item.regions.map((region, index) => <li key={index}>{regionText(region, training)}</li>)}</ul>}
      <div className={styles.reasons}>
        <p className={styles.reasonTitle}>추천 이유</p>
        <ul className={styles.badges}>{item.recommendationReasons.badges.map((badge, index) => <li key={index}>{badge}</li>)}</ul>
        <p>{item.recommendationReasons.description}</p>
      </div>
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
    content = <Card variant="sage" className={styles.recommendationIntro}>
      <h3>나에게 맞는 기회를 놓치지 마세요</h3>
      <p>내 조건과 관심 분야를 설정하면 적합한 청년정책과 직업훈련을 추천받을 수 있어요.</p>
      <p className={styles.help}>개인화 추천은 로그인과 맞춤 조건 설정이 필요해요.</p>
      <Link className={styles.textLink} to="/login?returnTo=%2F">카카오로 로그인하기 →</Link>
      <Link className={styles.textLink} to="/opportunities">공개 기회 먼저 살펴보기 →</Link>
    </Card>
  } else if (result.isPending) {
    content = <AsyncState status="loading" title="내 조건에 맞는 기회를 불러오는 중이에요" />
  } else if (result.isError) {
    const error = result.error
    if (error instanceof ApiError && error.status === 404 && error.code === 'ELIGIBILITY_001') {
      content = <><AsyncState status="empty" title="맞춤 조건 설정이 필요해요" description="판정 프로필이 등록되어 있지 않아요. 맞춤 조건 설정에서 등록 가능 여부를 확인해 주세요." /><Link className={styles.textLink} to="/onboarding">맞춤 조건 설정하기 →</Link></>
    } else if (error instanceof ApiError && error.status === 401) {
      content = <><AsyncState status="error" title="로그인 상태를 다시 확인해 주세요" description="인증을 확인하지 못했어요. 추천 결과가 없는 상황과는 달라요." /><Link className={styles.textLink} to="/login?returnTo=%2F">카카오 로그인 다시 확인</Link></>
    } else {
      content = <AsyncState status="error" {...homeErrorMessage(error)} onRetry={() => { void result.refetch() }} />
    }
  } else if (result.data.length === 0) {
    content = <AsyncState status="empty" title="아직 보여드릴 추천 결과가 없어요" description="최신 판정이 아직 준비되지 않았을 수도 있어요. 신청 가능한 기회가 없다는 뜻은 아니에요." />
  } else {
    content = <><p className={styles.help}>저장된 최신 판정과 실제 추천 근거를 바탕으로 제공해요.</p>
      {result.isFetching && <p role="status" className={styles.help}>추천 정보를 새로 확인하고 있어요.</p>}
      <ul className={styles.grid}>{result.data.map((item) => <li key={item.opportunityId}><RecommendationCard item={item} /></li>)}</ul>
    </>
  }
  return <section id="recommendations" className={styles.section} aria-labelledby="recommendations-heading">
    <div className={styles.sectionHeader}><div><span className={styles.eyebrow}>내 조건을 바탕으로</span><h2 id="recommendations-heading">개인화 추천</h2></div></div>
    {content}
  </section>
}

export default RecommendationsSection
