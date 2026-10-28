import { infiniteQueryOptions, type QueryClient } from '@tanstack/react-query'
import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { AuthUser } from './auth.ts'
import { authQueryKey, authTransitionKey } from '../auth/session.ts'
import { isOpportunityId } from './opportunityDetail.ts'

export const notificationLabels = {
  APPLICATION_OPENED: '신청 시작', APPLICATION_CLOSED: '신청 종료', DEADLINE_SOON: '마감 임박', DEADLINE_EXTENDED: '신청기간 연장',
  BECAME_ELIGIBLE: '신청 가능성 변경', ELIGIBILITY_DOWNGRADED: '신청 가능성 하락', ELIGIBILITY_REVIEW_REQUIRED: '신청 조건 재확인',
  NEW_MATCH: '새로운 추천',
} as const
export type NotificationItem = { id: number; opportunityId: number; type: keyof typeof notificationLabels; title: string; content: string; link: string; createdAt: string; readAt: string | null; clickedAt: string | null }
export type NotificationPage = { items: NotificationItem[]; nextCursor: number | null; hasNext: boolean }
export type UnreadCount = { available: true; unreadCount: number } | { available: false; unreadCount: null }
export type NotificationSettings = { inAppEnabled: boolean; pushEnabled: boolean; pushConsentAt: string | null; updatedAt: string | null }
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const id = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v > 0
const instant = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T.*Z$/.test(v) && Number.isFinite(Date.parse(v))
const nullableInstant = (v: unknown) => v === null || instant(v)
export function isNotificationPage(v: unknown): v is NotificationPage {
  if (!record(v) || !Array.isArray(v.items) || typeof v.hasNext !== 'boolean') return false
  let previous = Infinity
  for (const item of v.items) {
    if (!record(item) || !id(item.id) || item.id >= previous || !id(item.opportunityId) ||
      typeof item.type !== 'string' || !Object.hasOwn(notificationLabels, item.type) ||
      !['title', 'content', 'link'].every(key => typeof item[key] === 'string') ||
      !instant(item.createdAt) || !nullableInstant(item.readAt) || !nullableInstant(item.clickedAt)) return false
    previous = item.id
  }
  return v.hasNext ? v.items.length > 0 && v.nextCursor === previous : v.nextCursor === null
}
export function isUnreadCount(v: unknown): v is UnreadCount {
  return record(v) && (v.available === false ? v.unreadCount === null : v.available === true && typeof v.unreadCount === 'number' && Number.isSafeInteger(v.unreadCount) && v.unreadCount >= 0)
}
export function isNotificationSettings(v: unknown): v is NotificationSettings {
  return record(v) && typeof v.inAppEnabled === 'boolean' && typeof v.pushEnabled === 'boolean' && nullableInstant(v.pushConsentAt) && nullableInstant(v.updatedAt)
}
const malformed = () => new ApiError('unknown', '알림 응답 형식을 확인할 수 없어요.')
export async function getNotifications(beforeId: number | null = null, size = 20, signal?: AbortSignal): Promise<NotificationPage> {
  if ((beforeId !== null && !id(beforeId)) || !Number.isInteger(size) || size < 1 || size > 100) throw new ApiError('configuration', '알림 조회 조건을 확인해 주세요.')
  const response = await apiClient.get<unknown>('/notifications', { requiresAuth: true, signal, params: { ...(beforeId === null ? {} : { beforeId }), size } })
  if (response.status !== 200 || !isNotificationPage(response.data) || response.data.items.length > size ||
    (beforeId !== null && response.data.items.some(item => item.id >= beforeId))) throw malformed()
  return response.data
}
export async function getUnreadCount(signal?: AbortSignal): Promise<UnreadCount> {
  const response = await apiClient.get<unknown>('/notifications/unread-count', { requiresAuth: true, signal })
  if (response.status !== 200 || !isUnreadCount(response.data)) throw malformed()
  return response.data
}
export async function getNotificationSettings(signal?: AbortSignal): Promise<NotificationSettings> {
  const response = await apiClient.get<unknown>('/users/me/notification-settings', { requiresAuth: true, signal })
  if (response.status !== 200 || !isNotificationSettings(response.data)) throw malformed()
  return response.data
}
export async function putNotificationSettings(values: Pick<NotificationSettings, 'inAppEnabled' | 'pushEnabled'>): Promise<NotificationSettings> {
  if (typeof values.inAppEnabled !== 'boolean' || typeof values.pushEnabled !== 'boolean') throw new ApiError('configuration', '알림 설정값을 확인해 주세요.')
  const response = await apiClient.put<unknown>('/users/me/notification-settings', { inAppEnabled: values.inAppEnabled, pushEnabled: values.pushEnabled }, { requiresAuth: true })
  if (response.status !== 200 || !isNotificationSettings(response.data)) throw malformed()
  return response.data
}
async function changeNotification(action: 'read' | 'read-all' | 'click', notificationId?: number): Promise<void> {
  if (action !== 'read-all' && !id(notificationId)) throw new ApiError('configuration', '올바른 알림 번호가 아니에요.')
  const url = action === 'read-all' ? '/notifications/read-all' : `/notifications/${notificationId}/${action}`
  const response = await apiClient.request({ url, method: action === 'click' ? 'POST' : 'PATCH', requiresAuth: true })
  if (response.status !== 204) throw new ApiError('unknown', '알림 변경 완료 여부를 확인하지 못했어요. 최신 상태를 다시 조회해 주세요.')
}
export const readNotification = (notificationId: number) => changeNotification('read', notificationId)
export const readAllNotifications = () => changeNotification('read-all')
export const clickNotification = (notificationId: number) => changeNotification('click', notificationId)

