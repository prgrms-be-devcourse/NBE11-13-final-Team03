import type { QueryClient, Query } from '@tanstack/react-query'
import { deleteCurrentUser, getCurrentUser, postLogout, verifyOAuthUser } from '../api/auth.ts'
import type { AuthUser } from '../api/auth.ts'
import { endAuthSession, onAuthLost, resetAuthRecovery } from '../api/client.ts'
import { ApiError } from '../api/ApiError.ts'
import type { AuthStatus } from './AuthContext.ts'
import { changePushIdentity, releasePushSession } from '../push/lifecycle.ts'

export const authQueryKey = ['auth', 'user'] as const
export const authTransitionKey = ['auth', 'transition'] as const
export function isPersonalizedQuery(query: Query): boolean {
  const [root, part] = query.queryKey
  return root === 'notifications' || root === 'saved-opportunities' || root === 'profile' || root === 'user-interests' || root === 'recommendations' || root === 'opportunities' || root === 'opportunity-detail' || (root === 'home' && part === 'opportunities')
}
export async function resetPersonalizedQueries(client: QueryClient): Promise<void> {
  await client.cancelQueries({ predicate: isPersonalizedQuery })
  client.removeQueries({ queryKey: ['recommendations'] })
  client.removeQueries({ queryKey: ['profile'] })
  client.removeQueries({ queryKey: ['user-interests'] })
  client.removeQueries({ queryKey: ['saved-opportunities'] })
  client.removeQueries({ queryKey: ['notifications'] })
  // reset은 활성 observer의 과거 데이터도 즉시 비운다. 새 쿠키의 공개 조회는 다시 실행한다.
  void client.resetQueries({ predicate: (query) => isPersonalizedQuery(query) && !['recommendations', 'profile', 'user-interests', 'saved-opportunities', 'notifications'].includes(String(query.queryKey[0])) }).catch(() => {})
}
// 푸시 정리 실패는 인증 판정과 분리한다. 성공으로 기록하지 않으므로 남은 이전 소유 정보로 다음 인증 확인에서 다시 정리한다.
// 이전 소유자에게 묶인 동안에는 PushBridge가 새 사용자의 앱 내 푸시 안내·재연결을 하지 않는다.
async function syncPushIdentity(userId: number | null): Promise<void> {
  try { await changePushIdentity(userId) } catch { /* 다음 인증 확인 또는 푸시 설정 화면에서 다시 정리한다. */ }
}
export function authQueryOptions(client: QueryClient) {
  return {
    queryKey: authQueryKey,
    queryFn: async ({ signal }: { signal: AbortSignal }) => {
      const previous = client.getQueryData<AuthUser | null>(authQueryKey)
      const user = await getCurrentUser(signal)
      if (signal.aborted) throw new ApiError('cancelled', '인증 확인이 취소되었습니다.')
      await syncPushIdentity(user?.id ?? null)
      if (signal.aborted) throw new ApiError('cancelled', '인증 확인이 취소되었습니다.')
      if (previous?.id !== user?.id) await resetPersonalizedQueries(client)
      return user
    },
    staleTime: 30_000,
  }
}

/**
 * recoverAuth 공개 조회의 재발급이 401로 확정 실패하면 인증 Query를 서버에서 다시 확인한다.
 * 확인 결과 null이면 기존 queryFn이 푸시 소유 정리와 개인화 캐시 초기화를 그대로 수행한다.
 * 이미 확인 중이면 그 확인이 같은 재발급 실패를 공유하므로 중복 요청을 만들지 않는다.
 */
export function observeAuthLoss(client: QueryClient): () => void {
  return onAuthLost(() => {
    if (client.isFetching({ queryKey: authQueryKey })) return
    void client.invalidateQueries({ queryKey: authQueryKey }).catch(() => { /* Query의 error 상태가 보호 기능을 중단한다. */ })
  })
}

export type AuthTransitionKind = 'oauth' | 'logout' | 'withdraw'
type AuthTransition = { isPending: boolean; isError: boolean; variables?: AuthTransitionKind; error: Error | null }
type AuthQueryState = { isPending: boolean; isFetching: boolean; isError: boolean; data: AuthUser | null | undefined }
export function isUncertainLogout(error: unknown): boolean {
  return error instanceof ApiError && ['network', 'timeout', 'unknown'].includes(error.kind)
}
// Provider와 observer 회귀 테스트가 동일한 Query 활성화·상태 판정 규칙을 사용한다.
export function authObservationOptions(client: QueryClient, transition: AuthTransition, oauthPage: boolean) {
  return {
    ...authQueryOptions(client),
    enabled: (query: { state: { status: string } }) => !oauthPage && !transition.isPending &&
      !(transition.variables !== 'oauth' && isUncertainLogout(transition.error) && query.state.status === 'error'),
  }
}
export function authStatus(query: AuthQueryState, transition: AuthTransition, oauthPage: boolean): AuthStatus {
  if (transition.isPending || (oauthPage && !transition.isError)) return 'checking'
  if (oauthPage && transition.isError) return 'error'
  if (query.isPending) return 'checking'
  if (query.isError) return 'error'
  // 이미 확인된 사용자의 백그라운드 재조회는 보호 폼을 언마운트하지 않는다.
  // 사용자 정보가 없는 최초 확인·불확실한 세션 복구는 계속 checking으로 차단한다.
  if (query.isFetching && !query.data) return 'checking'
  return query.data ? 'authenticated' : 'anonymous'
}

export function createAuthActions(client: QueryClient) {
  let inFlight: { kind: AuthTransitionKind; promise: Promise<AuthUser | null> } | undefined
  function run(kind: AuthTransitionKind): Promise<AuthUser | null> {
    if (inFlight) return inFlight.kind === kind ? inFlight.promise : Promise.reject(new ApiError('configuration', '다른 인증 처리가 진행 중입니다.'))
    const promise = (async () => {
      await client.cancelQueries({ queryKey: authQueryKey })
      if (kind === 'oauth') {
        const user = await verifyOAuthUser()
        await syncPushIdentity(user.id)
        resetAuthRecovery()
        await resetPersonalizedQueries(client)
        client.setQueryData(authQueryKey, user)
        return user
      }
      await client.cancelQueries({ predicate: isPersonalizedQuery })
      let pushReleased = true
      try {
        const user = client.getQueryData<AuthUser | null>(authQueryKey)
        // 탈퇴도 세션 종료다. 인증이 남아 있는 동안 푸시 엔드포인트를 먼저 해제한다.
        // 푸시 정리 실패가 서버 로그아웃·탈퇴를 막지 않는다. 실패한 정리 상태는 저장소에 그대로 남긴다.
        if (user) await releasePushSession(user.id).catch(() => { pushReleased = false })
        await (kind === 'withdraw' ? deleteCurrentUser() : postLogout())
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
      // 서버 해제는 쿠키가 없어 재시도하지 않는다. 로컬 수신만 한 번 더 끊고, 실패하면 다음 인증 확인에서 재시도한다.
      if (!pushReleased) await syncPushIdentity(null)
      await resetPersonalizedQueries(client)
      client.setQueryData(authQueryKey, null)
      return null
    })().finally(() => { inFlight = undefined })
    inFlight = { kind, promise }
    return promise
  }
  return { completeOAuth: () => run('oauth'), logout: () => run('logout'), withdraw: () => run('withdraw') }
}
