export type PushClick = { notificationId: number; opportunityId: number }
export function positiveId(value: unknown): number | null {
  if ((typeof value !== 'string' && typeof value !== 'number') || !/^[1-9]\d*$/.test(String(value))) return null
  const number = Number(value)
  return Number.isSafeInteger(number) ? number : null
}
export function parsePushClick(search: string): PushClick | null {
  const params = new URLSearchParams(search), notificationId = positiveId(params.get('pushId')), opportunityId = positiveId(params.get('opportunityId'))
  return notificationId && opportunityId ? { notificationId, opportunityId } : null
}
export function pushClickPath(click: PushClick): string {
  return `/notifications?pushId=${click.notificationId}&opportunityId=${click.opportunityId}`
}
export const pushNavigateType = 'yon.push.navigate'
/** 워커가 보낸 이동 요청은 알림 목록 경로와 검증된 ID 형식만 허용한다. */
export function pushMessagePath(data: unknown): string | null {
  if (!data || typeof data !== 'object' || !('type' in data) || data.type !== pushNavigateType || !('path' in data) || typeof data.path !== 'string') return null
  if (data.path === '/notifications') return data.path
  const click = data.path.startsWith('/notifications?') ? parsePushClick(data.path.slice('/notifications'.length)) : null
  return click && pushClickPath(click) === data.path ? data.path : null
}
export function pushTarget(payload: unknown, origin: string): PushClick | null {
  if (!payload || typeof payload !== 'object' || !('data' in payload) || !payload.data || typeof payload.data !== 'object' || !('notificationId' in payload.data) || !('fcmOptions' in payload) || !payload.fcmOptions || typeof payload.fcmOptions !== 'object' || !('link' in payload.fcmOptions) || typeof payload.fcmOptions.link !== 'string') return null
  const notificationId = positiveId(payload.data.notificationId)
  if (!notificationId) return null
  try {
    const url = new URL(payload.fcmOptions.link, origin)
    if (url.origin !== origin || url.search || url.hash || url.username || url.password || !/^\/opportunities\/[1-9]\d*$/.test(url.pathname)) return null
    const opportunityId = positiveId(url.pathname.slice('/opportunities/'.length))
    return opportunityId ? { notificationId, opportunityId } : null
  } catch { return null }
}
