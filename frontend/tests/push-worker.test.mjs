import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { test } from 'node:test'
import ts from 'typescript'
import { readPushConfig, pushEnvironmentKeys } from '../src/push/config.ts'
import { pushTarget, pushClickPath, pushNavigateType, pushMessagePath } from '../src/push/protocol.ts'

function workerFixture(existing = true) {
  const handlers = {}, calls = []
  const source = fs.readFileSync(new URL('../src/push/firebase-messaging-sw.ts', import.meta.url), 'utf8').replace('import.meta.env', 'fixtureEnv')
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  // 앱 탭은 전용 scope 워커의 제어 대상이 아니므로 실제 브라우저처럼 navigate()를 거부한다.
  const client = { url: 'https://yon.test/', navigate: async () => { calls.push(['navigate']); throw new TypeError('not controlled') },
    postMessage: message => { calls.push(['message', message]) }, focus: async () => { calls.push(['focus']); return client } }
  const self = { location: { origin: 'https://yon.test' }, addEventListener: (name, handler) => { handlers[name] = handler }, clients: {
    matchAll: async () => existing ? [client] : [], openWindow: async url => { calls.push(['open', url]) },
  } }
  const modules = {
    '@firebase/app': { initializeApp: () => ({}) }, '@firebase/messaging/sw': { getMessaging: () => ({}), onBackgroundMessage: (_messaging, callback) => { handlers.background = callback } },
    './config': { readPushConfig }, './protocol': { pushTarget, pushClickPath, pushNavigateType },
  }
  vm.runInNewContext(compiled, { exports: {}, require: name => modules[name], self, URL, fixtureEnv: Object.fromEntries(pushEnvironmentKeys.map(key => [key, 'fixture'])) })
  async function click(payload) {
    let waiting
    handlers.notificationclick({ notification: { data: { FCM_MSG: payload }, close: () => calls.push(['close']) }, stopImmediatePropagation: () => calls.push(['stop']), waitUntil: promise => { waiting = promise } })
    await waiting
  }
  return { click, calls, background: handlers.background }
}
test('executed worker click handler asks an uncontrolled same-origin tab to route, focuses it, and passes only validated IDs', async () => {
  const f = workerFixture()
  await f.click({ data: { notificationId: '7' }, fcmOptions: { link: 'https://yon.test/opportunities/42' } })
  // 워커 컨텍스트의 객체이므로 직렬화해 비교한다.
  assert.deepEqual(JSON.parse(JSON.stringify(f.calls)), [['stop'], ['close'], ['message', { type: pushNavigateType, path: '/notifications?pushId=7&opportunityId=42' }], ['focus']])
  const external = workerFixture()
  await external.click({ data: { notificationId: '7' }, fcmOptions: { link: 'https://evil.test/opportunities/42' } })
  assert.deepEqual(JSON.parse(JSON.stringify(external.calls.at(2))), ['message', { type: pushNavigateType, path: '/notifications' }])
})
test('page accepts only notification paths with validated IDs from worker navigation messages', () => {
  assert.equal(pushMessagePath({ type: pushNavigateType, path: '/notifications?pushId=7&opportunityId=42' }), '/notifications?pushId=7&opportunityId=42')
  assert.equal(pushMessagePath({ type: pushNavigateType, path: '/notifications' }), '/notifications')
  for (const path of ['https://evil.test/notifications', '//evil.test/notifications', '/notifications?pushId=0&opportunityId=42', '/notifications?pushId=7&opportunityId=42&next=/x', '/opportunities/42', '/notifications#x', 7]) {
    assert.equal(pushMessagePath({ type: pushNavigateType, path }), null, String(path))
  }
  assert.equal(pushMessagePath({ type: 'other', path: '/notifications' }), null)
  assert.equal(pushMessagePath(null), null)
})
test('executed worker opens a safe app page when no tab exists and never follows external links', async () => {
  const f = workerFixture(false)
  await f.click({ data: { notificationId: '7' }, fcmOptions: { link: 'https://evil.test/opportunities/42' } })
  assert.deepEqual(f.calls, [['stop'], ['close'], ['open', 'https://yon.test/notifications']])
})
test('background callback performs no second display or authenticated work for an SDK notification message', () => {
  const f = workerFixture()
  f.background({ notification: { title: 'fixture', body: 'fixture' }, data: { notificationId: '7' } })
  assert.deepEqual(f.calls, [])
})
