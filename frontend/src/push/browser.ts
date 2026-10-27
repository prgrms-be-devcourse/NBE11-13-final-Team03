import { initializeApp, getApps } from '@firebase/app'
import { getMessaging, getToken, deleteToken, isSupported, onMessage } from '@firebase/messaging'
import type { Messaging } from '@firebase/messaging'
import type { QueryClient } from '@tanstack/react-query'
import type { AuthUser } from '../api/auth.ts'
import { getNotificationSettings, putNotificationSettings, assertNotificationOwner } from '../api/notifications.ts'
import { registerPushEndpoint, deactivatePushEndpoint } from '../api/push.ts'
import { readPushConfig, supportsPush } from './config.ts'
import { createPushController, PushError } from './model.ts'
import { loadPushStore, savePushStore } from './storage.ts'
import { hasBrowserPush, pushLock, pushScopeSuffix, revokeBrowserPush } from './lifecycle.ts'
import workerUrl from './firebase-messaging-sw.ts?worker&url'

const config = readPushConfig(import.meta.env)
let registration: ServiceWorkerRegistration | undefined
let tokenClient: Messaging | undefined
function messaging() {
  if (!config) throw new PushError('지금은 푸시 알림을 사용할 수 없어요.')
  const app = getApps().find(app => app.name === 'yon-push') ?? initializeApp(config, 'yon-push')
  return getMessaging(app)
}
export async function inspectPush(userId: number) {
  const basicSupport = supportsPush({ secure: window.isSecureContext, notification: 'Notification' in window, serviceWorker: 'serviceWorker' in navigator, pushManager: 'PushManager' in window, locks: 'locks' in navigator })
  const store = loadPushStore()
  const permission = 'Notification' in window ? Notification.permission : 'denied' as NotificationPermission
  // 설정이 없을 때는 SDK의 IndexedDB 지원 검사나 서비스 워커 조회를 기다리지 않는다.
  if (!config) return { ready: false, supported: basicSupport, permission, connected: false, phase: store.users[userId]?.phase ?? null, knownEndpoints: store.users[userId]?.endpointIds.length ?? 0 }
  const supported = basicSupport && await isSupported().catch(() => false)
  const subscribed = supported && await hasBrowserPush()
  return { ready: true, supported, permission,
    connected: subscribed && store.binding === userId && store.users[userId]?.phase === 'active', phase: store.users[userId]?.phase ?? null,
    knownEndpoints: store.users[userId]?.endpointIds.length ?? 0 }
}
async function token(): Promise<string> {
  if (!config || Notification.permission !== 'granted') throw new PushError('알림 권한을 먼저 허용해 주세요.')
  const url = new URL(workerUrl, location.href)
  const scope = new URL(`.${pushScopeSuffix}`, url).pathname
  const existing = await navigator.serviceWorker.getRegistration(new URL(scope, location.origin))
  if (existing && !new URL(existing.scope).pathname.endsWith(pushScopeSuffix)) throw new PushError('이 브라우저에 푸시 알림을 연결하지 못했어요. 새로고침한 뒤 다시 시도해 주세요.')
  registration = await navigator.serviceWorker.register(url, { scope }).catch(() => { throw new PushError('이 브라우저에 푸시 알림을 연결하지 못했어요. 잠시 후 다시 시도해 주세요.') })
  if (!registration.active) await new Promise<void>((resolve, reject) => {
    const worker = registration?.installing ?? registration?.waiting
    if (!worker) { reject(new PushError('이 브라우저에 푸시 알림을 연결하지 못했어요. 잠시 후 다시 시도해 주세요.')); return }
    if (worker.state === 'activated') { resolve(); return }
    const changed = () => {
      if (worker.state === 'activated') { worker.removeEventListener('statechange', changed); resolve() }
      if (worker.state === 'redundant') { worker.removeEventListener('statechange', changed); reject(new PushError('이 브라우저에 푸시 알림을 연결하지 못했어요. 잠시 후 다시 시도해 주세요.')) }
    }
    worker.addEventListener('statechange', changed)
  })
  try {
    tokenClient = messaging()
    return await getToken(tokenClient, { vapidKey: config.vapidKey, serviceWorkerRegistration: registration })
  } catch { throw new PushError('푸시 알림 연결에 실패했어요. 인터넷 연결을 확인하고 다시 시도해 주세요.') }
}
async function removeToken() {
  // 새로고침·설정 누락 시에도 전용 registration의 실제 브라우저 구독부터 해제한다.
  await revokeBrowserPush()
  if (tokenClient) { try { await deleteToken(tokenClient) } catch { /* 구독 폐기로 이전 토큰의 실제 수신은 차단됐다. SDK 메타데이터는 다음 getToken에서 정리한다. */ } }
  tokenClient = undefined
}
const controllers = new WeakMap<QueryClient, { owner: AuthUser; controller: ReturnType<typeof createPushController> }>()
export async function browserPushController(client: QueryClient, owner: AuthUser) {
  const existing = controllers.get(client)
  if (existing?.owner === owner) return existing.controller
  const status = await inspectPush(owner.id)
  const controller = createPushController(owner.id, {
    ready: status.ready, supported: status.supported, permission: () => Notification.permission, requestPermission: () => Notification.requestPermission(),
    lock: pushLock, assertOwner: () => assertNotificationOwner(client, owner), load: loadPushStore, save: savePushStore,
    token, removeToken, settings: getNotificationSettings, update: putNotificationSettings, register: registerPushEndpoint, deactivate: deactivatePushEndpoint,
  })
  controllers.set(client, { owner, controller }); return controller
}
export async function observePush(userId: number, receive: () => void): Promise<() => void> {
  const status = await inspectPush(userId)
  if (!status.ready || !status.supported || status.permission !== 'granted' || !status.connected) return () => {}
  const seen = new Set<string>()
  return onMessage(messaging(), payload => {
    const store = loadPushStore()
    const messageId = payload.data?.messageId ?? payload.messageId
    if (messageId && seen.has(messageId)) return
    if (messageId) { seen.add(messageId); if (seen.size > 100) seen.delete(seen.values().next().value!) }
    if (store.binding === userId && store.users[userId]?.phase === 'active') receive()
  })
}
