import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import type { AuthUser } from '../../api/auth'
import { interestsQuery, profileQuery } from '../../api/profile'
import AsyncState from '../../components/ui/AsyncState'
import Button from '../../components/ui/Button'
import ProfileGate from './ProfileGate'
import ProfileError from './ProfileError'
import ProfileForm from './ProfileForm'
import InterestsForm from './InterestsForm'
import { unavailableRegions } from './regionSelection'
import type { RegionSource } from './regionSelection'
import styles from './Profile.module.css'

function MyPageContent({ user }: { user: AuthUser }) {
  const profile = useQuery(profileQuery(user.id))
  const interests = useQuery(interestsQuery(user.id))
  const auth = useAuth()
  return <div className={styles.page}><h1>마이페이지</h1><section className={styles.panel} aria-labelledby="account-title"><h2 id="account-title">계정 정보</h2>
    <dl className={styles.info}><dt>닉네임</dt><dd>{user.nickname?.trim() || '미제공'}</dd><dt>이메일</dt><dd>{user.email || '미제공'}</dd><dt>계정 유형</dt><dd>{user.role === 'ADMIN' ? '관리자' : '일반 회원'}</dd></dl>
    <p className={styles.help}>계정 정보는 카카오에서 제공된 정보를 조회해요.</p>
    <div className={styles.actions}><Button variant="secondary" loading={auth.logoutPending} onClick={() => { void auth.logout().catch(() => {}) }}>로그아웃</Button></div>
    {auth.logoutError && <ProfileError error={auth.logoutError} onRetry={auth.retry} />}
  </section>
    <section className={styles.panel} aria-labelledby="profile-title"><h2 id="profile-title">맞춤 조건</h2>
      {profile.isPending ? <AsyncState status="loading" /> : profile.isError ? <ProfileError error={profile.error} onRetry={() => { void profile.refetch() }} /> : <><p>{profile.data ? '맞춤 조건이 등록되어 있어요.' : '맞춤 조건이 아직 등록되지 않았어요.'}</p><Link to={profile.data ? '/mypage/profile' : '/onboarding'}>{profile.data ? '맞춤 조건 수정' : '맞춤 조건 설정 시작'}</Link></>}
    </section>
    <section className={styles.panel} aria-labelledby="interests-title"><h2 id="interests-title">관심 분야</h2>
      {interests.isPending ? <AsyncState status="loading" /> : interests.isError ? <ProfileError error={interests.error} onRetry={() => { void interests.refetch() }} /> : <><p>{interests.data.length ? `${interests.data.length}개 관심 분야를 설정했어요.` : '관심 분야가 설정되지 않았어요. 선택 사항이에요.'}</p><Link to="/mypage/interests">관심 분야 수정</Link></>}
    </section>
  </div>
}
export function MyPage() { return <ProfileGate>{(user) => <MyPageContent key={user.id} user={user} />}</ProfileGate> }

function SettingsContent({ user, kind, regions }: { user: AuthUser; kind: 'profile' | 'interests'; regions: RegionSource }) {
  const profile = useQuery({ ...profileQuery(user.id), enabled: kind === 'profile' })
  const interests = useQuery({ ...interestsQuery(user.id), enabled: kind === 'interests' })
  return <div className={styles.page}><h1>{kind === 'profile' ? '맞춤 조건 설정' : '관심 분야 설정'}</h1><Link to="/mypage">마이페이지로 돌아가기</Link><section className={styles.panel}>
    {kind === 'profile' ? profile.isPending ? <AsyncState status="loading" /> : profile.isError ? <ProfileError error={profile.error} onRetry={() => { void profile.refetch() }} /> : <ProfileForm user={user} initial={profile.data} regions={regions} />
      : interests.isPending ? <AsyncState status="loading" /> : interests.isError ? <ProfileError error={interests.error} onRetry={() => { void interests.refetch() }} /> : <InterestsForm user={user} initial={interests.data} />}
  </section></div>
}
export function ProfileSettingsPage({ kind, regions = unavailableRegions }: { kind: 'profile' | 'interests'; regions?: RegionSource }) { return <ProfileGate>{(user) => <SettingsContent key={`${user.id}-${kind}`} user={user} kind={kind} regions={regions} />}</ProfileGate> }

function OnboardingContent({ user, regions }: { user: AuthUser; regions: RegionSource }) {
  const profile = useQuery(profileQuery(user.id))
  const interests = useQuery(interestsQuery(user.id))
  const [step, setStep] = useState<'profile' | 'interests' | 'done'>('profile')
  const currentStep = profile.data ? step : 'profile'
  return <div className={styles.page}><h1>나에게 맞는 기회 준비하기</h1><p className={styles.help}>맞춤 조건은 추천에 필요하고, 관심 분야는 선택 사항이에요. 각 단계에서 저장한 정보는 중간에 나가도 유지돼요.</p>
    <nav className={styles.actions} aria-label="설정 메뉴"><Link to="/mypage/interests">관심 분야만 먼저 설정</Link><Link to="/mypage">마이페이지로 돌아가기</Link></nav>
    <section className={styles.panel}>
      {profile.isPending ? <AsyncState status="loading" /> : profile.isError ? <ProfileError error={profile.error} onRetry={() => { void profile.refetch() }} />
        : currentStep === 'profile' ? <><h2>1. 맞춤 조건</h2>{profile.data ? <p>이미 등록된 맞춤 조건이 있어요. 확인·수정하거나 다음 단계로 이동할 수 있어요.</p> : <p>필수 조건을 등록해 주세요.{regions.status === 'unavailable' && ' 지역 선택 API 제공 전에는 신규 등록이 제한돼요.'}</p>}<ProfileForm user={user} initial={profile.data} regions={regions} onSaved={() => setStep('interests')} onContinue={profile.data ? () => setStep('interests') : undefined} /></>
          : currentStep === 'interests' ? <><h2>2. 관심 분야 (선택)</h2>{interests.isPending ? <><AsyncState status="loading" /><Button variant="secondary" onClick={() => setStep('profile')}>이전 단계로 돌아가기</Button></> : interests.isError ? <><ProfileError error={interests.error} onRetry={() => { void interests.refetch() }} /><Button variant="ghost" onClick={() => setStep('done')}>관심 분야를 변경하지 않고 완료</Button><Button variant="secondary" onClick={() => setStep('profile')}>이전 단계로 돌아가기</Button></> : <InterestsForm user={user} initial={interests.data} onSaved={() => setStep('done')} onSkip={() => setStep('done')} onBack={() => setStep('profile')} />}</>
            : <><h2>설정을 완료했어요</h2><p>저장된 조건에 따른 추천을 홈에서 확인할 수 있어요. 판정 반영에는 시간이 걸릴 수 있어요.</p><Link to="/">홈으로 이동</Link><Link to="/mypage">내 설정 확인</Link></>}
    </section>
  </div>
}
export function OnboardingPage({ regions = unavailableRegions }: { regions?: RegionSource } = {}) { return <ProfileGate>{(user) => <OnboardingContent key={user.id} user={user} regions={regions} />}</ProfileGate> }
