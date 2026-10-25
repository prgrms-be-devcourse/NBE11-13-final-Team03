import { createContext, useContext } from 'react'
import type { AuthUser } from '../api/auth'

export type AuthStatus = 'checking' | 'authenticated' | 'anonymous' | 'error'
export type AuthContextValue = {
  status: AuthStatus
  user?: AuthUser
  logoutPending: boolean
  logoutError: Error | null
  retry?: () => void
  completeOAuth: () => Promise<void>
  logout: () => Promise<void>
}
export const AuthContext = createContext<AuthContextValue | undefined>(undefined)
export function useAuth(): AuthContextValue {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('AuthProvider가 필요합니다.')
  return auth
}
