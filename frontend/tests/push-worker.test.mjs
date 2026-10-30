import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import ts from 'typescript'
import { readPushConfig, pushEnvironmentKeys, supportsPush } from '../src/push/config.ts'
import { createPushController, PushError } from '../src/push/model.ts'
import { emptyPushStore } from '../src/push/storage.ts'
import { pushScopeSuffix } from '../src/push/lifecycle.ts'
import { pushTarget, pushClickPath, pushNavigateType, pushMessagePath } from '../src/push/protocol.ts'
import { resolveConfig } from 'vite'

test('development prebundles Firebase app, window and service-worker SDKs together', async () => {
  const config = await resolveConfig({ root: fileURLToPath(new URL('../', import.meta.url)) }, 'serve')
  for (const entry of ['@firebase/app', '@firebase/messaging', '@firebase/messaging/sw']) {
    assert.ok(config.optimizeDeps.include.includes(entry), `${entry} must not be discovered only during worker registration`)
  }
  assert.equal(config.worker.format, 'iife')
})

function browserFixture(development, registrationFails = false) {
  // 모듈 전체를 실행하고 공개 controller.enable()을 호출한다. 내부 token()의 이름·위치를 알 필요가 없다.
  const source = fs.readFileSync(new URL('../src/push/browser.ts', import.meta.url), 'utf8')
    .replaceAll('import.meta.env', 'fixtureEnv')
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const calls = []
  const active = { active: {} }
  let store = emptyPushStore()
  const modules = {
    '@firebase/app': { getApps: () => [], initializeApp: () => ({}) },
    '@firebase/messaging': { isSupported: async () => true, getMessaging: () => ({}), getToken: async (_client, options) => {
      assert.equal(options.serviceWorkerRegistration, active)
      calls.push({ action: 'token' }); return 'fixture-token'
    } },
    '../api/notifications.ts': { assertNotificationOwner: () => {},
      getNotificationSettings: async () => ({ inAppEnabled: true, pushEnabled: false }),
      putNotificationSettings: async value => value },
    '../api/push.ts': { registerPushEndpoint: async token => {
      calls.push({ action: 'endpoint', token }); return { id: 7, active: true }
    } },
    './config.ts': { readPushConfig, supportsPush }, './model.ts': { createPushController, PushError },
    './storage.ts': { loadPushStore: () => structuredClone(store), savePushStore: value => { store = structuredClone(value) } },
    './lifecycle.ts': { hasBrowserPush: async () => false, pushLock: async work => work(), pushScopeSuffix },
    './firebase-messaging-sw.ts?worker&url': { __esModule: true, default: '/assets/firebase-messaging-sw.js' },
  }
  const exports = {}
  vm.runInNewContext(compiled, { exports, require: name => {
    assert.ok(Object.hasOwn(modules, name), `Unexpected dependency: ${name}`); return modules[name]
  }, URL, Notification: { permission: 'granted' },
  fixtureEnv: { ...Object.fromEntries(pushEnvironmentKeys.map(key => [key, 'fixture-public-key'])), DEV: development },
  location: { href: 'https://yon.test/', origin: 'https://yon.test' },
  window: { isSecureContext: true, Notification: {}, PushManager: {} },
  navigator: { locks: {}, serviceWorker: { getRegistration: async () => undefined, register: async (url, options) => {
    calls.push({ action: 'worker', url: url.href, ...options })
    if (registrationFails) throw Error('fixture registration failure')
    return active
  } } } })
  return { controller: () => exports.browserPushController({}, { id: 11 }), calls, store: () => store }
}

for (const development of [true, false]) test(`enabling push registers ${development ? 'module development' : 'classic production'} worker before token and endpoint`, async () => {
  const f = browserFixture(development)
  await (await f.controller()).enable()
  assert.deepEqual(f.calls.map(call => call.action), ['worker', 'token', 'endpoint'])
  assert.equal(f.calls[0].type, development ? 'module' : 'classic')
  assert.equal(f.calls[0].url, 'https://yon.test/assets/firebase-messaging-sw.js')
  assert.equal(f.calls[0].scope, '/assets/yon-fcm-push/')
  assert.equal(f.calls[2].token, 'fixture-token')
  assert.equal(f.store().users[11].phase, 'active')
})

test('failed worker registration never requests a token or registers an endpoint or claims active push', async () => {
  const f = browserFixture(false, true)
  await assert.rejects((await f.controller()).enable(), PushError)
  assert.deepEqual(f.calls.map(call => call.action), ['worker'])
  assert.notEqual(f.store().users[11].phase, 'active')
})

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
