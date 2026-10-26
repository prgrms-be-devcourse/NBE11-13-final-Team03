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
export async function revokeBrowserPush(): Promise<void> {
  if (typeof navigator === 'undefined' || !navigator.serviceWorker) return
  const registrations = await navigator.serviceWorker.getRegistrations()
  for (const registration of registrations.filter(value => new URL(value.scope).pathname.endsWith(pushScopeSuffix))) {
    const subscription = await registration.pushManager.getSubscription()
    if (subscription && !await subscription.unsubscribe()) throw new PushError('이 브라우저의 푸시 알림 해제를 확인하지 못했어요. 다시 시도해 주세요.')
    for (const notification of await registration.getNotifications()) notification.close()
  }
}
/** 새 쿠키로 이전 계정의 DELETE를 시도하지 않는다. 로컬 수신을 끊고 미정리 ID는 원래 소유자에게 남긴다. */
export async function changePushIdentity(userId: number | null): Promise<void> {
  let initial
  try { initial = loadPushStore() } catch { await revokeBrowserPush(); return }
  if (initial.binding === null || initial.binding === userId) return
  await pushLock(async () => {
    const store = loadPushStore()
    if (store.binding === null || store.binding === userId) return
    await revokeBrowserPush()
    if (store.users[store.binding]) store.users[store.binding].phase = 'cleanup'
    store.binding = null; savePushStore(store)
  })
}
/** HttpOnly 쿠키가 살아 있는 동안 알려진 본인 endpoint만 해제한다. 사용자 전체 설정은 바꾸지 않는다. */
export async function releasePushSession(userId: number): Promise<void> {
  let initial
  try { initial = loadPushStore() } catch { await revokeBrowserPush(); return }
  if (!initial.users[userId] && initial.binding === null) return
  await pushLock(async () => {
    const store = loadPushStore(), record = store.users[userId]
    if (record) { record.phase = 'cleanup'; savePushStore(store) }
    try {
      if (record) for (const id of [...record.endpointIds]) {
        try { await deactivatePushEndpoint(id); record.endpointIds = record.endpointIds.filter(value => value !== id); savePushStore(store) } catch { /* 서버 재시도에 필요한 ID는 보존한다. 로컬 구독은 아래에서 반드시 해제한다. */ }
      }
    } finally {
      // 서버 해제 실패여도 기존 토큰이 다음 계정에 연결되지 않도록 로컬 PushSubscription을 폐기한다.
      if (store.binding === userId) { await revokeBrowserPush(); store.binding = null; savePushStore(store) }
    }
    if (record && !record.endpointIds.length) { delete store.users[userId]; savePushStore(store) }
  })
}
