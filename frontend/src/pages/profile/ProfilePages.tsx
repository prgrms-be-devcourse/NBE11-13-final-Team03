import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { loginPath } from '../../auth/returnPath'
import { CircleCheck, CircleDashed, LogOut, SlidersHorizontal, Tags, UserRound } from 'lucide-react'
import { useAuth } from '../../auth/AuthContext'
import type { AuthUser } from '../../api/auth'
import { ApiError } from '../../api/ApiError'
import { interestsQuery, profileQuery } from '../../api/profile'
import AsyncState from '../../components/ui/AsyncState'
import Button from '../../components/ui/Button'
import ArrowLink from '../../components/ui/ArrowLink'
import ProfileGate from './ProfileGate'
import ProfileError from './ProfileError'
import ProfileForm from './ProfileForm'
import InterestsForm from './InterestsForm'
import { useRegions } from './useRegions'
import type { RegionSource } from './regionSelection'
import styles from './Profile.module.css'

function MyPageContent({ user }: { user: AuthUser }) {
  const profile = useQuery(profileQuery(user.id))
  const interests = useQuery(interestsQuery(user.id))
  const auth = useAuth()
  const [confirming, setConfirming] = useState(false)
  const nickname = user.nickname?.trim()
  const email = user.email?.trim()
  const status = (done: boolean, label: string) => <span className={styles.status} data-state={done ? 'done' : 'todo'}>
    {done ? <CircleCheck size={14} aria-hidden="true" /> : <CircleDashed size={14} aria-hidden="true" />}{label}
  </span>
  return <>
    <section className={styles.summary} aria-labelledby="account-summary-title">
      <span className={styles.avatar} aria-hidden="true"><UserRound size={24} /></span>
      <div className={styles.summaryText}>
        <h2 id="account-summary-title">{nickname ? `${nickname}님` : '닉네임 미제공'}</h2>
        <p className={styles.help}>{email || '이메일 미제공'}</p>
      </div>
      {user.role === 'ADMIN' && <span className={styles.status} data-state="done">관리자</span>}
    </section>
    <div className={styles.manageGrid}>
      <section className={styles.manageCard} data-tone="profile" aria-labelledby="profile-title">
        <div className={styles.cardHead}><span className={styles.cardIcon} aria-hidden="true"><SlidersHorizontal size={18} /></span><h2 id="profile-title">맞춤 조건</h2>
          {profile.isSuccess && status(!!profile.data, profile.data ? '등록됨' : '미등록')}</div>
        {profile.isPending ? <AsyncState status="loading" /> : profile.isError ? <ProfileError error={profile.error} onRetry={() => { void profile.refetch() }} /> : <>
          <div className={styles.lines}><p className={styles.state}>{profile.data ? '맞춤 조건이 등록되어 있어요.' : '맞춤 조건이 아직 등록되지 않았어요.'}</p><p className={styles.note}>추천과 신청 가능성 판정에 사용돼요.</p></div>
          <ArrowLink className={styles.cta} to={profile.data ? '/mypage/profile' : '/onboarding'}>{profile.data ? '맞춤 조건 수정' : '맞춤 조건 설정 시작'}</ArrowLink>
        </>}
      </section>
      <section className={styles.manageCard} data-tone="interests" aria-labelledby="interests-title">
        <div className={styles.cardHead}><span className={styles.cardIcon} aria-hidden="true"><Tags size={18} /></span><h2 id="interests-title">관심 분야</h2>
          {interests.isSuccess && status(interests.data.length > 0, interests.data.length ? `${interests.data.length}개` : '선택 사항')}</div>
        {interests.isPending ? <AsyncState status="loading" /> : interests.isError ? <ProfileError error={interests.error} onRetry={() => { void interests.refetch() }} /> : <>
          {/* 관심 분야가 없으면 추천은 분야 제한 없이 동작한다(RecommendationInterestMatcher). */}
          <div className={styles.lines}>{interests.data.length ? <><p className={styles.state}>관심 분야 {interests.data.length}개를 설정했어요.</p><p className={styles.note}>기회 추천에 반영돼요.</p></>
            : <><p className={styles.state}>관심 분야가 설정되지 않았어요.</p><p className={styles.note}>선택 사항이에요. 설정하지 않으면 분야 제한 없이 추천해요.</p></>}</div>
          <ArrowLink className={styles.cta} to="/mypage/interests">{interests.data.length ? '관심 분야 수정' : '관심 분야 설정'}</ArrowLink>
        </>}
      </section>
    </div>
    <section className={styles.accountPanel} aria-labelledby="account-title">
      <div><h2 id="account-title">계정 관리</h2><p className={styles.help}>계정 정보는 카카오에서 제공된 정보를 조회해요.</p></div>
      <Button className={`${styles.logout} ${styles.accountButton}`} variant="secondary" loading={auth.logoutPending} disabled={auth.logoutAllPending || auth.withdrawPending} onClick={() => { void auth.logout().catch(() => {}) }}><LogOut size={16} aria-hidden="true" />로그아웃</Button>
      {auth.logoutError && <div className={styles.accountError}><ProfileError error={auth.logoutError} onRetry={auth.retry} /></div>}
      <LogoutAllArea />
      <div className={styles.withdrawArea}>
        <button type="button" className={styles.withdrawLink} aria-expanded={confirming} aria-controls="withdraw-confirm" onClick={() => setConfirming(!confirming)}>회원 탈퇴</button>
        {confirming && <div id="withdraw-confirm" className={styles.withdrawConfirm} role="group" aria-labelledby="withdraw-title">
          <p id="withdraw-title" className={styles.withdrawTitle}>정말 탈퇴할까요?</p>
          <div className={styles.lines}>
            <p>회원 정보와 맞춤 조건, 관심 분야, 저장·숨김 기록이 삭제돼요.</p>
            <p>삭제된 정보는 되돌릴 수 없어요.</p>
            <p className={styles.help}>알림 데이터는 순차적으로 삭제돼요.</p>
          </div>
          <div className={styles.actions}>
            <Button className={styles.withdrawButton} variant="secondary" loading={auth.withdrawPending} disabled={!auth.withdraw || auth.logoutPending || auth.logoutAllPending} onClick={() => { void auth.withdraw?.().catch(() => {}) }}>탈퇴하기</Button>
            <Button variant="ghost" onClick={() => setConfirming(false)}>취소</Button>
          </div>
        </div>}
        {auth.withdrawError && <p className={styles.error} role="alert">{withdrawErrorMessage(auth.withdrawError)}</p>}
      </div>
    </section>
  </>
}
function logoutAllErrorMessage(error: Error): string {
  if (error instanceof ApiError) {
    if (error.kind === 'configuration') return '다른 로그인 처리가 진행 중이에요. 완료된 뒤 다시 시도해 주세요.'
    if (error.status === 401) return '로그인 상태를 확인하지 못했어요. 다시 로그인한 뒤 시도해 주세요.'
    if (error.status === 403 || error.kind === 'csrf') return '요청이 거부됐어요. 인증·보안 토큰 상태를 확인해 주세요.'
    // 응답을 받지 못하면 서버에서 이미 모든 세션이 끝났을 수 있다. 실패로 단정하지 않고, Provider가 로그인 상태를 다시 확인한다.
    if (['network', 'timeout', 'unknown'].includes(error.kind)) return '모든 기기 로그아웃 완료 여부를 확인하지 못했어요. 서버에서 이미 처리됐을 수 있어 로그인 상태를 다시 확인했어요. 아직 로그인되어 있다면 다시 시도해 주세요.'
  }
  return '모든 기기 로그아웃을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
}
/**
 * 계정 관리의 모든 기기 로그아웃. 회원 탈퇴와 같은 인라인 확인 방식이며 붉은색은 쓰지 않는다.
 * 확인을 열면 열기 버튼을 감추고 확인 영역으로 포커스를 옮기며, 취소하면 열기 버튼으로 돌려준다.
 */
