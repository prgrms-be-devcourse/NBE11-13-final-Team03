import type { Query, QueryClient } from '@tanstack/react-query'
import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { AuthUser } from './auth.ts'
import { authQueryKey, authTransitionKey } from '../auth/session.ts'
import { isOpportunityId } from './opportunityDetail.ts'
import type { OpportunityType } from '../pages/opportunities/types.ts'

export type PreferenceSource = 'SEARCH' | 'RECOMMENDATION' | 'SAVED' | 'OTHER'
export type PreferenceChange = 'save' | 'unsave' | 'hide' | 'unhide'
export type SavedOpportunity = { opportunityId: number; type: OpportunityType; title: string; category: string | null; subCategory: string | null; savedAt: string }
export type SavedOpportunities = { content: SavedOpportunity[]; page: number; size: number; totalElements: number; totalPages: number; hasNext: boolean }
const integer = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown) => value === null || typeof value === 'string'
export function isSavedOpportunities(value: unknown): value is SavedOpportunities {
  return record(value) && integer(value.page) && integer(value.size) && value.size >= 1 && value.size <= 100 && integer(value.totalElements) &&
    integer(value.totalPages) && typeof value.hasNext === 'boolean' && Array.isArray(value.content) && value.content.length <= value.size &&
    value.content.every((item: unknown) => record(item) && integer(item.opportunityId) && item.opportunityId > 0 &&
      ['YOUTH_POLICY', 'VOCATIONAL_TRAINING'].includes(String(item.type)) && typeof item.title === 'string' && text(item.category) && text(item.subCategory) &&
      typeof item.savedAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(item.savedAt) && Number.isFinite(Date.parse(item.savedAt)))
}
function validatePage(page: number, size: number): void {
  if (!integer(page) || page > 2147483647 || !integer(size) || size < 1 || size > 100) throw new ApiError('configuration', '저장 목록 페이지 조건을 확인해 주세요.')
}
export async function getSavedOpportunities(page = 0, size = 20, signal?: AbortSignal): Promise<SavedOpportunities> {
  validatePage(page, size)
  const { data } = await apiClient.get<unknown>('/users/me/saved-opportunities', { params: { page, size }, requiresAuth: true, signal })
  if (!isSavedOpportunities(data) || data.page !== page || data.size !== size) throw new ApiError('unknown', '저장 목록 응답 형식을 확인할 수 없어요.')
  return data
}
export const savedOpportunitiesQuery = (userId: number, page = 0, size = 20) => ({
  queryKey: ['saved-opportunities', userId, page, size] as const,
  queryFn: ({ signal }: { signal: AbortSignal }) => getSavedOpportunities(page, size, signal),
  gcTime: 0,
})
export function correctedSavedPage(page: number, data: SavedOpportunities): number {
  return data.page === page && !data.content.length && page > 0 ? Math.min(page - 1, Math.max(0, data.totalPages - 1)) : page
}
async function changePreference(id: number, change: PreferenceChange, source: PreferenceSource, signal?: AbortSignal): Promise<void> {
  if (!isOpportunityId(String(id))) throw new ApiError('configuration', '올바른 공고 번호가 아니에요.')
  const adding = change === 'save' || change === 'hide'
  const resource = change === 'save' || change === 'unsave' ? 'save' : 'hide'
  const response = await apiClient.request({ url: `/opportunities/${id}/${resource}`, method: adding ? 'POST' : 'DELETE', params: adding ? { source } : undefined, requiresAuth: true, signal })
  if (response.status !== 204) throw new ApiError('unknown', '변경 완료 응답을 확인하지 못했어요. 최신 상태를 다시 조회해 주세요.')
}
export const saveOpportunity = (id: number, source: PreferenceSource = 'OTHER', signal?: AbortSignal) => changePreference(id, 'save', source, signal)
export const unsaveOpportunity = (id: number, signal?: AbortSignal) => changePreference(id, 'unsave', 'OTHER', signal)
export const hideOpportunity = (id: number, source: PreferenceSource = 'OTHER', signal?: AbortSignal) => changePreference(id, 'hide', source, signal)
export const unhideOpportunity = (id: number, signal?: AbortSignal) => changePreference(id, 'unhide', 'OTHER', signal)

export const preferenceMutationKey = (userId: number | undefined, id: number) => ['preference-change', userId ?? null, id] as const
function assertPreferenceOwner(client: QueryClient, owner: AuthUser, signal?: AbortSignal): void {
  const state = client.getQueryState(authQueryKey)
  if (signal?.aborted || client.getQueryData(authQueryKey) !== owner || state?.status !== 'success' || state.fetchStatus === 'fetching' || client.isMutating({ mutationKey: authTransitionKey })) throw new ApiError('cancelled', '인증 상태가 변경되어 요청 결과 처리를 중단했어요.')
}
function affectedPreferenceQueries(userId: number, id: number, change: PreferenceChange) {
  const saving = change === 'save' || change === 'unsave'
  return (query: Query) => {
    const [root, part] = query.queryKey
    if (root === 'recommendations') return part === userId
    return saving && ((root === 'saved-opportunities' && part === userId) || root === 'opportunities' || (root === 'opportunity-detail' && String(part) === String(id)) || (root === 'home' && part === 'opportunities'))
  }
}
export async function refreshPreferenceQueries(client: QueryClient, owner: AuthUser, id: number, change: PreferenceChange): Promise<void> {
  assertPreferenceOwner(client, owner)
  const predicate = affectedPreferenceQueries(owner.id, id, change)
  // 변경 전에 시작된 GET이 늦게 도착해 saved를 되돌리지 않도록 취소 후 재조회한다.
  await client.cancelQueries({ predicate })
  assertPreferenceOwner(client, owner)
  await client.invalidateQueries({ predicate }, { throwOnError: true })
}
type Attempt = { owner: AuthUser; change: PreferenceChange; promise: Promise<void> }
const attempts = new WeakMap<QueryClient, Map<string, Attempt>>()
export function runPreferenceChange(client: QueryClient, owner: AuthUser, id: number, change: PreferenceChange, source: PreferenceSource, signal?: AbortSignal): Promise<void> {
  try { assertPreferenceOwner(client, owner, signal) } catch (error) { return Promise.reject(error) }
  let pending = attempts.get(client)
  if (!pending) { pending = new Map(); attempts.set(client, pending) }
  const key = `${owner.id}:${id}`
  const existing = pending.get(key)
  if (existing?.owner === owner) return existing.change === change ? existing.promise : Promise.reject(new ApiError('configuration', '이 공고의 변경을 처리하고 있어요. 완료 후 다시 시도해 주세요.'))
  const predicate = affectedPreferenceQueries(owner.id, id, change)
  const promise = (async () => {
    await client.cancelQueries({ predicate })
    assertPreferenceOwner(client, owner, signal)
    await changePreference(id, change, source, signal)
    assertPreferenceOwner(client, owner, signal)
    await refreshPreferenceQueries(client, owner, id, change)
  })().finally(() => { if (pending.get(key)?.promise === promise) pending.delete(key) })
  pending.set(key, { owner, change, promise })
  return promise
}
