export type PushRecord = { endpointIds: number[]; phase: 'pending' | 'active' | 'cleanup' }
export type PushStore = { binding: number | null; users: Record<string, PushRecord> }
export const pushStorageKey = 'yon.push.browser.v1'
export const emptyPushStore = (): PushStore => ({ binding: null, users: {} })
export function parsePushStore(value: string | null): PushStore {
  if (!value) return emptyPushStore()
  const parsed: unknown = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || !('binding' in parsed) || !('users' in parsed) || !parsed.users || typeof parsed.users !== 'object' || Array.isArray(parsed.users)) throw new Error('Invalid push metadata')
  if (parsed.binding !== null && (typeof parsed.binding !== 'number' || !Number.isSafeInteger(parsed.binding) || parsed.binding < 1)) throw new Error('Invalid push owner')
  for (const [key, record] of Object.entries(parsed.users)) {
    if (!/^[1-9]\d*$/.test(key) || !record || typeof record !== 'object' || !('phase' in record) || !['pending', 'active', 'cleanup'].includes(String(record.phase)) || !('endpointIds' in record) || !Array.isArray(record.endpointIds) || record.endpointIds.some((id: unknown) => typeof id !== 'number' || !Number.isSafeInteger(id) || id < 1)) throw new Error('Invalid push metadata')
  }
  return parsed as PushStore
}
export function loadPushStore(): PushStore {
  if (typeof localStorage === 'undefined') return emptyPushStore()
  return parsePushStore(localStorage.getItem(pushStorageKey))
}
export function savePushStore(store: PushStore): void {
  // FCM 토큰·인증 쿠키는 보관하지 않는다. 실패하면 구독 성공을 주장하지 않는다.
  localStorage.setItem(pushStorageKey, JSON.stringify(store))
}