function LogoutAllArea() {
  const auth = useAuth()
  const [confirming, setConfirming] = useState(false)
  const opener = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const returnFocus = useRef(false)
  useEffect(() => {
    if (confirming) panel.current?.focus()
    else if (returnFocus.current) { returnFocus.current = false; opener.current?.focus() }
  }, [confirming])
  const busy = !!(auth.logoutPending || auth.withdrawPending || auth.logoutAllPending)
  const cancel = () => { returnFocus.current = true; setConfirming(false) }
  return <div className={styles.logoutAllArea}>
    <div className={styles.logoutAllText}><h3 id="logout-all-title">모든 기기 로그아웃</h3><p className={styles.help}>다른 기기의 로그인도 종료할 수 있어요.</p></div>
    {!confirming && <Button ref={opener} variant="secondary" className={styles.accountButton} disabled={busy || !auth.logoutAll} aria-controls="logout-all-confirm" aria-expanded={false} onClick={() => setConfirming(true)}>모든 기기 로그아웃</Button>}
    {confirming && <div id="logout-all-confirm" ref={panel} tabIndex={-1} className={styles.logoutAllConfirm} role="group" aria-labelledby="logout-all-confirm-title"
      onKeyDown={(event) => { if (event.key === 'Escape' && !auth.logoutAllPending) cancel() }}>
      <p id="logout-all-confirm-title" className={styles.confirmTitle}>모든 기기에서 로그아웃할까요?</p>
      <div className={styles.lines}>
        <p>현재 기기를 포함해 다른 기기의 로그인 세션도 종료돼요.</p>
        <p className={styles.help}>다른 기기는 이미 받은 로그인이 만료될 때까지 잠시 유지될 수 있고, 그 뒤에는 다시 로그인해야 해요.</p>
      </div>
      <div className={styles.actions}>
        <Button variant="secondary" loading={auth.logoutAllPending} disabled={busy} onClick={() => { void auth.logoutAll?.().catch(() => {}) }}>모든 기기 로그아웃</Button>
        <Button variant="ghost" disabled={auth.logoutAllPending} onClick={cancel}>취소</Button>
      </div>
    </div>}
    {auth.logoutAllError && <p className={styles.error} role="alert">{logoutAllErrorMessage(auth.logoutAllError)}</p>}
  </div>
}
function withdrawErrorMessage(error: Error): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인 상태를 확인하지 못했어요. 다시 로그인한 뒤 탈퇴해 주세요.'
    if (error.status === 404) return '사용자 정보를 찾을 수 없어요. 로그인 상태를 다시 확인해 주세요.'
    if (error.status === 403 || error.kind === 'csrf') return '요청이 거부됐어요. 인증·보안 토큰 상태를 확인해 주세요.'
    // 응답을 받지 못한 경우 서버 처리 여부를 확정하지 않는다. 인증 상태는 Provider가 다시 확인한다.
    if (['network', 'timeout', 'unknown'].includes(error.kind)) return '탈퇴 완료 여부를 확인하지 못했어요. 로그인 상태를 확인한 뒤 다시 시도해 주세요.'
  }
  return '탈퇴를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
}
export function MyPage() {
  const auth = useAuth()
  // 저장 목록·알림처럼 로그인 여부와 관계없이 같은 제목과 폭을 쓴다.
  return <section className={styles.page} aria-labelledby="mypage-title"><h1 id="mypage-title">마이페이지</h1>
    {auth.withdrawn ? <><AsyncState status="empty" title="회원 탈퇴가 완료됐어요" description="그동안 Y:ON을 이용해 주셔서 감사합니다." /><ArrowLink to="/">홈으로 이동</ArrowLink></>
      : auth.loggedOutAll ? <><AsyncState status="empty" title="모든 기기에서 로그아웃했어요" description="다른 기기는 이미 받은 로그인이 만료될 때까지 잠시 유지될 수 있고, 그 뒤에는 다시 로그인해야 해요." />
        <div className={styles.actions}><Link className={styles.loginAgain} to={loginPath('/mypage')}>다시 로그인하기</Link><ArrowLink to="/">홈으로 이동</ArrowLink></div></>
        : <>
          {/* 결과가 불확실한 채로 로그아웃 상태가 확인되면 계정 관리 카드가 사라지므로 안내를 여기서 이어 보여 준다. */}
          {auth.logoutAllError && auth.status !== 'authenticated' && <p className={styles.error} role="alert">{logoutAllErrorMessage(auth.logoutAllError)}</p>}
          <ProfileGate>{(user) => <MyPageContent key={user.id} user={user} />}</ProfileGate>
        </>}
  </section>
}

