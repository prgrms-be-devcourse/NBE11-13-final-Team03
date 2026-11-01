import { useCallback, useMemo } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { authQueryKey } from '../../auth/session'
import type { AuthUser } from '../../api/auth'
import { adminKeys } from '../../api/admin'
import AsyncState from '../../components/ui/AsyncState'
import LoginRequired from '../../components/auth/LoginRequired'
import { AdminAccessContext } from './adminAccess'
import styles from './Admin.module.css'

const sections = [
  { to: '/admin/collection', label: '데이터 수집' },
  { to: '/admin/raw-records', label: '원본 레코드' },
  { to: '/admin/retention', label: '행동 로그 보관' },
]

function AdminShell({ owner }: { owner: AuthUser }) {
  const client = useQueryClient()
  // 서버의 관리자 권한 거부가 최종 기준이다. 거부는 컴포넌트 상태가 아니라 인증 세션 단위로 기억한다.
  // 다른 화면을 거쳐 돌아와도 다시 열지 않고, 로그인 전환(resetPersonalizedQueries가 'admin' 캐시를 지움) 뒤에만 다시 확인한다.
  const deniedKey = adminKeys.denied(owner.id)
  const denied = useQuery({ queryKey: deniedKey, queryFn: () => client.getQueryData<boolean>(deniedKey) ?? false, enabled: false, staleTime: Infinity, gcTime: Infinity }).data === true
  const deny = useCallback(() => {
    client.setQueryData(adminKeys.denied(owner.id), true)
    void client.invalidateQueries({ queryKey: authQueryKey }).catch(() => {})
  }, [client, owner.id])
  const access = useMemo(() => ({ owner, deny }), [owner, deny])
  if (denied) return <AsyncState status="error" title="관리자 권한이 확인되지 않았어요" description="서버에서 관리자 요청을 거부했어요. 권한이 바뀌었다면 다시 로그인한 뒤 이용해 주세요." action={<Link to="/">홈으로 이동</Link>} />
  return <div className={styles.shell}>
    <nav className={styles.sideNav} aria-label="관리자 메뉴">
      <ul>{sections.map(({ to, label }) => <li key={to}><NavLink to={to} className={({ isActive }) => isActive ? styles.active : undefined}>{label}</NavLink></li>)}</ul>
    </nav>
    <div className={styles.content}><AdminAccessContext.Provider value={access}><Outlet /></AdminAccessContext.Provider></div>
  </div>
}

/** 서버에서 확인한 role=ADMIN 사용자만 관리자 화면을 그린다. 확인 중·실패·비로그인·일반 사용자에게는 관리자 콘텐츠를 렌더링하지 않는다. */
function AdminLayout() {
  const auth = useAuth()
  const location = useLocation()
  if (auth.status === 'checking') return <AsyncState status="loading" title="로그인 상태를 확인하고 있어요" />
  if (auth.status === 'error') return <AsyncState status="error" title="로그인 상태를 확인하지 못했어요" onRetry={auth.retry} />
  if (auth.status !== 'authenticated' || !auth.user) return <LoginRequired description="로그인 후 이용할 수 있어요." returnTo={location.pathname} />
  if (auth.user.role !== 'ADMIN') return <AsyncState status="empty" title="접근 권한이 없어요" description="이 화면을 이용할 권한이 없어요." action={<Link to="/">홈으로 이동</Link>} />
  return <section className={styles.admin} aria-labelledby="admin-title">
    <header className={styles.pageHeader}><p className={styles.eyebrow}>Y:ON 운영</p><h1 id="admin-title">관리자</h1></header>
    <AdminShell key={auth.user.id} owner={auth.user} />
  </section>
}
export default AdminLayout
