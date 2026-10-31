import { useCallback, useEffect, useMemo } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AuthContext } from './AuthContext'
import { authObservationOptions, authStatus, authTransitionKey, createAuthActions, observeAuthLoss } from './session'
import type { AuthTransitionKind } from './session'
import PushBridge from '../push/PushBridge'
import { ApiError } from '../api/ApiError'

// 사용자 정보는 Query가 소유한다. Provider는 앱 전체 인증 전환·중복 로그아웃을 한 곳에서 조율한다.
function AuthProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const location = useLocation()
  const actions = useMemo(() => createAuthActions(client), [client])
  // 공개 조회가 재발급 실패로 익명 전환되면 같은 인증 Query로 상태를 다시 확인한다.
  useEffect(() => observeAuthLoss(client), [client])
  const transition = useMutation({ mutationKey: authTransitionKey, mutationFn: (kind: AuthTransitionKind) => kind === 'oauth' ? actions.completeOAuth() : kind === 'withdraw' ? actions.withdraw() : kind === 'logout-all' ? actions.logoutAll() : actions.logout() })
  const oauthPage = location.pathname === '/login-success.html'
  const query = useQuery(authObservationOptions(client, transition, oauthPage))
  const mutate = transition.mutateAsync
  // 세션 종료 전환(로그아웃·모든 기기 로그아웃·탈퇴)이 진행 중이면 다른 종료 요청을 새 mutation으로 시작하지 않는다.
  // 시작하면 Provider의 상태가 진행 중인 전환 대신 즉시 거부된 요청을 가리키게 된다. 중복 요청은 거부되고 먼저 시작한 작업이 끝까지 진행된다.
  const end = useCallback(async (kind: Exclude<AuthTransitionKind, 'oauth'>) => {
    if (client.isMutating({ mutationKey: authTransitionKey }) > 0) throw new ApiError('configuration', '다른 로그인 처리가 진행 중이에요. 완료된 뒤 다시 시도해 주세요.')
    await mutate(kind)
  }, [client, mutate])
  const completeOAuth = useCallback(async () => { await mutate('oauth') }, [mutate])
  const logout = useCallback(async () => { await end('logout') }, [end])
  const logoutAll = useCallback(async () => { await end('logout-all') }, [end])
  const withdraw = useCallback(async () => { await end('withdraw') }, [end])
  const status = authStatus(query, transition, oauthPage)
  return <AuthContext.Provider value={{
    status, user: status === 'authenticated' ? query.data ?? undefined : undefined,
    logoutPending: transition.isPending && transition.variables === 'logout',
    logoutError: transition.variables === 'logout' ? transition.error : null,
    logoutAllPending: transition.isPending && transition.variables === 'logout-all',
    logoutAllError: transition.variables === 'logout-all' ? transition.error : null,
    loggedOutAll: transition.isSuccess && transition.variables === 'logout-all',
    logoutAll,
    withdrawPending: transition.isPending && transition.variables === 'withdraw',
    withdrawError: transition.variables === 'withdraw' ? transition.error : null,
    withdrawn: transition.isSuccess && transition.variables === 'withdraw',
    withdraw,
    // 완료 페이지는 전용 재시도로 사용자 확인→복구 초기화→복귀까지 수행한다.
    retry: oauthPage ? undefined : () => { void query.refetch() }, completeOAuth, logout,
  }}><PushBridge />{children}</AuthContext.Provider>
}

export default AuthProvider
