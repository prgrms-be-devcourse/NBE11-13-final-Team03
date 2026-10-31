import { useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import { Link } from 'react-router-dom'
import { AuthContext } from '../../auth/AuthContext'
import type { AuthStatus } from '../../auth/AuthContext'
import { authQueryKey, authTransitionKey } from '../../auth/session'
import type { AuthUser } from '../../api/auth'
import { PreferenceRefreshError, runPreferenceChange } from '../../api/preferences'
import Button from '../../components/ui/Button'
import { X } from 'lucide-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { recommendationsQuery } from '../../api/recommendations'
import type { RecommendationItem } from '../../api/recommendations'
import { ApiError } from '../../api/ApiError'
import { recordBehaviorEvent } from '../../api/behaviorEvents'
import { observeImpression } from './recommendationImpression'
import { opportunityDetailQuery } from '../../api/opportunityDetail'
import AsyncState from '../../components/ui/AsyncState'
import HomeOpportunityCard from './HomeOpportunityCard'
import { homeErrorMessage } from './homeQueries'
import { isValidDate, statusLabels } from '../opportunities/searchParams'
import { applicationWindowText, regionText } from '../opportunities/detailPresentation'
import { formatDate, formatDatesInText } from '../../utils/date'
import styles from './HomePage.module.css'

function RecommendationCard({ item, disabled, onHidden }: { item: RecommendationItem; disabled: boolean; onHidden?: () => void }) {
  const training = item.type === 'VOCATIONAL_TRAINING'
  const date = training ? item.trainingStartDate : item.applicationEndDate
  const detailOptions = opportunityDetailQuery(String(item.opportunityId))
  const detail = useQuery({ ...detailOptions, enabled: !training })
  const periods = detail.data?.applicationWindows ?? []
  const alwaysOpenShown = periods.some(period => period.periodType === 'ALWAYS' && period.validationStatus === 'VALID')
  const badges = item.recommendationReasons.badges.filter(badge => !(item.eligibilityStatus === 'ELIGIBLE' && badge === '신청 조건 충족'))
  const sentences = item.recommendationReasons.description.trim().split(/(?<=[.!?;。！？])\s+|\r?\n+/u).filter(Boolean).map(sentence => sentence.replace(/;$/, '.'))
  return <HomeOpportunityCard item={item} source="RECOMMENDATION" allowHide disabled={disabled} recommendation onHidden={onHidden}>
    <div className={styles.recommendationInfo}>
      {training ? date && isValidDate(date) && <p className={styles.facts}>교육 시작일 <time dateTime={date}>{formatDate(date)}</time></p>
        : periods.length > 0 ? periods.map((period, index) => <p className={styles.facts} key={index}>신청기간 {formatDatesInText(applicationWindowText(period))}</p>)
          : <p className={styles.facts}>신청기간 {detail.isPending ? '확인 중' : '시작일 미확인'}{date && isValidDate(date) && <> ~ <time dateTime={date}>{formatDate(date)}</time></>}</p>}
      {/* 상세와 같이 '모집 상태:' 접두어 없이 상태만 보여 주고, 신청기간이 이미 '상시 신청'이면 중복 표시하지 않는다. */}
      {item.applicationStatus !== 'OPEN' && !(item.applicationStatus === 'ALWAYS_OPEN' && alwaysOpenShown) && <p className={styles.help} data-application-status={item.applicationStatus}>{item.applicationStatus === 'UNKNOWN' ? '모집 상태 미확인' : statusLabels[item.applicationStatus]}</p>}
      <div className={styles.reasons}>
        {badges.length > 0 && <ul className={styles.badges} aria-label="추천 이유">{badges.map((badge, index) => <li key={index} data-badge={badge}>{badge}</li>)}</ul>}
        <p className={styles.reasonDescription}>{sentences.map((sentence, index) => <span key={index}>{sentence}</span>)}</p>
      </div>
      {item.regions.length > 0 && <ul className={styles.regions}>{item.regions.map((region, index) => <li key={index}>{regionText(region, training)}</li>)}</ul>}
      {!training && date && isValidDate(date) && <p className={styles.help}>여러 신청기간이 있을 수 있어요.<br />전체 일정은 상세에서 확인해 주세요.</p>}
      {training && <p className={styles.help}>교육 시작일은 신청 마감일과 다른 정보예요.</p>}
    </div>
  </HomeOpportunityCard>
}

// 화면에 렌더링된 추천 카드만 관찰한다. 재렌더링·재조회에도 같은 홈 진입 동안 사용자·공고별 한 번만 보낸다.
function ObservedRecommendation({ item, disabled, userId, recorded, onHidden }: { item: RecommendationItem; disabled: boolean; userId: number; recorded: RefObject<Set<string>>; onHidden: (item: RecommendationItem) => void }) {
  const client = useQueryClient()
  const element = useRef<HTMLLIElement>(null)
  const opportunityId = item.opportunityId
  useEffect(() => {
    const key = `${userId}:${opportunityId}`
    if (!element.current || recorded.current.has(key)) return
    return observeImpression(element.current, () => {
      if (recorded.current.has(key)) return
      recorded.current.add(key)
      void recordBehaviorEvent(client, userId, opportunityId, { eventType: 'IMPRESSION', source: 'RECOMMENDATION' })
    })
  }, [client, userId, opportunityId, recorded])
  return <li ref={element}><RecommendationCard item={item} disabled={disabled} onHidden={() => onHidden(item)} /></li>
}

const undoVisibleMs = 5000
type Hidden = { userId: number; opportunityId: number; title: string; sequence: number }

function undoErrorMessage(error: unknown): string {
  if (error instanceof PreferenceRefreshError) return '숨김은 해제했지만 추천을 새로 불러오지 못했어요. 다시 시도해 주세요.'
  if (error instanceof ApiError && error.status === 401) return '로그인 상태를 다시 확인해 주세요.'
  if (error instanceof ApiError && ['network', 'timeout', 'unknown'].includes(error.kind)) return '되돌리기 완료 여부를 확인하지 못했어요. 다시 시도해 주세요.'
  return '숨김을 되돌리지 못했어요. 다시 시도해 주세요.'
}

/**
 * 숨김 직후 되돌리기. 기존 숨김 해제 API(DELETE /opportunities/{id}/hide)로 서버 상태를 실제로 되돌리고 추천을 다시 받는다.
 * 5초 뒤 안내만 닫으며 서버 숨김은 그대로 둔다. 키보드 포커스·포인터가 안내에 있으면 닫지 않고, 실패하면 닫지 않고 재시도를 둔다.
 */
function HideUndo({ hidden, onUndone, onClose }: { hidden: Hidden; onUndone: () => void; onClose: () => void }) {
  const client = useQueryClient()
  const auth = useContext(AuthContext)
  const [state, setState] = useState<'idle' | 'pending' | 'error'>('idle')
  const [error, setError] = useState<unknown>(null)
  const [paused, setPaused] = useState(false)
  const running = useRef(false)
  useEffect(() => {
    if (state !== 'idle' || paused) return
    const timer = setTimeout(onClose, undoVisibleMs)
    return () => clearTimeout(timer)
  }, [state, paused, onClose])
  const undo = () => {
    const owner = auth?.status === 'authenticated' ? auth.user : undefined
    if (!owner || owner.id !== hidden.userId || running.current) return
    running.current = true
    setState('pending'); setError(null)
    runPreferenceChange(client, owner, hidden.opportunityId, 'unhide', 'RECOMMENDATION')
      .then(onUndone, (reason: unknown) => { setError(reason); setState('error') })
      .finally(() => { running.current = false })
  }
  // 키보드로 머무는 동안(:focus-visible)과 포인터가 올라가 있는 동안에는 자동으로 닫지 않는다.
  return <div className={styles.undo} onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}
    onFocus={(event) => { if (event.target.matches(':focus-visible')) setPaused(true) }} onBlur={() => setPaused(false)}>
    {/* 긴 제목으로 크기가 흔들리지 않도록 화면에는 짧은 문구만 둔다. 어떤 공고인지는 버튼의 접근 가능한 이름으로 알린다. */}
    <p className={styles.undoText}>공고를 숨겼어요.</p>
    <div className={styles.undoActions}>
      <Button variant="ghost" className={styles.undoButton} loading={state === 'pending'} disabled={state === 'pending'} aria-label={`${hidden.title} 숨김 되돌리기`} onClick={undo}>{state === 'error' ? '다시 시도' : '되돌리기'}</Button>
      {/* 평소에는 5초 뒤 저절로 닫힌다. 실패하면 자동으로 닫히지 않으므로 그때만 닫기를 둔다. */}
      {state === 'error' && <Button variant="ghost" className={styles.undoClose} aria-label="숨김 안내 닫기" onClick={onClose}><X size={18} aria-hidden="true" /></Button>}
    </div>
    {state === 'error' && <p className={styles.undoError} role="alert">{undoErrorMessage(error)}</p>}
  </div>
}

