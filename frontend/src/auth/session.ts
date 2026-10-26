import type { QueryClient, Query } from '@tanstack/react-query'
import { getCurrentUser, postLogout, verifyOAuthUser } from '../api/auth.ts'
import type { AuthUser } from '../api/auth.ts'
import { endAuthSession, resetAuthRecovery } from '../api/client.ts'
import { ApiError } from '../api/ApiError.ts'
import type { AuthStatus } from './AuthContext.ts'

export const authQueryKey = ['auth', 'user'] as const
export const authTransitionKey = ['auth', 'transition'] as const
export function isPersonalizedQuery(query: Query): boolean {
  const [root, part] = query.queryKey
  return root === 'profile' || root === 'user-interests' || root === 'recommendations' || root === 'opportunities' || root === 'opportunity-detail' || (root === 'home' && part === 'opportunities')
}
export async function resetPersonalizedQueries(client: QueryClient): Promise<void> {
  await client.cancelQueries({ predicate: isPersonalizedQuery })
  client.removeQueries({ queryKey: ['recommendations'] })
  client.removeQueries({ queryKey: ['profile'] })
  client.removeQueries({ queryKey: ['user-interests'] })
  // reset은 활성 observer의 과거 데이터도 즉시 비운다. 새 쿠키의 공개 조회는 다시 실행한다.
  void client.resetQueries({ predicate: (query) => isPersonalizedQuery(query) && !['recommendations', 'profile', 'user-interests'].includes(String(query.queryKey[0])) }).catch(() => {})
}
export function authQueryOptions(client: QueryClient) {
  return {
    queryKey: authQueryKey,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      const previous = client.getQueryData<AuthUser | null>(authQueryKey)
      const user = await getCurrentUser(signal)
      if (signal.aborted) throw new ApiError('cancelled', '인증 확인이 취소되었습니다.')
      if (previous?.id !== user?.id) await resetPersonalizedQueries(client)
      return user
    },
    staleTime: 30_000,
  }
}

type AuthTransition = { isPending: boolean; isError: boolean; variables?: 'oauth' | 'logout'; error: Error | null }
type AuthQueryState = { isPending: boolean; isFetching: boolean; isError: boolean; data: AuthUser | null | undefined }
export function isUncertainLogout(error: unknown): boolean {
  return error instanceof ApiError && ['network', 'timeout', 'unknown'].includes(error.kind)
}
// Provider와 observer 회귀 테스트가 동일한 Query 활성화·상태 판정 규칙을 사용한다.
export function authObservationOptions(client: QueryClient, transition: AuthTransition, oauthPage: boolean) {
  return {
    ...authQueryOptions(client),
    enabled: (query: { state: { status: string } }) => !oauthPage && !transition.isPending &&
      !(transition.variables === 'logout' && isUncertainLogout(transition.error) && query.state.status === 'error'),
  }
}
export function authStatus(query: AuthQueryState, transition: AuthTransition, oauthPage: boolean): AuthStatus {
  if (transition.isPending || (oauthPage && !transition.isError)) return 'checking'
  if (oauthPage && transition.isError) return 'error'
  if (query.isPending || query.isFetching) return 'checking'
  if (query.isError) return 'error'
  return query.data ? 'authenticated' : 'anonymous'
}

export function createAuthActions(client: QueryClient) {
  let inFlight: { kind: 'oauth' | 'logout'; promise: Promise<AuthUser | null> } | undefined
  function run(kind: 'oauth' | 'logout'): Promise<AuthUser | null> {
    if (inFlight) return inFlight.kind === kind ? inFlight.promise : Promise.reject(new ApiError('configuration', '다른 인증 처리가 진행 중입니다.'))
    const promise = (async () => {
      await client.cancelQueries({ queryKey: authQueryKey })
      if (kind === 'oauth') {
        const user = await verifyOAuthUser()
        resetAuthRecovery()
        await resetPersonalizedQueries(client)
        client.setQueryData(authQueryKey, user)
        return user
      }
      await client.cancelQueries({ predicate: isPersonalizedQuery })
      try {
        await postLogout()
      } catch (error) {
        if (isUncertainLogout(error)) {
          // 응답 유실은 서버 성공/실패를 확정하지 않는다. mutation이 pending인 동안
          // fresh 캐시를 건너뛰고 서버를 확인한다. 조회가 실패해도 원래 로그아웃 오류를 유지한다.
          await resetPersonalizedQueries(client)
          try { await client.fetchQuery({ ...authQueryOptions(client), staleTime: 0 }) } catch { /* Query의 error 상태가 보호 기능을 계속 중단한다. */ }
        }
        throw error
      }
      endAuthSession()
      await resetPersonalizedQueries(client)
      client.setQueryData(authQueryKey, null)
      return null
    })().finally(() => { inFlight = undefined })
    inFlight = { kind, promise }
    return promise
  }
  return { completeOAuth: () => run('oauth'), logout: () => run('logout') }
}
