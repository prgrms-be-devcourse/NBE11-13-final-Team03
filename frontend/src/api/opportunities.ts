import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { InterestOptions, OpportunitySearchItem, OpportunitySearchResponse, SearchParams } from '../pages/opportunities/types.ts'
import { parseSearchParams, serializeSearchParams } from '../pages/opportunities/searchParams.ts'

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
  const response = await apiClient.get<unknown>('/opportunities', { params: serializeSearchParams(safe), signal })
  if (!isSearchResponse(response.data)) throw new ApiError('unknown', '검색 응답 형식을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.')
  return response.data
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
