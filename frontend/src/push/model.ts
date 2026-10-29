import type { NotificationSettings } from '../api/notifications.ts'
import type { PushEndpoint } from '../api/push.ts'
import type { PushStore } from './storage.ts'
import { ApiError } from '../api/ApiError.ts'

export class PushError extends Error {
  constructor(message: string) { super(message); this.name = 'PushError' }
}
export type PushDependencies = {
  ready: boolean; supported: boolean; permission: () => NotificationPermission; requestPermission: () => Promise<NotificationPermission>
  lock: <T>(work: () => Promise<T>) => Promise<T>; assertOwner: () => void
  load: () => PushStore; save: (store: PushStore) => void
  token: () => Promise<string>; removeToken: () => Promise<void>
  settings: () => Promise<NotificationSettings>; update: (settings: { inAppEnabled: boolean; pushEnabled: boolean }) => Promise<NotificationSettings>
  register: (token: string) => Promise<PushEndpoint>; deactivate: (id: number) => Promise<void>
}
export function createPushController(userId: number, deps: PushDependencies) {
  let pending: { kind: string; promise: Promise<void> } | undefined
  function run(kind: string, work: () => Promise<void>): Promise<void> {
    if (pending) return pending.kind === kind ? pending.promise : Promise.reject(new PushError('다른 푸시 알림 작업을 처리하고 있어요.'))
    const promise = work().finally(() => { if (pending?.promise === promise) pending = undefined })
    pending = { kind, promise }; return promise
  }
  function enable(request = true): Promise<void> {
    return run('enable', async () => {
      if (!deps.ready) throw new PushError('지금은 푸시 알림을 사용할 수 없어요.')
      if (!deps.supported) throw new PushError('이 브라우저에서는 푸시 알림을 사용할 수 없어요.')
      deps.assertOwner()
      // 권한 요청은 사용자 클릭의 동기 실행 구간에서 시작한다. 자동 재연결은 요청하지 않는다.
      const permission = deps.permission() === 'granted' ? Promise.resolve('granted') : request ? deps.requestPermission() : Promise.resolve(deps.permission())
      if (await permission !== 'granted') throw new PushError('알림 권한이 허용되지 않았어요. 브라우저 설정에서 확인해 주세요.')
      await deps.lock(async () => {
        deps.assertOwner()
        const store = deps.load()
        if (store.binding !== null && store.binding !== userId) {
          await deps.removeToken()
          if (store.users[store.binding]) store.users[store.binding].phase = 'cleanup'
          store.binding = null; deps.save(store)
        }
        const settings = await deps.settings(); deps.assertOwner()
        if (!request && !settings.pushEnabled) {
          await deps.removeToken()
          if (store.users[userId]) store.users[userId].phase = 'cleanup'
          store.binding = null; deps.save(store); return
        }
        const record = store.users[userId] ?? { endpointIds: [], phase: 'pending' as const }
        record.phase = 'pending'; store.users[userId] = record; store.binding = userId; deps.save(store)
        const token = await deps.token(); deps.assertOwner()
        let endpoint
        try { endpoint = await deps.register(token) } catch (error) {
          if (!(error instanceof ApiError) || error.status !== 409) throw error
          // 메타데이터 유실 후 다른 계정에 묶인 토큰인 경우 새 브라우저 토큰으로 한 번만 복구한다.
          // 이전 계정의 임의 ID로 DELETE하지 않는다.
          deps.assertOwner(); await deps.removeToken(); deps.assertOwner()
          const renewed = await deps.token(); deps.assertOwner()
          endpoint = await deps.register(renewed)
        }
        // 등록 응답 직후 계정이 바뀌어도 원래 소유자의 ID를 남겨 잘못 재사용하지 않는다.
        if (!record.endpointIds.includes(endpoint.id)) record.endpointIds.push(endpoint.id)
        store.binding = userId; deps.save(store); deps.assertOwner()
        for (const old of [...record.endpointIds]) if (old !== endpoint.id) {
          await deps.deactivate(old); deps.assertOwner()
          record.endpointIds = record.endpointIds.filter(id => id !== old); deps.save(store)
        }
        // 자동 재연결은 endpoint만 갱신한다. 사용자가 그 사이 저장한 설정을 덮어쓰지 않는다.
        if (request) {
          const updated = await deps.update({ inAppEnabled: settings.inAppEnabled, pushEnabled: true }); deps.assertOwner()
          if (!updated.pushEnabled) throw new PushError('푸시 알림 켜기를 확인하지 못했어요.')
        }
        record.phase = 'active'; deps.save(store)
      })
    })
  }
  function disable(allDevices = false): Promise<void> {
    return run(allDevices ? 'disable-all' : 'detach', () => deps.lock(async () => {
      deps.assertOwner()
      const store = deps.load(), record = store.users[userId]
      if (record) { record.phase = 'cleanup'; deps.save(store) }
      if (allDevices) {
        const settings = await deps.settings(); deps.assertOwner()
        const updated = await deps.update({ inAppEnabled: settings.inAppEnabled, pushEnabled: false }); deps.assertOwner()
        if (updated.pushEnabled) throw new PushError('모든 기기의 푸시 끄기를 확인하지 못했어요.')
      }
      let failed = false
      if (record) for (const id of [...record.endpointIds]) {
        deps.assertOwner()
        try { await deps.deactivate(id); record.endpointIds = record.endpointIds.filter(value => value !== id); deps.save(store) } catch { failed = true }
      }
      deps.assertOwner()
      if (store.binding === userId || store.binding === null) { await deps.removeToken(); store.binding = null; deps.save(store) }
      if (failed) throw new PushError('이 브라우저 알림은 껐지만 해제가 모두 끝났는지 확인하지 못했어요. 다시 해제해 주세요.')
      if (record) { delete store.users[userId]; deps.save(store) }
    }))
  }
  return { enable, disable }
}