function SettingsContent({ user, kind, regions }: { user: AuthUser; kind: 'profile' | 'interests'; regions?: RegionSource }) {
  const regionSource = useRegions(kind === 'profile' && regions === undefined)
  const profile = useQuery({ ...profileQuery(user.id), enabled: kind === 'profile' })
  const interests = useQuery({ ...interestsQuery(user.id), enabled: kind === 'interests' })
  return <div className={styles.page}><ArrowLink direction="back" to="/mypage" aria-label="마이페이지로 돌아가기">마이페이지</ArrowLink><h1>{kind === 'profile' ? '맞춤 조건 설정' : '관심 분야 설정'}</h1><section className={styles.panel}>
    {kind === 'profile' ? profile.isPending ? <AsyncState status="loading" /> : profile.isError ? <ProfileError error={profile.error} onRetry={() => { void profile.refetch() }} /> : <ProfileForm user={user} initial={profile.data} regions={regions ?? regionSource} />
      : interests.isPending ? <AsyncState status="loading" /> : interests.isError ? <ProfileError error={interests.error} onRetry={() => { void interests.refetch() }} /> : <InterestsForm user={user} initial={interests.data} />}
  </section></div>
}
export function ProfileSettingsPage({ kind, regions }: { kind: 'profile' | 'interests'; regions?: RegionSource }) { return <ProfileGate>{(user) => <SettingsContent key={`${user.id}-${kind}`} user={user} kind={kind} regions={regions} />}</ProfileGate> }

