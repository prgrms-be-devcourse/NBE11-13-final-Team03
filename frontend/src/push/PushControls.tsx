import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import type { AuthUser } from '../api/auth'
import type { NotificationSettings } from '../api/notifications'
import { notificationError, notificationKeys, notificationMutationKey } from '../api/notifications'
import { PushError } from './model'
import Button from '../components/ui/Button'
import { formatDateTime } from '../utils/date'
import styles from './PushControls.module.css'

export default function PushControls({ owner, settings, disabled }: { owner: AuthUser; settings: NotificationSettings; disabled: boolean }) {
  const running = useRef(false)
  const client = useQueryClient(), key = ['notifications', owner.id, 'push-browser'] as const
  const browser = useQuery({ queryKey: key, gcTime: 0, retry: false, queryFn: async ({ signal }) => {
    const runtime = await import('./browser')
    const status = await runtime.inspectPush(owner.id), controller = await runtime.browserPushController(client, owner)
    if (signal.aborted) throw new PushError('확인이 취소됐어요.')
    return { ...status, controller }
  } })
  const pending = useIsMutating({ mutationKey: notificationMutationKey(owner.id) }) > 0
  const mutation = useMutation({ mutationKey: notificationMutationKey(owner.id), mutationFn: async ({ task }: { kind: 'enable' | 'detach' | 'off'; task: Promise<void> }) => {
    try {
      await task
    } finally {
      try {
        await client.invalidateQueries({ queryKey: key })
        await client.invalidateQueries({ queryKey: notificationKeys.settings(owner.id) })
      } finally { running.current = false }
    }
  } })
  const busy = pending || disabled || browser.isFetching
  function start(kind: 'enable' | 'detach' | 'off') {
    if (busy || running.current || !browser.data) return
    running.current = true
    // Permission prompt는 mutation의 비동기 스케줄링 전, 직접 사용자 동작에서 시작한다.
    const task = kind === 'enable' ? browser.data.controller.enable() : browser.data.controller.disable(kind === 'off')
    mutation.mutate({ kind, task })
  }
  return <section className={styles.section} aria-labelledby="web-push-title">
    <h2 id="web-push-title">브라우저 푸시 알림</h2>
    <div className={styles.status}><p>푸시 알림: {settings.pushEnabled ? '켜짐' : '꺼짐'}</p>
    <p>이 브라우저: {browser.data?.connected && settings.pushEnabled && browser.data.permission === 'granted' ? '연결됨' : '연결 확인 안 됨'}</p></div>
    {settings.pushConsentAt && <p className={styles.note}>푸시 알림 동의: <time dateTime={settings.pushConsentAt}>{formatDateTime(settings.pushConsentAt)}</time></p>}
    {browser.isPending && <p role="status">이 브라우저의 연결 상태를 확인하고 있어요.</p>}
    {browser.isError && <p role="alert">이 브라우저의 연결 상태를 확인하지 못했어요. 다시 확인해 주세요.</p>}
    {browser.data && !browser.data.ready && <p>지금은 푸시 알림을 사용할 수 없어요.</p>}
    {browser.data && !browser.data.supported && <p>이 브라우저에서는 푸시 알림을 사용할 수 없어요. iPhone·iPad는 홈 화면에 추가한 Y:ON에서 사용할 수 있어요.</p>}
    {browser.data?.permission === 'denied' && <p>알림 권한이 차단되어 있어요. 브라우저 설정에서 알림을 허용한 뒤 다시 확인해 주세요.</p>}
    {browser.data?.phase === 'pending' && <p role="status">연결이 끝나지 않았어요. 다시 연결하거나 이 브라우저를 해제해 주세요.</p>}
    {browser.data?.phase === 'cleanup' && <p role="status">이전 연결 정리가 끝나지 않았어요. ‘이 브라우저 해제’를 다시 눌러 주세요.</p>}
    <div className={styles.actions}><Button disabled={busy || !browser.data?.ready || !browser.data.supported || browser.data.permission === 'denied'} onClick={() => start('enable')}>이 브라우저 연결</Button>
    <Button variant="secondary" disabled={busy || !browser.data} onClick={() => start('detach')}>이 브라우저 해제</Button>
    <Button variant="secondary" disabled={busy || !browser.data || !settings.pushEnabled} onClick={() => start('off')}>모든 기기 푸시 끄기</Button>
    <Button variant="ghost" disabled={pending} onClick={() => { void browser.refetch() }}>상태 다시 확인</Button></div>
    <p className={styles.note}>‘이 브라우저 해제’는 이 기기만, ‘모든 기기 푸시 끄기’는 모든 기기의 푸시 알림을 꺼요.</p>
    {mutation.isPending && <p role="status">처리하고 있어요.</p>}
    {mutation.isSuccess && <p role="status">{mutation.variables.kind === 'enable' ? '이 브라우저에 푸시 알림을 연결했어요.' : '푸시 알림을 해제했어요.'}</p>}
    {mutation.isError && <p role="alert">{mutation.error instanceof PushError ? mutation.error.message : notificationError(mutation.error)} 일부만 반영됐을 수 있으니 상태를 다시 확인해 주세요.</p>}
  </section>
}
