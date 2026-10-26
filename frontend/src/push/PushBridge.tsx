import { useEffect, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { authQueryKey } from '../auth/session'
import { assertNotificationOwner, notificationKeys } from '../api/notifications'
import { loadPushStore, parsePushStore, pushStorageKey } from './storage'

export default function PushBridge() {
  const auth = useAuth(), client = useQueryClient()
  const [notice, setNotice] = useState<number | null>(null)
  const owner = auth.status === 'authenticated' ? auth.user : undefined
  useEffect(() => {
    if (!owner) return
    let cancelled = false, version = 0, stop = () => {}
    async function connect() {
      const current = ++version
      stop(); stop = () => {}
      try {
        const runtime = await import('./browser')
        if (cancelled || version !== current) return
        assertNotificationOwner(client, owner!)
        const status = await runtime.inspectPush(owner!.id)
        if (!status.ready || !status.supported) return
        const store = loadPushStore()
        if (store.binding === owner!.id && store.users[owner!.id]?.phase === 'active') {
          const controller = await runtime.browserPushController(client, owner!)
          if (status.permission === 'granted') await controller.enable(false)
          else await controller.disable()
        }
        if (cancelled || version !== current) return
        const unsubscribe = await runtime.observePush(owner!.id, () => {
          if (cancelled) return
          try { assertNotificationOwner(client, owner!); setNotice(owner!.id) } catch { return }
          void client.invalidateQueries({ queryKey: notificationKeys.unread(owner!.id) })
          void client.invalidateQueries({ queryKey: notificationKeys.list(owner!.id) })
        })
        if (cancelled || version !== current) unsubscribe(); else stop = unsubscribe
        void client.invalidateQueries({ queryKey: ['notifications', owner!.id, 'push-browser'] })
      } catch { /* 권한을 자동 요청하지 않는다. 부분 실패는 설정 화면에서 확인·복구한다. */ }
    }
    const changed = (event: StorageEvent) => {
      if (event.key !== pushStorageKey) return
      try {
        if (parsePushStore(event.oldValue).binding !== parsePushStore(event.newValue).binding) void client.invalidateQueries({ queryKey: authQueryKey })
      } catch { void client.invalidateQueries({ queryKey: authQueryKey }) }
      void client.invalidateQueries({ queryKey: ['notifications', owner.id, 'push-browser'] })
    }
    const focused = () => { void connect() }
    void connect()
    window.addEventListener('focus', focused); window.addEventListener('storage', changed)
    return () => { cancelled = true; stop(); window.removeEventListener('focus', focused); window.removeEventListener('storage', changed) }
  }, [client, owner])
  return owner && notice === owner.id ? <aside role="status"><Link to="/notifications" onClick={() => setNotice(null)}>새 알림이 도착했어요. 알림 목록 확인하기</Link></aside> : null
}