function OnboardingContent({ user, regions }: { user: AuthUser; regions?: RegionSource }) {
  const regionSource = useRegions(regions === undefined)
  const profile = useQuery(profileQuery(user.id))
  const interests = useQuery(interestsQuery(user.id))
  const [step, setStep] = useState<'profile' | 'interests' | 'done'>('profile')
  const currentStep = profile.data ? step : 'profile'
  return <div className={styles.page}>
    {/* 뒤로가기는 왼쪽, 다른 설정으로 가는 링크는 같은 줄 오른쪽 끝에 둔다. */}
    <div className={styles.topLinks}><ArrowLink direction="back" to="/mypage" aria-label="마이페이지로 돌아가기">마이페이지</ArrowLink>
      <nav aria-label="설정 메뉴"><ArrowLink to="/mypage/interests">관심 분야만 먼저 설정</ArrowLink></nav></div>
    <h1>나에게 맞는 기회 준비하기</h1><p className={styles.help}>맞춤 조건은 추천에 필요하고, 관심 분야는 선택 사항이에요. 각 단계에서 저장한 정보는 중간에 나가도 유지돼요.</p>
    <section className={styles.panel}>
      {profile.isPending ? <AsyncState status="loading" /> : profile.isError ? <ProfileError error={profile.error} onRetry={() => { void profile.refetch() }} />
        : currentStep === 'profile' ? <><h2>1. 맞춤 조건</h2>{profile.data ? <p>이미 등록된 맞춤 조건이 있어요. 확인·수정하거나 다음 단계로 이동할 수 있어요.</p> : <p>필수 조건을 등록해 주세요. 거주지역은 마지막 하위 지역까지 선택해 주세요.</p>}<ProfileForm user={user} initial={profile.data} regions={regions ?? regionSource} onSaved={() => setStep('interests')} onContinue={profile.data ? () => setStep('interests') : undefined} /></>
          : currentStep === 'interests' ? <><h2>2. 관심 분야 (선택)</h2>{interests.isPending ? <><AsyncState status="loading" /><Button variant="secondary" onClick={() => setStep('profile')}>이전 단계로 돌아가기</Button></> : interests.isError ? <><ProfileError error={interests.error} onRetry={() => { void interests.refetch() }} /><Button variant="ghost" onClick={() => setStep('done')}>관심 분야를 변경하지 않고 완료</Button><Button variant="secondary" onClick={() => setStep('profile')}>이전 단계로 돌아가기</Button></> : <InterestsForm user={user} initial={interests.data} onSaved={() => setStep('done')} onSkip={() => setStep('done')} onBack={() => setStep('profile')} />}</>
            : <><h2>설정을 완료했어요</h2><p>저장된 조건에 따른 추천을 홈에서 확인할 수 있어요. 판정 반영에는 시간이 걸릴 수 있어요.</p><div className={styles.actions}><ArrowLink to="/">홈으로 이동</ArrowLink><ArrowLink to="/mypage">내 설정 확인</ArrowLink></div></>}
    </section>
  </div>
}
export function OnboardingPage({ regions }: { regions?: RegionSource } = {}) { return <ProfileGate>{(user) => <OnboardingContent key={user.id} user={user} regions={regions} />}</ProfileGate> }
