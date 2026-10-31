import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'
import ts from 'typescript'
const require = createRequire(import.meta.url)
require.extensions['.css'] = module => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const extension of ['.ts', '.tsx']) require.extensions[extension] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const Controls = require('../src/push/PushControls.tsx').default
const owner = { id: 11, role: 'USER' }, settings = { inAppEnabled: true, pushEnabled: true, pushConsentAt: null, updatedAt: null }
function render(status, overrides = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } })
  if (status === 'error') client.getQueryCache().build(client, { queryKey: ['notifications', 11, 'push-browser'] }).setState({ status: 'error', error: new Error('secret'), fetchStatus: 'idle' })
  else if (status !== 'checking') client.setQueryData(['notifications', 11, 'push-browser'], { ready: true, supported: true, permission: 'granted', connected: false, phase: null, knownEndpoints: 0, controller: {}, ...status })
  try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(Controls, { owner, settings: { ...settings, ...overrides }, disabled: false }))) } finally { client.clear() }
}
const browserState = html => html.match(/<dt>이 브라우저<\/dt><dd[^>]*>([^<]+)<\/dd>/)?.[1]
const buttons = html => [...html.matchAll(/<div class="actions">([\s\S]*?)<\/div>/g)][0][1].match(/<button[^>]*>[\s\S]*?<\/button>/g).map(button => ({
  text: button.replace(/<[^>]+>/g, ''), variant: button.match(/class="button (\w+)/)[1], disabled: /disabled=""/.test(button),
}))

test('missing configuration shows a plain unavailable message without developer settings and disables connecting', () => {
  const html = render({ ready: false })
  assert.match(html, /지금은 푸시 알림을 사용할 수 없어요/)
  assert.doesNotMatch(html, /VITE_FIREBASE|Firebase|VAPID|\.env/)
  assert.match(html, /<button[^>]*disabled=""[^>]*>이 브라우저 연결/)
  assert.equal(browserState(html), '사용할 수 없음')
})
test('unsupported environment and blocked permissions disable connecting without claiming a subscription', () => {
  for (const status of [{ supported: false }, { permission: 'denied' }]) {
    const html = render(status)
    assert.match(html, /<button[^>]*disabled=""[^>]*>이 브라우저 연결/)
    assert.doesNotMatch(html, /연결됨/)
  }
  assert.match(render({ supported: false }), /이 브라우저에서는 푸시 알림을 사용할 수 없어요[\s\S]*홈 화면에 추가한 Y:ON/)
  // 권한 차단은 브라우저 설정 변경 후 다시 확인하는 것이 주요 행동이다.
  const denied = render({ permission: 'denied' })
  assert.match(denied, /알림 권한이 차단되어 있어요/)
  assert.deepEqual(buttons(denied)[0], { text: '상태 다시 확인', variant: 'secondary', disabled: false })
})
test('checking and failed checks never guess the connection and offer only the matching action', () => {
  const checking = render('checking')
  assert.equal(browserState(checking), '확인하고 있어요')
  assert.doesNotMatch(checking, /연결 안 됨|연결됨|>이 브라우저 연결<|>이 브라우저 해제</)
  const failed = render('error')
  assert.equal(browserState(failed), '확인하지 못했어요')
  assert.doesNotMatch(failed, /연결 안 됨|연결됨|secret/)
  assert.deepEqual(buttons(failed).map(button => button.text), ['상태 다시 확인'])
})
test('the primary action follows the confirmed browser state while account and browser states stay separate', () => {
  const idle = render({})
  assert.equal(browserState(idle), '연결 안 됨')
  assert.deepEqual(buttons(idle).map(({ text, variant }) => [text, variant]), [['이 브라우저 연결', 'primary'], ['상태 다시 확인', 'ghost']])
  const connected = render({ connected: true, phase: 'active', knownEndpoints: 1 })
  assert.match(connected, /<dt>계정 푸시 알림<\/dt><dd[^>]*>켜짐<\/dd>/)
  assert.equal(browserState(connected), '연결됨')
  assert.deepEqual(buttons(connected).map(({ text, variant }) => [text, variant]), [['이 브라우저 해제', 'secondary'], ['상태 다시 확인', 'ghost']])
  // 브라우저는 연결돼 있어도 계정 푸시가 꺼져 있으면 연결됨으로 표시하지 않는다.
  const accountOff = render({ connected: true, phase: 'active', knownEndpoints: 1 }, { pushEnabled: false })
  assert.match(accountOff, /<dt>계정 푸시 알림<\/dt><dd[^>]*>꺼짐<\/dd>/)
  assert.notEqual(browserState(accountOff), '연결됨')
  assert.equal(buttons(accountOff)[0].text, '이 브라우저 연결')
})
test('unfinished connection and cleanup keep their recovery actions', () => {
  const pending = render({ phase: 'pending', knownEndpoints: 1 })
  assert.match(pending, /연결이 끝나지 않았어요/)
  assert.deepEqual(buttons(pending).map(({ text, variant }) => [text, variant]), [['이 브라우저 연결', 'primary'], ['이 브라우저 해제', 'ghost'], ['상태 다시 확인', 'ghost']])
  const cleanup = render({ phase: 'cleanup', knownEndpoints: 1 })
  assert.match(cleanup, /이전 연결 정리가 끝나지 않았어요/)
  assert.deepEqual(buttons(cleanup)[0], { text: '이 브라우저 해제', variant: 'secondary', disabled: false })
})
test('turning off every device is separated, explained once and shown only while account push is on', () => {
  const html = render({ connected: true, phase: 'active', knownEndpoints: 1 })
  assert.match(html, /<div class="allDevices"><button[^>]*class="button ghost offButton"[^>]*>모든 기기 푸시 끄기<\/button><p[^>]*>다른 기기를 포함해 이 계정의 푸시 알림을 모두 꺼요/)
  assert.doesNotMatch(buttons(html).map(button => button.text).join(), /모든 기기/)
  assert.equal((html.match(/다른 기기를 포함해/g) ?? []).length, 1)
  assert.doesNotMatch(render({}, { pushEnabled: false }), /모든 기기 푸시 끄기/)
})
test('the general push description sits once under the title, above the divider and the state rows', () => {
  for (const status of [{}, { connected: true, phase: 'active', knownEndpoints: 1 }]) {
    const html = render(status)
    assert.match(html, /<div class="head"><h2 id="web-push-title">브라우저 푸시 알림<\/h2><p class="help">연결하면 Y:ON 화면을 보고 있지 않을 때도 이 브라우저로 새 소식을 받을 수 있어요\.<\/p><\/div><dl class="status">/)
    assert.equal((html.match(/보고 있지 않을 때도/g) ?? []).length, 1)
  }
})
test('state rows use a fixed label column and never show a lingering success message on render', () => {
  const css = fs.readFileSync(new URL('../src/push/PushControls.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.status \{[^}]*grid-template-columns: 6\.5rem minmax\(0, 1fr\)/)
  assert.match(css, /\.consent \{[^}]*font-size: 0\.75rem/)
  const html = render({ connected: true, phase: 'active', knownEndpoints: 1 }, { pushConsentAt: '2026-10-26T01:00:00Z' })
  assert.match(html, /<dd data-tone="ok">켜짐<span class="consent">동의 <time/)
  // 완료 문구는 작업 직후의 일시적 피드백이다. 연결됨 상태로 다시 그릴 때는 보이지 않는다.
  assert.doesNotMatch(html, /연결했어요|해제했어요|껐어요/)
  // 연결 확인 불가·권한 차단 안내는 계속 보인다.
  assert.match(render('error'), /role="alert">이 브라우저의 연결 상태를 확인하지 못했어요/)
  assert.match(render({ permission: 'denied' }), /role="alert">알림 권한이 차단되어 있어요/)
})
test('push controls use plain language and hide internal implementation details', () => {
  for (const status of [{}, { ready: false }, { phase: 'pending' }, 'error']) {
    assert.doesNotMatch(render(status), /서버 엔드포인트|등록 ID|FCM|서비스 워커|Web Locks|Push API|HTTPS|Firebase/)
  }
})