export function notificationTarget(item: { type?: unknown; opportunityId: unknown; link: unknown }): string | null {
  // NEW_MATCH의 opportunityId는 요약의 대표 정책이므로 개별 상세 화면으로 이동하지 않는다.
  if (item.type === 'NEW_MATCH') return item.link === '/' ? '/' : null
  if (typeof item.opportunityId === 'number' && isOpportunityId(String(item.opportunityId))) return `/opportunities/${item.opportunityId}`
  // 서버 link는 상세 경로만 허용한다. 외부 주소·스킴·인증 경로로 이동하지 않는다.
  if (typeof item.link !== 'string' || !/^\/opportunities\/[1-9]\d*$/.test(item.link)) return null
  return isOpportunityId(item.link.slice('/opportunities/'.length)) ? item.link : null
}
export const notificationKeys = {
  list: (userId: number) => ['notifications', userId, 'list'] as const,
  unread: (userId: number) => ['notifications', userId, 'unread'] as const,
  settings: (userId: number) => ['notifications', userId, 'settings'] as const,
}
export function notificationsQuery(userId: number) {
  return infiniteQueryOptions({ queryKey: notificationKeys.list(userId), initialPageParam: null as number | null,
    queryFn: ({ pageParam, signal }) => getNotifications(pageParam, 20, signal),
    getNextPageParam: (last) => last.hasNext ? last.nextCursor : undefined, gcTime: 0 })
}
export const unreadCountQuery = (userId: number) => ({ queryKey: notificationKeys.unread(userId), queryFn: ({ signal }: { signal: AbortSignal }) => getUnreadCount(signal), staleTime: 30_000, gcTime: 0, refetchOnWindowFocus: false })
export const notificationSettingsQuery = (userId: number) => ({ queryKey: notificationKeys.settings(userId), queryFn: ({ signal }: { signal: AbortSignal }) => getNotificationSettings(signal), gcTime: 0 })
export const notificationMutationKey = (userId: number) => ['notification-change', userId] as const
export function assertNotificationOwner(client: QueryClient, owner: AuthUser): void {
  const state = client.getQueryState(authQueryKey)
  if (client.getQueryData(authQueryKey) !== owner || state?.status !== 'success' || state.fetchStatus !== 'idle' || client.isMutating({ mutationKey: authTransitionKey })) throw new ApiError('cancelled', '인증 상태가 변경되어 결과 처리를 중단했어요.')
}
export type NotificationAction = { kind: 'read' | 'open'; id: number } | { kind: 'read-all' } | { kind: 'settings'; values: Pick<NotificationSettings, 'inAppEnabled' | 'pushEnabled'> }
const pending = new WeakMap<QueryClient, { owner: AuthUser; key: string; promise: Promise<void> }>()
export function runNotificationChange(client: QueryClient, owner: AuthUser, action: NotificationAction): Promise<void> {
  try { assertNotificationOwner(client, owner) } catch (error) { return Promise.reject(error) }
  const key = JSON.stringify(action), previous = pending.get(client)
  if (previous?.owner === owner) return previous.key === key ? previous.promise : Promise.reject(new ApiError('configuration', '알림 변경을 처리 중이에요. 완료 후 다시 시도해 주세요.'))
  const predicate = (query: { queryKey: readonly unknown[] }) => query.queryKey[0] === 'notifications' && query.queryKey[1] === owner.id &&
    (action.kind === 'settings' ? query.queryKey[2] === 'settings' : ['list', 'unread'].includes(String(query.queryKey[2])))
  const promise = (async () => {
    await client.cancelQueries({ predicate })
    assertNotificationOwner(client, owner)
    // 요청 실패·응답 유실에도 서버 반영 가능성이 있어, 현재 계정의 해당 Query만 재확인한다.
    let failed = false
    let failure: unknown
    try {
      if (action.kind === 'settings') await putNotificationSettings(action.values)
      else if (action.kind === 'read-all') await readAllNotifications()
      else {
        await readNotification(action.id)
        assertNotificationOwner(client, owner)
        if (action.kind === 'open') await clickNotification(action.id)
      }
    } catch (error) { failed = true; failure = error }
    assertNotificationOwner(client, owner)
    try {
      await client.cancelQueries({ predicate })
      assertNotificationOwner(client, owner)
      await client.invalidateQueries({ predicate }, { throwOnError: true })
    } catch (error) { if (!failed) throw error }
    if (failed) throw failure
    assertNotificationOwner(client, owner)
  })().finally(() => { if (pending.get(client)?.promise === promise) pending.delete(client) })
  pending.set(client, { owner, key, promise })
  return promise
}

export function notificationError(error: Error): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인 상태를 다시 확인해 주세요.'
    if (error.status === 403 || error.kind === 'csrf') return '인증·보안 상태를 확인하고 다시 시도해 주세요.'
    if (error.status === 404) return '알림을 찾을 수 없거나 접근할 수 없어요. 목록을 다시 조회해 주세요.'
    if (error.status === 410) return '탈퇴한 사용자로 알림을 이용할 수 없어요.'
    if (error.status === 503) return '알림 서비스를 일시적으로 이용할 수 없어요.'
    if (error.kind === 'network' || error.kind === 'timeout') return '서버 응답을 확인하지 못했어요. 연결을 확인하고 최신 상태를 다시 조회해 주세요.'
    if (error.kind === 'cancelled') return '인증 상태가 변경되어 결과 확인을 중단했어요.'
  }
  return '알림 요청 또는 최신 상태 확인에 실패했어요. 다시 조회해 주세요.'
}
