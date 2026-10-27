import { PushError } from './model.ts'
import { loadPushStore, savePushStore } from './storage.ts'
import { deactivatePushEndpoint } from '../api/push.ts'

export const pushScopeSuffix = '/yon-fcm-push/'
export async function pushLock<T>(work: () => Promise<T>): Promise<T> {
  if (typeof navigator === 'undefined' || !navigator.locks) throw new PushError('이 브라우저에서는 푸시 알림을 사용할 수 없어요.')
  return navigator.locks.request('yon.push.browser.v1', { ifAvailable: true }, async lock => {
    if (!lock) throw new PushError('다른 탭에서 푸시 알림을 처리하고 있어요. 잠시 후 다시 시도해 주세요.')
    return work()
  })
}
export async function hasBrowserPush(): Promise<boolean> {
  if (typeof navigator === 'undefined' || !navigator.serviceWorker) return false
  const registrations = await navigator.serviceWorker.getRegistrations()
  return (await Promise.all(registrations.filter(value => new URL(value.scope).pathname.endsWith(pushScopeSuffix)).map(value => value.pushManager.getSubscription()))).some(Boolean)
}
export async function revokeBrowserPush(): Promise<void> {
  if (typeof navigator === 'undefined' || !navigator.serviceWorker) return
  const registrations = await navigator.serviceWorker.getRegistrations()
  for (const registration of registrations.filter(value => new URL(value.scope).pathname.endsWith(pushScopeSuffix))) {
    const subscription = await registration.pushManager.getSubscription()
    if (subscription && !await subscription.unsubscribe()) throw new PushError('이 브라우저의 푸시 알림 해제를 확인하지 못했어요. 다시 시도해 주세요.')
    for (const notification of await registration.getNotifications()) notification.close()
  }
}
// 등록은 Web Locks 지원 환경에서만 가능하다. 해제는 미지원 환경에서도 기존 구독을 정리한다.
async function cleanupPush(work: () => Promise<void>): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.locks) await pushLock(work)
  else await work()
}
/** 새 쿠키로 이전 계정의 DELETE를 시도하지 않는다. 로컬 수신을 끊고 미정리 ID는 원래 소유자에게 남긴다. */
export async function changePushIdentity(userId: number | null): Promise<void> {
  let initial
  try { initial = loadPushStore() } catch { await cleanupPush(revokeBrowserPush); return }
  if (initial.binding !== null && initial.binding === userId) return
  // 소유 정보도 전용 구독도 없으면 정리할 것이 없다. 락 없이 확인해 매 인증 확인마다 다른 탭 작업과 경합하지 않는다.
  if (initial.binding === null && !await hasBrowserPush()) return
  await cleanupPush(async () => {
    let store
    try { store = loadPushStore() } catch { await revokeBrowserPush(); return }
    if (store.binding !== null && store.binding === userId) return
    await revokeBrowserPush()
    // 소유 정보가 없으면 임의 endpoint ID로 서버 DELETE를 하지 않는다.
    if (store.binding !== null) {
      if (store.users[store.binding]) store.users[store.binding].phase = 'cleanup'
      store.binding = null; savePushStore(store)
    }
  })
}
/** HttpOnly 쿠키가 살아 있는 동안 알려진 본인 endpoint만 해제한다. 사용자 전체 설정은 바꾸지 않는다. */
export async function releasePushSession(userId: number): Promise<void> {
  await cleanupPush(async () => {
    let store
    try { store = loadPushStore() } catch { await revokeBrowserPush(); return }
    const record = store.users[userId]
    try {
      if (record) { record.phase = 'cleanup'; savePushStore(store) }
      if (record) for (const id of [...record.endpointIds]) {
        try { await deactivatePushEndpoint(id); record.endpointIds = record.endpointIds.filter(value => value !== id); savePushStore(store) } catch { /* 서버 재시도에 필요한 ID는 보존한다. 로컬 구독은 아래에서 반드시 해제한다. */ }
      }
    } finally {
      // 서버 해제 실패여도 기존 토큰이 다음 계정에 연결되지 않도록 로컬 PushSubscription을 폐기한다.
      await revokeBrowserPush()
      if (store.binding !== null) {
        if (store.binding !== userId && store.users[store.binding]) store.users[store.binding].phase = 'cleanup'
        store.binding = null; savePushStore(store)
      }
    }
    if (record && !record.endpointIds.length) { delete store.users[userId]; savePushStore(store) }
  })
}
