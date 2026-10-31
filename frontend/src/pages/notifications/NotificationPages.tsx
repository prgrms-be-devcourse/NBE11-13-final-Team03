import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useInfiniteQuery, useIsMutating, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { loginPath } from '../../auth/returnPath'
import type { AuthUser } from '../../api/auth'
import { ApiError } from '../../api/ApiError'
import { assertNotificationOwner, notificationError, notificationKeys, notificationLabels, notificationMutationKey, notificationsQuery, notificationSettingsQuery, notificationTarget, runNotificationChange } from '../../api/notifications'
import type { NotificationAction, NotificationSettings } from '../../api/notifications'
import Button from '../../components/ui/Button'
import Card from '../../components/ui/Card'
import AsyncState from '../../components/ui/AsyncState'
import ArrowLink from '../../components/ui/ArrowLink'
import LoginRequired from '../../components/auth/LoginRequired'
import { formatDateTime } from '../../utils/date'
import { ArrowRight, CheckCheck, RefreshCw } from 'lucide-react'
import styles from './NotificationPages.module.css'
import PushControls from '../../push/PushControls'
import { parsePushClick } from '../../push/protocol'

function Access({ path, children }: { path: string; children: (owner: AuthUser) => ReactNode }) {
  const auth = useAuth()
  if (auth.status === 'checking') return <AsyncState status="loading" title="로그인 상태를 확인하고 있어요" />
  if (auth.status === 'error') return <AsyncState status="error" title="로그인 상태를 확인하지 못했어요" onRetry={auth.retry} />
  if (auth.status !== 'authenticated' || !auth.user) return <LoginRequired description="알림은 로그인 후 이용할 수 있어요." returnTo={path} />
  return children(auth.user)
}
function RequestError({ error, retry, path }: { error: Error; retry: () => void; path: string }) {
  return <><AsyncState status="error" title={notificationError(error)} onRetry={retry} />
    {error instanceof ApiError && error.status === 401 && <Link to={loginPath(path)}>다시 로그인하기</Link>}</>
}
function useNotificationAction(owner: AuthUser) {
  const client = useQueryClient(), running = useRef(false), active = useRef(false)
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  const mutation = useMutation({ mutationKey: notificationMutationKey(owner.id), mutationFn: (action: NotificationAction) => runNotificationChange(client, owner, action) })
  const pending = useIsMutating({ mutationKey: notificationMutationKey(owner.id) }) > 0
  async function run(action: NotificationAction): Promise<boolean> {
    if (running.current || pending) return false
    running.current = true
    try { await mutation.mutateAsync(action); return true } catch { return false } finally { running.current = false }
  }
  return { mutation, pending, run, active, client }
}
function NotificationList({ owner }: { owner: AuthUser }) {
  const result = useInfiniteQuery(notificationsQuery(owner.id))
  const { mutation, pending, run, active, client } = useNotificationAction(owner)
  const navigate = useNavigate()
  const click = parsePushClick(useLocation().search)
  function open(id: number, target: string) {
    void run({ kind: 'open', id }).then(ok => {
      if (!ok || !active.current) return
      try { assertNotificationOwner(client, owner); navigate(target) } catch { /* 이전 세션의 늦은 응답은 이동하지 않는다. */ }
    })
  }
  const retry = () => { void Promise.all([
    result.refetch({ throwOnError: true }),
    client.invalidateQueries({ queryKey: notificationKeys.unread(owner.id) }, { throwOnError: true }),
  ]).then(() => mutation.reset()).catch(() => {}) }
  if (result.isPending) return <AsyncState status="loading" title="알림을 불러오고 있어요" />
  if (result.isError && !result.data) return <RequestError error={result.error} retry={retry} path="/notifications" />
  const items = result.data?.pages.flatMap(page => page.items) ?? []
  const busy = pending || result.isFetching
  const pushedItem = click && items.find(item => item.id === click.notificationId)
  const pushedTarget = click && (pushedItem ? notificationTarget(pushedItem) : `/opportunities/${click.opportunityId}`)
  return <>
    {click && <Card className={styles.card}><p>푸시 알림으로 받은 내용을 확인해 보세요.</p><Button disabled={busy || !pushedTarget} onClick={() => { if (pushedTarget) open(click.notificationId, pushedTarget) }}>알림 확인하기</Button></Card>}
    <div className={styles.toolbar}><Button variant="ghost" disabled={busy} onClick={retry}><RefreshCw size={16} aria-hidden="true" />새로고침</Button><Button variant="secondary" disabled={busy || !items.length} loading={pending} onClick={() => { void run({ kind: 'read-all' }) }}><CheckCheck size={16} aria-hidden="true" />전체 읽음 처리</Button></div>
    {mutation.isSuccess && <p role="status">알림 처리를 완료했어요.</p>}
    {mutation.isError && <RequestError error={mutation.error} retry={retry} path="/notifications" />}
    {result.isError && <RequestError error={result.error} retry={retry} path="/notifications" />}
    {!items.length && !result.isError && <AsyncState status="empty" title="아직 알림이 없어요" description="새로운 기회 소식이 도착하면 이곳에서 확인할 수 있어요." />}
    <ul className={styles.list}>{items.map(item => {
      const target = notificationTarget(item)
      const unread = item.readAt === null
      // 다른 목록 카드처럼 위에는 종류·상태 배지, 아래에는 시각과 동작을 둔다. 읽음 여부는 색과 함께 글자로도 표시한다.
      return <li key={item.id}><Card className={`${styles.card} ${unread ? styles.unread : ''}`}>
        <div className={styles.meta}><span className={styles.kind}>{notificationLabels[item.type]}</span><span className={styles.readState} data-read={!unread}>{unread ? '안 읽음' : '읽음'}</span></div>
        <h2>{item.title || '제목 미제공'}</h2><p className={styles.content}>{item.content}</p>
        <div className={styles.footer}>
          <time dateTime={item.createdAt}>{formatDateTime(item.createdAt)}</time>
          <div className={styles.actions}>
            {unread && <Button variant="secondary" disabled={busy} aria-label={`${item.title} 읽음 처리`} onClick={() => { void run({ kind: 'read', id: item.id }) }}>읽음 처리</Button>}
            {/* 클릭 기록 후 이동해야 하므로 링크가 아닌 버튼이다. 모양은 다른 화면의 이동 링크와 맞춘다. */}
            <Button variant="ghost" className={styles.openButton} disabled={busy || !target} aria-label={`${item.title} ${item.type === 'NEW_MATCH' ? '추천 보기' : '공고 보기'}`} onClick={() => {
              if (!target) return
              open(item.id, target)
            }}>{item.type === 'NEW_MATCH' ? '추천 보기' : '공고 보기'}<ArrowRight size={16} aria-hidden="true" /></Button>
          </div>
          {!target && <p className={styles.note}>연결된 공고가 없어요.</p>}
        </div>
      </Card></li>
    })}</ul>
    {result.isFetching && <p role="status">최신 알림을 확인하고 있어요.</p>}
    {result.hasNextPage && <div className={styles.more}><Button variant="secondary" loading={result.isFetchingNextPage} disabled={busy} onClick={() => { if (!result.isFetching) void result.fetchNextPage({ cancelRefetch: false }) }}>더 보기</Button></div>}
  </>
}
function SettingsForm({ owner, settings, fetching }: { owner: AuthUser; settings: NotificationSettings; fetching: boolean }) {
  const [draft, setDraft] = useState<boolean | null>(null)
  const { mutation, pending, run } = useNotificationAction(owner)
  const client = useQueryClient()
  const enabled = draft ?? settings.inAppEnabled
  const changed = draft !== null && draft !== settings.inAppEnabled
  // 마이페이지처럼 주제별 카드로 나눈다: 알림 받기(인앱) / 웹 푸시.
  return <>
    <Card className={styles.panel}><form className={styles.form} aria-labelledby="in-app-title" onSubmit={event => {
      event.preventDefault()
      if (fetching) return
      void run({ kind: 'settings', values: { inAppEnabled: enabled, pushEnabled: settings.pushEnabled } }).then(ok => { if (ok) setDraft(null) })
    }}>
      <div className={styles.sectionHead}><h2 id="in-app-title">알림 받기</h2>
        <p className={styles.help}>신청 시작·마감 임박 같은 공고 소식을 알림 화면에서 확인해요.</p></div>
      {/* 설정이 하나뿐이라 체크박스와 저장 버튼을 한 줄에 둔다. 저장 시 기존 푸시 설정 값은 그대로 보낸다. */}
      <div className={styles.settingRow}>
        <label className={styles.toggle}><input type="checkbox" checked={enabled} disabled={pending || fetching} onChange={event => setDraft(event.target.checked)} />Y:ON 안에서 알림 받기</label>
        <div className={styles.formActions}><Button type="submit" loading={pending} disabled={fetching || !changed}>설정 저장</Button></div>
      </div>
      {/* 체크 상태가 저장값과 다를 때만 미저장을 알린다. 같으면 체크 상태가 곧 저장된 설정이다. */}
      {changed ? <p className={styles.unsaved} role="status">{pending ? '설정을 저장하고 있어요.' : '저장하지 않은 변경이 있어요.'}</p>
        : mutation.isSuccess && draft === null ? <p className={styles.saved} role="status">알림 설정을 저장했어요.</p>
          : settings.updatedAt && <p className={styles.note}>마지막 변경 <time dateTime={settings.updatedAt}>{formatDateTime(settings.updatedAt)}</time></p>}
      {mutation.isError && <RequestError error={mutation.error} path="/settings/notifications" retry={() => { void client.invalidateQueries({ queryKey: notificationKeys.settings(owner.id) }, { throwOnError: true }).then(() => mutation.reset()).catch(() => {}) }} />}
    </form></Card>
    <Card className={styles.panel}><PushControls owner={owner} settings={settings} disabled={pending || fetching} /></Card>
  </>
}
function Settings({ owner }: { owner: AuthUser }) {
  const result = useQuery(notificationSettingsQuery(owner.id))
  if (result.isPending) return <AsyncState status="loading" title="알림 설정을 불러오고 있어요" />
  if (result.isError) return <RequestError error={result.error} path="/settings/notifications" retry={() => { void result.refetch() }} />
  return <SettingsForm owner={owner} settings={result.data} fetching={result.isFetching} />
}
export function NotificationsPage() {
  const location = useLocation()
  // 제목과 설정 이동을 한 줄에 묶고, 목록 동작은 그 아래 도구 줄에 둔다.
  return <section className={styles.page} aria-labelledby="notifications-title">
    <header className={styles.pageHeader}><h1 id="notifications-title">알림</h1><ArrowLink to="/settings/notifications">알림 설정</ArrowLink></header>
    <Access path={`/notifications${location.search}`}>{owner => <NotificationList key={owner.id} owner={owner} />}</Access></section>
}
export function NotificationSettingsPage() {
  return <section className={styles.page} aria-labelledby="notification-settings-title">
    {/* 맞춤 조건 설정과 같이 뒤로가기 → 제목 → 패널 순서로 페이지 간격만 사용한다. */}
    <ArrowLink direction="back" to="/notifications" aria-label="알림으로 돌아가기">알림</ArrowLink><h1 id="notification-settings-title">알림 설정</h1>
    <Access path="/settings/notifications">{owner => <Settings key={owner.id} owner={owner} />}</Access></section>
}