function RecommendationsSection({ confirmedUserId, authStatus }: { confirmedUserId?: number; authStatus?: AuthStatus }) {
  const options = recommendationsQuery(authStatus && authStatus !== 'authenticated' ? undefined : confirmedUserId)
  const result = useQuery(options)
  // 홈에 들어올 때마다 새로 만든다. 다른 화면에서 돌아오거나 새로고침하면 다시 노출로 인정한다.
  const recorded = useRef(new Set<string>())
  const client = useQueryClient()
  // 되돌리기 안내는 가장 최근에 숨긴 공고 하나만 가리킨다. 이전 숨김은 서버에 그대로 남는다.
  const [hidden, setHidden] = useState<Hidden | null>(null)
  const [undone, setUndone] = useState(false)
  const sequence = useRef(0)
  const heading = useRef<HTMLHeadingElement>(null)
  // 숨김 응답 시점에도 같은 확인된 사용자일 때만 안내를 띄운다. 전환 중이거나 사용자가 바뀌었으면 띄우지 않는다.
  const onHidden = useCallback((item: RecommendationItem) => {
    const current = client.getQueryData<AuthUser | null>(authQueryKey)
    if (!current || current.id !== confirmedUserId || client.isMutating({ mutationKey: authTransitionKey })) return
    setUndone(false)
    setHidden({ userId: current.id, opportunityId: item.opportunityId, title: item.title || '제목 미확인', sequence: ++sequence.current })
  }, [client, confirmedUserId])
  const closeUndo = useCallback(() => {
    // 안내 안에 포커스가 있었다면 사라지기 전에 추천 제목으로 옮겨 포커스를 잃지 않게 한다.
    if (document.activeElement?.closest(`.${styles.undo}`)) heading.current?.focus()
    setHidden(null)
  }, [])
  // 숨긴 카드의 버튼이 사라져 포커스를 잃었으면 추천 제목으로 옮긴다. 다음 Tab이 바로 되돌리기 버튼이다.
  // 버튼에 직접 포커스를 주면 자동 닫힘이 멈출 수 있어(키보드 포커스로 판단) 제목을 쓴다.
  const hiddenSequence = hidden?.sequence
  useEffect(() => {
    if (hiddenSequence !== undefined && (!document.activeElement || document.activeElement === document.body)) heading.current?.focus()
  }, [hiddenSequence])
  useEffect(() => {
    if (!undone) return
    const timer = setTimeout(() => setUndone(false), 3000)
    return () => clearTimeout(timer)
  }, [undone])
  // 로그아웃·계정 전환·인증 확인 중에는 이전 사용자의 안내를 보이지 않는다.
  const visibleHidden = hidden && authStatus === 'authenticated' && hidden.userId === confirmedUserId ? hidden : null
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
        return item && confirmedUserId !== undefined ? <ObservedRecommendation key={item.opportunityId} item={item} disabled={result.isFetching} userId={confirmedUserId} recorded={recorded} onHidden={onHidden} />
          : <li key={`empty-${index}`} className={styles.vacantSlot} aria-hidden="true" />
      })}</ul>
    </>
  }
  return <section id="recommendations" className={styles.section} aria-labelledby="recommendations-heading">
    {/* 되돌리기 안내는 추천 제목 줄 오른쪽 끝에 둔다. 숨긴 카드는 사라지므로 카드 대신 이 영역이 소유한다.
        안내 영역은 항상 두어 스크린 리더가 새 안내를 읽게 한다. */}
    <div className={styles.sectionHeader}><h2 id="recommendations-heading" ref={heading} tabIndex={-1}>개인화 추천</h2>
      <div className={styles.undoRegion} role="status" aria-live="polite">
        {visibleHidden ? <HideUndo key={visibleHidden.sequence} hidden={visibleHidden} onClose={closeUndo}
          onUndone={() => { closeUndo(); setUndone(true) }} />
          : undone && <p className={styles.undoDone}>숨김을 되돌렸어요.</p>}
      </div>
    </div>
    {content}
  </section>
}

export default RecommendationsSection
