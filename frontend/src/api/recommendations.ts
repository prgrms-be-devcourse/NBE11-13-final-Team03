import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { OpportunitySearchItem, ApplicationStatus } from '../pages/opportunities/types.ts'
import type { EligibilityStatus } from '../components/ui/StatusBadge.tsx'
import type { RegionItem } from './opportunityDetail.ts'

export type RecommendationReasons = { badges: string[]; description: string }
export type RecommendationItem = Omit<OpportunitySearchItem, 'createdAt' | 'eligibilityStatus'> & {
  createdAt?: string | null
  regions: RegionItem[]
  applicationStatus: ApplicationStatus
  eligibilityStatus: EligibilityStatus
  applicationEndDate?: string | null
  trainingStartDate?: string | null
  recommendationReasons: RecommendationReasons
}

const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown): boolean => value == null || typeof value === 'string'
const member = (value: unknown, options: string[]): boolean => typeof value === 'string' && options.includes(value)

export function isRecommendations(value: unknown): value is RecommendationItem[] {
  return Array.isArray(value) && value.length <= 5 && value.every((item: unknown) => {
    if (!record(item)) return false
    const reasons = item.recommendationReasons
    return typeof item.opportunityId === 'number' && Number.isSafeInteger(item.opportunityId) && item.opportunityId > 0 &&
      member(item.type, ['YOUTH_POLICY', 'VOCATIONAL_TRAINING']) && typeof item.title === 'string' && typeof item.saved === 'boolean' &&
      ['category', 'subCategory', 'detailCategory'].every((key) => item[key] === null || typeof item[key] === 'string') &&
      ['createdAt', 'applicationEndDate', 'trainingStartDate'].every((key) => text(item[key])) &&
      member(item.applicationStatus, ['UPCOMING', 'OPEN', 'ALWAYS_OPEN', 'CLOSED', 'UNKNOWN']) &&
      member(item.eligibilityStatus, ['ELIGIBLE', 'POSSIBLY_ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN']) &&
      Array.isArray(item.regions) && item.regions.every((region: unknown) => record(region) &&
        member(region.role, ['RESIDENCE', 'SCHOOL', 'BUSINESS', 'IMPLEMENTATION', 'UNKNOWN']) && typeof region.status === 'string' && text(region.code) && text(region.name) &&
        (region.validationStatus == null || member(region.validationStatus, ['VALID', 'INVALID', 'DATA_CONFLICT', 'UNKNOWN', 'NEEDS_REVIEW', 'UNVERIFIED']))) &&
      record(reasons) && typeof reasons.description === 'string' && Array.isArray(reasons.badges) && reasons.badges.length <= 3 &&
      reasons.badges.every((badge: unknown) => typeof badge === 'string')
  })
}

export async function getRecommendations(signal?: AbortSignal): Promise<RecommendationItem[]> {
  const { data } = await apiClient.get<unknown>('/users/me/recommendations', { requiresAuth: true, signal })
  if (!isRecommendations(data)) throw new ApiError('unknown', '추천 응답 형식을 확인할 수 없습니다.')
  return data
}

// 후속 인증 소유자가 서버에서 확인한 사용자 ID만 전달한다. 쿠키 유무로 인증을 추측하지 않는다.
export function recommendationsQuery(confirmedUserId?: number) {
  const userId = confirmedUserId !== undefined && Number.isSafeInteger(confirmedUserId) && confirmedUserId > 0 ? confirmedUserId : null
  return {
    queryKey: ['recommendations', userId],
    enabled: userId !== null,
    queryFn: ({ signal }: { signal: AbortSignal }) => {
      if (userId === null) throw new ApiError('configuration', '확인된 로그인 상태가 필요합니다.')
      return getRecommendations(signal)
    },
    // 계정 변경 시 이전 계정 데이터를 placeholder로 넘기거나 비활성 캐시로 유지하지 않는다.
    placeholderData: undefined,
    gcTime: 0,
  }
}
