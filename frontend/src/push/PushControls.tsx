import { useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { RefreshCw } from 'lucide-react'
import type { AuthUser } from '../api/auth'
import type { NotificationSettings } from '../api/notifications'
import { notificationError, notificationKeys, notificationMutationKey } from '../api/notifications'
import { PushError } from './model'
import Button from '../components/ui/Button'
import { formatDateTime } from '../utils/date'
import styles from './PushControls.module.css'

const successNoticeMs = 4000
type BrowserStatus ={ ready: boolean; supported: boolean; permission: NotificationPermission; connected: boolean; phase: 'pending' | 'active' | 'cleanup' | null; knownEndpoints: number }
type BrowserView = { label: string; tone: 'ok' | 'muted' | 'warn'; help?: string; main: 'enable' | 'detach' | 'refresh' | null }

// 확인한 상태만 보여 준다. 확인 중·확인 실패를 '연결 안 됨'으로 바꾸지 않고, 상태마다 가장 필요한 동작 하나를 main으로 둔다.
function browserView(isPending: boolean, data: BrowserStatus | undefined, pushEnabled: boolean): BrowserView {
  if (isPending) return { label: '확인하고 있어요', tone: 'muted', main: null }
  if (!data) return { label: '확인하지 못했어요', tone: 'warn', help: '이 브라우저의 연결 상태를 확인하지 못했어요. 다시 확인해 주세요.', main: 'refresh' }
  if (!data.ready) return { label: '사용할 수 없음', tone: 'muted', help: '지금은 푸시 알림을 사용할 수 없어요.', main: null }
  if (!data.supported) return { label: '지원하지 않는 브라우저', tone: 'muted', help: '이 브라우저에서는 푸시 알림을 사용할 수 없어요. iPhone·iPad는 홈 화면에 추가한 Y:ON에서 사용할 수 있어요.', main: null }
  if (data.phase === 'cleanup') return { label: '해제가 끝나지 않음', tone: 'warn', help: '이전 연결 정리가 끝나지 않았어요. ‘이 브라우저 해제’를 다시 눌러 주세요.', main: 'detach' }
  if (data.permission === 'denied') return { label: '알림 권한 차단됨', tone: 'warn', help: '알림 권한이 차단되어 있어요. 브라우저 설정에서 알림을 허용한 뒤 다시 확인해 주세요.', main: 'refresh' }
  if (data.phase === 'pending') return { label: '연결이 끝나지 않음', tone: 'warn', help: '연결이 끝나지 않았어요. 다시 연결하거나 이 브라우저를 해제해 주세요.', main: 'enable' }
  // 일반 설명은 카드 상단에 한 번만 둔다. 상태 아래에는 원인·조치가 필요한 안내만 보여 준다.
  if (data.connected && pushEnabled && data.permission === 'granted') return { label: '연결됨', tone: 'ok', main: 'detach' }
  if (data.connected) return { label: '다시 연결 필요', tone: 'warn', help: pushEnabled ? '알림 권한을 확인하지 못했어요. 다시 연결해 주세요.' : '계정의 푸시 알림이 꺼져 있어 이 브라우저로 받지 않아요. 다시 연결하면 켜져요.', main: 'enable' }
  return { label: '연결 안 됨', tone: 'muted', main: 'enable' }
}

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
  // 성공 결과는 바로 아래 상태값(연결됨·꺼짐)으로 계속 보이므로, 완료 문구는 직후 잠시만 보여 준다. 실패·진행 중 표시는 mutation 상태를 그대로 따른다.
  const [done, setDone] = useState<'enable' | 'detach' | 'off' | null>(null)
  useEffect(() => {
    if (!done) return
    const timer = setTimeout(() => setDone(null), successNoticeMs)
    return () => clearTimeout(timer)
  }, [done])
  function start(kind: 'enable' | 'detach' | 'off') {
    if (busy || running.current || !browser.data) return
    running.current = true
    setDone(null)
    // Permission prompt는 mutation의 비동기 스케줄링 전, 직접 사용자 동작에서 시작한다.
    const task = kind === 'enable' ? browser.data.controller.enable() : browser.data.controller.disable(kind === 'off')
    mutation.mutate({ kind, task }, { onSuccess: (_, variables) => setDone(variables.kind) })
  }
  const data = browser.data
  const view = browserView(browser.isPending, data, settings.pushEnabled)
  // 설정 누락·미지원·권한 차단에서는 연결할 수 없다는 것을 비활성 버튼과 안내로 함께 알린다.
  const blocked = !!data && (!data.ready || !data.supported || data.permission === 'denied')
  const showEnable = view.main === 'enable' || blocked
  const showDetach = !!data && (view.main === 'detach' || data.connected || data.phase !== null || data.knownEndpoints > 0)
  const enableButton = showEnable && <Button key="enable" disabled={busy || blocked} onClick={() => start('enable')}>이 브라우저 연결</Button>
  const detachButton = showDetach && <Button key="detach" variant={view.main === 'detach' ? 'secondary' : 'ghost'} disabled={busy} onClick={() => start('detach')}>이 브라우저 해제</Button>
  const refreshButton = <Button key="refresh" variant={view.main === 'refresh' ? 'secondary' : 'ghost'} disabled={pending || browser.isFetching} onClick={() => { void browser.refetch() }}><RefreshCw size={16} aria-hidden="true" />상태 다시 확인</Button>
  return <section className={styles.section} aria-labelledby="web-push-title">
    <div className={styles.head}><h2 id="web-push-title">브라우저 푸시 알림</h2>
      <p className={styles.help}>연결하면 Y:ON 화면을 보고 있지 않을 때도 이 브라우저로 새 소식을 받을 수 있어요.</p></div>
    <dl className={styles.status}>
      <div><dt>계정 푸시 알림</dt><dd data-tone={settings.pushEnabled ? 'ok' : 'muted'}>{settings.pushEnabled ? '켜짐' : '꺼짐'}
        {settings.pushConsentAt && <span className={styles.consent}>동의 <time dateTime={settings.pushConsentAt}>{formatDateTime(settings.pushConsentAt)}</time></span>}</dd></div>
      <div><dt>이 브라우저</dt><dd data-tone={view.tone} aria-live="polite">{view.label}</dd></div>
    </dl>
    {view.help && <p className={styles.help} role={view.tone === 'warn' ? 'alert' : undefined}>{view.help}</p>}
    <div className={styles.actions}>{view.main === 'detach' ? [detachButton, enableButton] : view.main === 'refresh' ? [refreshButton, enableButton, detachButton] : [enableButton, detachButton]}{view.main !== 'refresh' && refreshButton}</div>
    {mutation.isPending && <p role="status">처리하고 있어요.</p>}
    {done && mutation.isSuccess && <p className={styles.success} role="status">{done === 'enable' ? '이 브라우저에 푸시 알림을 연결했어요.' : done === 'off' ? '모든 기기의 푸시 알림을 껐어요.' : '이 브라우저의 푸시 알림을 해제했어요.'}</p>}
    {mutation.isError && <p className={styles.error} role="alert">{mutation.error instanceof PushError ? mutation.error.message : notificationError(mutation.error)} 일부만 반영됐을 수 있으니 상태를 다시 확인해 주세요.</p>}
    {/* 마이페이지 회원 탈퇴 영역처럼 구분선 아래 작은 텍스트 버튼으로 둔다. */}
    {settings.pushEnabled && <div className={styles.allDevices}>
      <Button variant="ghost" className={styles.offButton} disabled={busy || !data} onClick={() => start('off')}>모든 기기 푸시 끄기</Button>
      <p className={styles.note}>다른 기기를 포함해 이 계정의 푸시 알림을 모두 꺼요. Y:ON 안에서 알림 받기 설정은 바뀌지 않아요.</p>
    </div>}
  </section>
}
