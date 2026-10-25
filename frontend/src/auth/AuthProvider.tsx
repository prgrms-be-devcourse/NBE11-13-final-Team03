import { useCallback, useMemo } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AuthContext } from './AuthContext'
import { authObservationOptions, authStatus, createAuthActions } from './session'

// 사용자 정보는 Query가 소유한다. Provider는 앱 전체 인증 전환·중복 로그아웃을 한 곳에서 조율한다.
function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const location = useLocation()
  const actions = useMemo(() => createAuthActions(client), [client])
  const transition = useMutation({ mutationFn: (kind: 'oauth' | 'logout') => kind === 'oauth' ? actions.completeOAuth() : actions.logout() })
  const oauthPage = location.pathname === '/login-success.html'
  const query = useQuery(authObservationOptions(client, transition, oauthPage))
  const mutate = transition.mutateAsync
  const completeOAuth = useCallback(async () => { await mutate('oauth') }, [mutate])
  const logout = useCallback(async () => { await mutate('logout') }, [mutate])
  const status = authStatus(query, transition, oauthPage)
  return <AuthContext.Provider value={{
    status, user: status === 'authenticated' ? query.data ?? undefined : undefined,
    logoutPending: transition.isPending && transition.variables === 'logout',
    logoutError: transition.variables === 'logout' ? transition.error : null,
    // 완료 페이지는 전용 재시도로 사용자 확인→복구 초기화→복귀까지 수행한다.
    retry: oauthPage ? undefined : () => { void query.refetch() }, completeOAuth, logout,
  }}>{children}</AuthContext.Provider>
}

export default AuthProvider
