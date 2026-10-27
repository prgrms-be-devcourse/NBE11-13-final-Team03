import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { profileQuery } from '../../api/profile'
import { ArrowRight, Bookmark, Sprout, GraduationCap, SlidersHorizontal } from 'lucide-react'
import AsyncState from '../../components/ui/AsyncState'
import HomeOpportunityCard from './HomeOpportunityCard'
import RecommendationsSection from './RecommendationsSection'
import { homeErrorMessage, homeLatestQuery } from './homeQueries'
import styles from './HomePage.module.css'

function HomePage() {
  const auth = useAuth()
  const latest = useQuery(homeLatestQuery())
  const authenticated = auth.status === 'authenticated' && !!auth.user
  const profile = useQuery({ ...profileQuery(auth.user?.id ?? 0), enabled: authenticated })
  const profileConfirmed = authenticated && profile.isSuccess && profile.fetchStatus === 'idle'
  return <div className={styles.page}>
    <section className={styles.hero} aria-labelledby="home-title">
      <div className={styles.heroCopy}>
      <h1 id="home-title">나에게 맞는 기회를<br />Y:ON에서 발견하세요.</h1>
      <p className={styles.lead}>청년정책과 직업훈련을 한곳에서 탐색하세요.</p>
      <div className={styles.heroActions}>
        <Link className={styles.primaryLink} to="/opportunities">기회 탐색하기 <ArrowRight size={18} aria-hidden="true" /></Link>
        {profileConfirmed && (profile.data
          ? <a className={styles.secondaryLink} href="#recommendations">맞춤 추천 보기</a>
          : <Link className={styles.secondaryLink} to="/onboarding">맞춤 조건 설정하기</Link>)}
      </div>
      </div>
      <svg className={styles.heroArt} viewBox="0 0 480 360" fill="none" aria-hidden="true" focusable="false">
        <circle cx="255" cy="174" r="150" className={styles.artDisc} />
        <path d="M60 307C145 210 233 302 450 241V360H0Z" className={styles.artHill} />
        <path d="M230 290V130a60 60 0 0 1 120 0v125" stroke="currentColor" strokeWidth="36" />
        <path d="M233 360c-99-84 155-52 62-119" className={styles.artPath} strokeWidth="48" />
        <path d="M389 280V176m0 60c-35-4-47-23-43-44 25 0 44 16 43 44Zm0-27c33-7 42-26 34-47-25 4-38 23-34 47Z" className={styles.artPlant} strokeWidth="3" />
        <circle cx="112" cy="128" r="9" className={styles.artHill} />
        <path d="M157 62v18m-9-9h18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </svg>
    </section>
    <nav className={styles.shortcuts} aria-label="기회 탐색 바로가기">
      <Link to="/opportunities?type=YOUTH_POLICY"><span className={styles.shortcutIcon}><Sprout size={24} aria-hidden="true" /></span><span><strong>청년정책</strong><small>생활에 필요한 지원 찾기</small></span><ArrowRight size={16} aria-hidden="true" /></Link>
      <Link to="/opportunities?type=VOCATIONAL_TRAINING"><span className={styles.shortcutIcon}><GraduationCap size={24} aria-hidden="true" /></span><span><strong>직업훈련</strong><small>새로운 배움의 기회</small></span><ArrowRight size={16} aria-hidden="true" /></Link>
      <Link to="/mypage/interests"><span className={styles.shortcutIcon}><SlidersHorizontal size={24} aria-hidden="true" /></span><span><strong>관심 분야</strong><small>나만의 탐색 기준 설정</small></span><ArrowRight size={16} aria-hidden="true" /></Link>
      <Link to="/saved"><span className={styles.shortcutIcon}><Bookmark size={24} aria-hidden="true" /></span><span><strong>저장한 기회</strong><small>다시 보고 싶은 공고</small></span><ArrowRight size={16} aria-hidden="true" /></Link>
    </nav>
    <RecommendationsSection confirmedUserId={auth.user?.id} authStatus={auth.status} />
    <section className={styles.section} aria-labelledby="latest-heading" aria-busy={latest.isFetching}>
      <div className={styles.sectionHeader}>
        <h2 id="latest-heading">최근 등록된 기회</h2>
        <Link className={styles.textLink} to="/opportunities">전체 기회 보기 <ArrowRight size={16} aria-hidden="true" /></Link>
      </div>
      {latest.isPending ? <AsyncState status="loading" title="새로운 기회를 불러오는 중이에요" />
        : latest.isError ? <AsyncState status="error" {...homeErrorMessage(latest.error)} onRetry={() => { void latest.refetch() }} />
          : latest.data.content.length === 0 ? <AsyncState status="empty" title="아직 등록된 기회가 없어요" description="새로운 공고가 등록되면 이곳에서 확인할 수 있어요." />
            : <>{latest.isFetching && <p role="status" className={styles.help}>최근 등록된 기회를 새로 확인하고 있어요.</p>}
              <ul className={styles.grid}>{latest.data.content.map((item) => <li key={item.opportunityId}><HomeOpportunityCard item={item} disabled={latest.isFetching} /></li>)}</ul></>}
    </section>
  </div>
}

export default HomePage
