import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import AsyncState from '../../components/ui/AsyncState'
import LoginRequired from '../../components/auth/LoginRequired'
import type { AuthUser } from '../../api/auth'

function ProfileGate({ children }: { children: (user: AuthUser) => ReactNode }) {
  const auth = useAuth()
  const location = useLocation()
  if (auth.status === 'checking') return <AsyncState status="loading" title="로그인 상태를 확인하고 있어요" />
  if (auth.status === 'error') return <AsyncState status="error" title="로그인 상태를 확인하지 못했어요" description="연결을 확인하고 다시 시도해 주세요." onRetry={auth.retry} />
  if (auth.status !== 'authenticated' || !auth.user) return <LoginRequired description="내 정보와 맞춤 조건은 로그인 후 확인할 수 있어요." returnTo={location.pathname} />
  return children(auth.user)
}
export default ProfileGate
