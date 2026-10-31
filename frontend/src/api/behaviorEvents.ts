import type { QueryClient } from '@tanstack/react-query'
import { apiClient } from './client.ts'
import type { AuthUser } from './auth.ts'
import { authQueryKey, authTransitionKey } from '../auth/session.ts'
import { isOpportunityId } from './opportunityDetail.ts'
import type { PreferenceSource } from './preferences.ts'

// 백엔드 UserBehaviorSource·ExternalLinkType과 같은 값만 사용한다.
export type BehaviorSource = PreferenceSource
export type ExternalLinkType = 'APPLICATION' | 'DETAIL' | 'REFERENCE'
// SAVE/HIDE는 서버가 저장·숨김 전환 때 직접 기록하므로 화면에서 보내지 않는다.
export type BehaviorEvent =
  | { eventType: 'IMPRESSION'; source: 'RECOMMENDATION' }
  | { eventType: 'DETAIL_VIEW'; source: BehaviorSource }
  | { eventType: 'EXTERNAL_LINK_CLICK'; source: BehaviorSource; linkType: ExternalLinkType }

const behaviorSources: readonly BehaviorSource[] = ['RECOMMENDATION', 'SEARCH', 'SAVED', 'OTHER']

/** 앱 내부 링크가 location.state에 남긴 출처만 인정한다. 없거나 알 수 없는 값은 OTHER다. */
export function behaviorSourceFromState(state: unknown): BehaviorSource {
  if (!state || typeof state !== 'object' || !('behaviorSource' in state)) return 'OTHER'
  const value = state.behaviorSource
  return behaviorSources.find(source => source === value) ?? 'OTHER'
}

function isCurrentOwner(client: QueryClient, ownerId: number): boolean {
  const state = client.getQueryState(authQueryKey)
  return state?.status === 'success' && client.getQueryData<AuthUser | null>(authQueryKey)?.id === ownerId &&
    !client.isMutating({ mutationKey: authTransitionKey })
}

/**
 * 확인된 사용자의 행동 한 건을 보낸다. 로그인·로그아웃 전환 중이거나 사용자가 바뀌었으면 보내지 않는다.
 * 실패는 화면 표시·이동·외부 링크 열기에 영향을 주지 않도록 삼키며 다시 보내지 않는다(만료 토큰의 재발급 후 1회 재전송만 공통 클라이언트를 따른다).
 */
export async function recordBehaviorEvent(client: QueryClient, ownerId: number, opportunityId: number, event: BehaviorEvent): Promise<void> {
  try {
    if (!isCurrentOwner(client, ownerId) || !isOpportunityId(String(opportunityId))) return
    await apiClient.post(`/opportunities/${opportunityId}/events`, event, { requiresAuth: true })
  } catch {
    // 행동 기록 실패는 사용자 기능의 실패가 아니다.
  }
}
