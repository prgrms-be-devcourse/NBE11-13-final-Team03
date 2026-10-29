import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { InterestOptions, OpportunitySearchItem, OpportunitySearchResponse, SearchParams } from '../pages/opportunities/types.ts'
import { parseSearchParams, serializeSearchParams } from '../pages/opportunities/searchParams.ts'
import { keepPreviousData } from '@tanstack/react-query'

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const nullableText = (value: unknown): boolean => value === null || typeof value === 'string'
const statuses = new Set(['ELIGIBLE', 'POSSIBLY_ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN'])

function isItem(value: unknown): value is OpportunitySearchItem {
  return record(value) && integer(value.opportunityId) && value.opportunityId > 0 &&
    (value.type === 'YOUTH_POLICY' || value.type === 'VOCATIONAL_TRAINING') && typeof value.title === 'string' &&
    nullableText(value.category) && nullableText(value.subCategory) && nullableText(value.detailCategory) &&
    typeof value.createdAt === 'string' && typeof value.saved === 'boolean' &&
    (value.eligibilityStatus === null || (typeof value.eligibilityStatus === 'string' && statuses.has(value.eligibilityStatus)))
}

export function isSearchResponse(value: unknown): value is OpportunitySearchResponse {
  return record(value) && Array.isArray(value.content) && value.content.every(isItem) && integer(value.page) &&
    integer(value.size) && value.size >= 1 && value.size <= 100 && integer(value.totalElements) &&
    integer(value.totalPages) && typeof value.hasNext === 'boolean'
}

export async function searchOpportunities(params: SearchParams, signal?: AbortSignal): Promise<OpportunitySearchResponse> {
  const safe = parseSearchParams(serializeSearchParams(params)).params
  const requestParams = serializeSearchParams(safe)
  // 검색 화면의 기본값(10)은 서버 기본값(20)과 달라 명시적으로 전달한다.
  requestParams.set('size', String(safe.size))
  const response = await apiClient.get<unknown>('/opportunities', {
    params: requestParams, signal,
    // 자격 필터는 로그인 전용이라 재발급 실패를 오류로 유지한다. 그 밖의 검색은 재발급 실패 시 비회원 결과로 대체한다.
    ...(safe.eligibilityStatuses.length > 0 ? { requiresAuth: true } : { recoverAuth: true }),
  })
  if (!isSearchResponse(response.data)) throw new ApiError('unknown', '검색 응답 형식을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.')
  return response.data
}

export function searchQueryOptions(params: SearchParams, userId: number | null, enabled = true) {
  return {
    queryKey: ['opportunities', serializeSearchParams(params).toString(), userId] as const,
    queryFn: ({ signal }: { signal: AbortSignal }) => searchOpportunities(params, signal),
    enabled,
    // 페이지·조건 변경에만 이전 결과를 사용하고 계정 변경에는 재사용하지 않는다.
    placeholderData: (previous: OpportunitySearchResponse | undefined, query: { queryKey: readonly unknown[] } | undefined) =>
      query?.queryKey[2] === userId ? keepPreviousData(previous) : undefined,
  }
}

export async function getInterestOptions(signal?: AbortSignal): Promise<InterestOptions> {
  const { data } = await apiClient.get<unknown>('/interests/options', { signal })
  if (!record(data) || !Array.isArray(data.categories) || !data.categories.every((category: unknown) =>
    record(category) && typeof category.category === 'string' && Array.isArray(category.subCategories) &&
    category.subCategories.every((sub: unknown) => record(sub) && typeof sub.subCategory === 'string' &&
      Array.isArray(sub.detailCategories) && sub.detailCategories.every((detail: unknown) => typeof detail === 'string')))) {
    throw new ApiError('unknown', '분류 목록의 응답 형식을 확인할 수 없어요.')
  }
  return data as InterestOptions
}
