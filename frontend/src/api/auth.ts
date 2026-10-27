import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'

export type AuthUser = { id: number; nickname?: string | null; email?: string | null; role: 'USER' | 'ADMIN' }
export function isAuthUser(value: unknown): value is AuthUser {
  if (!value || typeof value !== 'object') return false
  return 'id' in value && typeof value.id === 'number' && Number.isSafeInteger(value.id) && value.id > 0 &&
    'role' in value && (value.role === 'USER' || value.role === 'ADMIN') &&
    (!('nickname' in value) || value.nickname == null || typeof value.nickname === 'string') &&
    (!('email' in value) || value.email == null || typeof value.email === 'string')
}
async function requestUser(requiresAuth: boolean, signal?: AbortSignal): Promise<AuthUser> {
  const response = await apiClient.get<unknown>('/users/me', { requiresAuth, signal })
  if (response.status !== 200 || !isAuthUser(response.data)) throw new ApiError('unknown', '사용자 정보 응답을 확인할 수 없습니다.')
  return response.data
}
export async function getCurrentUser(signal?: AbortSignal): Promise<AuthUser | null> {
  try { return await requestUser(true, signal) }
  catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}
export function verifyOAuthUser(signal?: AbortSignal): Promise<AuthUser> {
  // OAuth 직후의 새 쿠키를 직접 검증한다. 과거 재발급 실패에 막히거나 성공을 추측해 먼저 초기화하지 않는다.
  // requiresAuth는 서버 인증 여부가 아니라 자동 재발급 여부다. 이 확인 요청만 재발급을 하지 않는다.
  return requestUser(false, signal)
}
export async function postLogout(): Promise<void> {
  const response = await apiClient.post('/auth/logout', undefined, { requiresAuth: true })
  if (response.status !== 204) throw new ApiError('unknown', '로그아웃 완료 응답을 확인할 수 없습니다.')
}
/** 서버가 회원 정보를 삭제하고 인증 쿠키를 만료한다. 204만 탈퇴 완료로 본다. */
export async function deleteCurrentUser(): Promise<void> {
  const response = await apiClient.delete('/users/me', { requiresAuth: true })
  if (response.status !== 204) throw new ApiError('unknown', '탈퇴 완료 응답을 확인할 수 없습니다.')
}
