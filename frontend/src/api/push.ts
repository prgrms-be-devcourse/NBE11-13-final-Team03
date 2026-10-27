import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
export type PushEndpoint = { id: number; active: boolean }
export async function registerPushEndpoint(fcmToken: string): Promise<PushEndpoint> {
  if (!/^[!-~]{1,2048}$/.test(fcmToken)) throw new ApiError('configuration', 'FCM 등록 토큰을 확인할 수 없어요.')
  const { status, data } = await apiClient.post<unknown>('/users/me/notification-endpoints', { fcmToken }, { requiresAuth: true })
  if (status !== 200 || !data || typeof data !== 'object' || !('id' in data) || typeof data.id !== 'number' || !Number.isSafeInteger(data.id) || data.id < 1 || !('active' in data) || data.active !== true) throw new ApiError('unknown', '브라우저 등록 응답을 확인할 수 없어요.')
  return { id: data.id, active: true }
}
export async function deactivatePushEndpoint(id: number): Promise<void> {
  if (!Number.isSafeInteger(id) || id < 1) throw new ApiError('configuration', '브라우저 등록 번호를 확인해 주세요.')
  const response = await apiClient.delete(`/users/me/notification-endpoints/${id}`, { requiresAuth: true })
  if (response.status !== 204) throw new ApiError('unknown', '브라우저 해제 응답을 확인할 수 없어요.')
}
