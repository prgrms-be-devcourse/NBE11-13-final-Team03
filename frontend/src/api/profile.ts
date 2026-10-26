import type { QueryClient } from '@tanstack/react-query'
import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import { choices, emptyProfile, validateProfile } from '../pages/profile/profileModel.ts'
import type { Profile, ProfileInput } from '../pages/profile/profileModel.ts'
import { authQueryKey, authTransitionKey } from '../auth/session.ts'
import type { AuthUser } from './auth.ts'

export type Interest = { type: 'CATEGORY' | 'SUB_CATEGORY' | 'DETAIL_CATEGORY' | 'NCS'; code: string }
const profilePath = '/users/me/eligibility-profile'
const interestsPath = '/users/me/interests'
export const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
export function parseProfile(data: unknown): Profile {
  const invalid = () => new ApiError('unknown', '맞춤 조건 응답 형식을 확인할 수 없어요.')
  if (!isRecord(data)) throw invalid()
  const value: Record<string, unknown> = { ...emptyProfile, ...data }
  if (typeof value.birthDate !== 'string' || typeof value.residenceRegionCode !== 'string' || (value.schoolRegionCode != null && typeof value.schoolRegionCode !== 'string') || (value.updatedAt != null && typeof value.updatedAt !== 'string')) throw invalid()
  const enums = { employmentStatus: choices.employmentStatus, educationLevel: choices.educationLevel, enrollmentStatus: choices.enrollmentStatus, maritalStatus: choices.maritalStatus, housingStatus: choices.housingStatus, homelessStatus: choices.yesNo, householderStatus: choices.yesNo, majorField: choices.majorField, trainingCardStatus: choices.trainingCardStatus, kdtParticipation: choices.trainingParticipation, otherTrainingParticipation: choices.trainingParticipation }
  for (const [key, options] of Object.entries(enums)) if (value[key] != null && (typeof value[key] !== 'string' || !Object.hasOwn(options, value[key]))) throw invalid()
  if (value.householdSize !== null && typeof value.householdSize !== 'number') throw invalid()
  for (const key of ['personalIncome', 'householdIncome', 'medianIncome', 'studentLoanBracket', 'specialTargets', 'militaryService']) {
    const answer: unknown = value[key]
    if (answer == null) continue
    if (!isRecord(answer) || typeof answer.status !== 'string' || !Object.hasOwn(key === 'militaryService' ? choices.military : choices.answer, answer.status)) throw invalid()
    if (key.endsWith('Income') && key !== 'medianIncome') {
      if (answer.status === 'NOT_APPLICABLE' || (answer.amount !== null && typeof answer.amount !== 'number') || (answer.period !== null && !Object.hasOwn(choices.period, String(answer.period)))) throw invalid()
    } else if (key === 'specialTargets') {
      if (!Array.isArray(answer.targets) || !answer.targets.every((item: unknown) => typeof item === 'string' && Object.hasOwn(choices.targets, item))) throw invalid()
    } else {
      const number = answer[key === 'medianIncome' ? 'percent' : key === 'studentLoanBracket' ? 'bracket' : 'months']
      if (number !== null && typeof number !== 'number') throw invalid()
    }
  }
  const profile = { ...value, updatedAt: value.updatedAt ?? null } as unknown as Profile
  if (Object.keys(validateProfile(profile)).length) throw invalid()
  return profile
}
export function parseInterests(data: unknown): Interest[] {
  if (!isRecord(data) || !Array.isArray(data.interests) || data.interests.length > 30 || !data.interests.every((item: unknown) => isRecord(item) && ['CATEGORY', 'SUB_CATEGORY', 'DETAIL_CATEGORY', 'NCS'].includes(String(item.type)) && typeof item.code === 'string' && item.code.trim().length > 0 && item.code.length <= 100)) throw new ApiError('unknown', '관심 분야 응답 형식을 확인할 수 없어요.')
  const items = data.interests as Interest[]
  if (new Set(items.map(interestKey)).size !== items.length) throw new ApiError('unknown', '관심 분야가 중복된 응답이에요.')
  return items
}
export const interestKey = (item: Interest): string => JSON.stringify([item.type, item.code])
export async function getProfile(signal?: AbortSignal): Promise<Profile | null> {
  try { return parseProfile((await apiClient.get<unknown>(profilePath, { requiresAuth: true, signal })).data) }
  catch (error) { if (error instanceof ApiError && error.status === 404 && error.code === 'ELIGIBILITY_001') return null; throw error }
}
export async function getInterests(signal?: AbortSignal): Promise<Interest[]> {
  return parseInterests((await apiClient.get<unknown>(interestsPath, { requiresAuth: true, signal })).data)
}
export const profileQuery = (userId: number) => ({ queryKey: ['profile', userId] as const, queryFn: ({ signal }: { signal: AbortSignal }) => getProfile(signal), gcTime: 0 })
export const interestsQuery = (userId: number) => ({ queryKey: ['user-interests', userId] as const, queryFn: ({ signal }: { signal: AbortSignal }) => getInterests(signal), gcTime: 0 })
// 인증 확인 객체가 바뀌면 이전 화면의 저장 응답을 현재 사용자에게 반영하지 않는다.
export function assertOwner(client: QueryClient, owner: AuthUser, signal: AbortSignal): void {
  if (signal.aborted || client.isMutating({ mutationKey: authTransitionKey }) > 0 || client.getQueryState(authQueryKey)?.fetchStatus === 'fetching' || client.getQueryData(authQueryKey) !== owner) throw new ApiError('cancelled', '인증 상태가 변경되어 저장 결과 표시를 중단했어요.')
}
export async function refreshProfileDependents(client: QueryClient): Promise<void> {
  await Promise.all(['recommendations', 'opportunities', 'opportunity-detail'].map((root) => client.invalidateQueries({ queryKey: [root] })))
  await client.invalidateQueries({ queryKey: ['home', 'opportunities'] })
}
export async function saveProfile(client: QueryClient, owner: AuthUser, body: ProfileInput | Partial<ProfileInput>, creating: boolean, signal: AbortSignal): Promise<Profile> {
  assertOwner(client, owner, signal)
  const response = await apiClient.request<unknown>({ url: profilePath, method: creating ? 'PUT' : 'PATCH', data: body, requiresAuth: true, signal })
  assertOwner(client, owner, signal)
  const profile = parseProfile(response.data)
  client.setQueryData(profileQuery(owner.id).queryKey, profile)
  await refreshProfileDependents(client)
  return profile
}
export async function saveInterests(client: QueryClient, owner: AuthUser, interests: Interest[], signal: AbortSignal): Promise<Interest[]> {
  assertOwner(client, owner, signal)
  parseInterests({ interests })
  const data = parseInterests((await apiClient.put<unknown>(interestsPath, { interests }, { requiresAuth: true, signal })).data)
  assertOwner(client, owner, signal)
  client.setQueryData(interestsQuery(owner.id).queryKey, data)
  await refreshProfileDependents(client)
  return data
}
