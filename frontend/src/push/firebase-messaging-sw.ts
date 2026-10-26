/// <reference lib="webworker" />
import { initializeApp } from '@firebase/app'
import { getMessaging, onBackgroundMessage } from '@firebase/messaging/sw'
import { readPushConfig } from './config'
import { pushTarget, pushClickPath } from './protocol'

const worker = self as unknown as ServiceWorkerGlobalScope
worker.addEventListener('install', event => { event.waitUntil(worker.skipWaiting()) })
// SDK의 클릭 리스너보다 먼저 등록한다. SW에서는 인증·CSRF API를 호출하지 않는다.
worker.addEventListener('notificationclick', event => {
  const raw: unknown = event.notification.data
  if (!raw || typeof raw !== 'object' || !('FCM_MSG' in raw)) return
  event.stopImmediatePropagation(); event.notification.close()
  event.waitUntil((async () => {
    const target = pushTarget(raw.FCM_MSG, worker.location.origin)
    const url = new URL(target ? pushClickPath(target) : '/notifications', worker.location.origin).href
    const clients = await worker.clients.matchAll({ type: 'window', includeUncontrolled: true })
    const client = clients.find(client => new URL(client.url).origin === worker.location.origin)
    if (client) { await client.navigate(url); await client.focus() } else await worker.clients.openWindow(url)
  })())
})
const config = readPushConfig(import.meta.env)
if (config) {
  const messaging = getMessaging(initializeApp(config))
  // Backend WebpushConfig.notification은 SDK가 자동 표시한다. 여기서 showNotification을 다시 호출하지 않는다.
  onBackgroundMessage(messaging, () => {})
}
