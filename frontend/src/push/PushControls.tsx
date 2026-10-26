import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef } from 'react'
import type { AuthUser } from '../api/auth'
import type { NotificationSettings } from '../api/notifications'
import { notificationError, notificationKeys, notificationMutationKey } from '../api/notifications'
import { PushError } from './model'
import Button from '../components/ui/Button'

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
  return <section aria-labelledby="web-push-title">
    <h2 id="web-push-title">웹 푸시</h2>
    <p>사용자 전체 푸시 설정: {settings.pushEnabled ? '켜짐' : '꺼짐'}</p>
    <p>이 브라우저: {browser.data?.connected && settings.pushEnabled && browser.data.permission === 'granted' ? '연결됨' : '연결 완료 미확인'}</p>
    {browser.isPending && <p role="status">브라우저 지원·연결 상태를 확인하고 있어요.</p>}
    {browser.isError && <p role="alert">브라우저 구독 정보를 읽지 못했어요. 저장소·권한 설정을 확인해 주세요.</p>}
    {browser.data && !browser.data.ready && <p id="push-config-help">Firebase 웹 앱 설정과 Web Push VAPID 공개 키가 필요해요. .env.example의 VITE_FIREBASE_* 항목을 설정하고 다시 빌드해 주세요.</p>}
    {browser.data && !browser.data.supported && <p>HTTPS 또는 localhost에서 Push API·서비스 워커·Web Locks를 지원하는 브라우저가 필요해요. iOS는 지원 버전의 홈 화면 웹 앱에서 확인해 주세요.</p>}
    {browser.data?.permission === 'denied' && <p>알림 권한이 거부됐어요. 브라우저 설정에서 변경한 뒤 다시 확인해 주세요.</p>}
    {browser.data?.phase === 'pending' && <p role="status">일부 등록이 진행됐지만 전체 완료를 확인하지 못했어요. 다시 연결하거나 이 브라우저를 해제해 주세요.</p>}
    {browser.data?.phase === 'cleanup' && <p role="status">이전에 등록한 서버 엔드포인트 정리가 남아 있어요. 이 브라우저 해제를 다시 시도해 주세요.</p>}
    <p>이 브라우저 해제는 다른 기기를 끄지 않아요. 모든 기기 푸시 끄기는 사용자 전체 설정을 끄며 현재 브라우저의 알려진 등록도 해제해요. 브라우저 권한 자체는 브라우저 설정에서 관리해 주세요.</p>
    <Button disabled={busy || !browser.data?.ready || !browser.data.supported || browser.data.permission === 'denied'} onClick={() => start('enable')}>이 브라우저 푸시 연결·재확인</Button>{' '}
    <Button variant="secondary" disabled={busy || !browser.data} onClick={() => start('detach')}>이 브라우저 해제</Button>{' '}
    <Button variant="secondary" disabled={busy || !browser.data || !settings.pushEnabled} onClick={() => start('off')}>모든 기기 푸시 끄기</Button>{' '}
    <Button variant="ghost" disabled={pending} onClick={() => { void browser.refetch() }}>브라우저 상태 다시 확인</Button>
    {mutation.isPending && <p role="status">푸시 설정을 처리하고 있어요.</p>}
    {mutation.isSuccess && <p role="status">{mutation.variables.kind === 'enable' ? '등록과 사용자 설정 저장을 확인했어요.' : '해제 처리를 확인했어요.'}</p>}
    {mutation.isError && <p role="alert">{mutation.error instanceof PushError ? mutation.error.message : notificationError(mutation.error)} 서버 등록·설정이 일부 반영됐을 수 있어요. 상태를 확인하고 다시 시도해 주세요.</p>}
    <p>등록 ID가 없는 서버 엔드포인트는 조회 API가 없어 정확히 해제할 수 없어요. 임의 ID를 사용하지 않아요.</p>
  </section>
}
