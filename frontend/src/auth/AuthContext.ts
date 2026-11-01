import { createContext, useContext } from 'react'
import type { AuthUser } from '../api/auth'

export type AuthStatus = 'checking' | 'authenticated' | 'anonymous' | 'error'
export type AuthContextValue = {
  status: AuthStatus
  user?: AuthUser
  logoutPending: boolean
  logoutError: Error | null
  logoutAllPending?: boolean
  logoutAllError?: Error | null
  /** 이 화면에서 서버 204로 모든 기기 로그아웃이 확인되었다. 다음 로그인 전환 전까지 유지된다. */
  loggedOutAll?: boolean
  withdrawPending?: boolean
  withdrawError?: Error | null
  /** 이 화면에서 서버 204로 탈퇴가 확인되었다. 다음 로그인 전환 전까지 유지된다. */
  withdrawn?: boolean
  retry?: () => void
  completeOAuth: () => Promise<void>
  logout: () => Promise<void>
  logoutAll?: () => Promise<void>
  withdraw?: () => Promise<void>
}
export const AuthContext = createContext<AuthContextValue | undefined>(undefined)
export function useAuth(): AuthContextValue {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('AuthProvider가 필요합니다.')
  return auth
}
