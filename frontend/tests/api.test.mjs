import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import axios, { AxiosError } from 'axios'
import { ApiError, toApiError } from '../src/api/ApiError.ts'
import { queryClient } from '../src/api/queryClient.ts'

const originalAdapter = axios.defaults.adapter
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
let clientNumber = 0

afterEach(() => {
  axios.defaults.adapter = originalAdapter
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
  queryClient.clear()
})

async function client(adapter, cookie = '') {
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie } })
  axios.defaults.adapter = adapter
  return (await import(`../src/api/client.ts?test=${++clientNumber}`)).apiClient
}

function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

function response(config, data = {}, status = 200) {
  return { config, data, status, statusText: String(status), headers: {} }
}

function rejectHttp(config, status, code = 'AUTH_007') {
  throw new AxiosError('private request details', 'ERR_BAD_RESPONSE', config, {}, response(config, {
    code, message: 'Request failed', fieldErrors: {},
  }, status))
}

test('relative API path, cookies, timeout and safe methods', async () => {
  const requests = []
  const api = await client(async (config) => {
    requests.push(config)
    return response(config)
  })
  for (const method of ['get', 'head', 'options', 'trace']) await api.request({ method, url: '/opportunities' })
  assert.equal(requests.length, 4)
  for (const request of requests) {
    assert.equal(api.getUri(request), '/api/opportunities')
    assert.equal(request.withCredentials, true)
    assert.equal(request.timeout, 10_000)
    assert.equal(request.headers.get('X-XSRF-TOKEN'), undefined)
  }
  for (const url of ['/api/opportunities', 'https://example.com', '//example.com', 'opportunities']) {
    await assert.rejects(api.get(url), (error) => error.kind === 'configuration')
  }
  assert.equal(requests.length, 4)
})

test('unsafe methods bootstrap CSRF once and send the latest cookie token', async () => {
  const requests = []
  const api = await client(async (config) => {
    requests.push(config)
    if (config.url === '/interests/options') document.cookie = 'XSRF-TOKEN=csrf%2Dtest'
    else assert.equal(config.headers.get('X-XSRF-TOKEN'), 'csrf-test')
    return response(config)
  })
  await Promise.all(['post', 'put', 'patch', 'delete'].map((method) => api.request({ method, url: '/users/me/interests' })))
  assert.equal(requests.filter((config) => config.url === '/interests/options').length, 1)
  assert.equal(requests.length, 5)
})

test('missing CSRF cookie blocks state changes instead of bypassing protection', async () => {
  const paths = []
  const api = await client(async (config) => {
    paths.push(config.url)
    return response(config)
  })
  await assert.rejects(api.post('/auth/reissue'), (error) => error.kind === 'csrf')
  assert.deepEqual(paths, ['/interests/options'])
})

test('public 401, forbidden and network failures do not reissue tokens', async () => {
  for (const mode of ['public', 'forbidden', 'network']) {
    const paths = []
    const api = await client(async (config) => {
      paths.push(config.url)
      if (mode === 'network') throw new AxiosError('private', 'ERR_NETWORK', config)
      return rejectHttp(config, mode === 'forbidden' ? 403 : 401)
    })
    await assert.rejects(api.get('/opportunities', { requiresAuth: mode !== 'public' }))
    assert.deepEqual(paths, ['/opportunities'])
  }
})

test('parallel authenticated 401s share reissue; late 401 replays without another rotation', async () => {
  let refreshed = false
  let rotations = 0
  let releaseLate
  const late = new Promise((resolve) => { releaseLate = resolve })
  const counts = new Map()
  const api = await client(async (config) => {
    if (config.url === '/auth/reissue') {
      rotations++
      assert.equal(config.headers.get('X-XSRF-TOKEN'), 'csrf-test')
      await new Promise((resolve) => setTimeout(resolve, 5))
      refreshed = true
      return response(config, '', 200)
    }
    const count = (counts.get(config.url) ?? 0) + 1
    counts.set(config.url, count)
    if (config.url === '/users/me/late' && count === 1) {
      await late
      return rejectHttp(config, 401)
    }
    if (!refreshed) return rejectHttp(config, 401)
    return response(config)
  }, 'XSRF-TOKEN=csrf-test')
  const lateRequest = api.get('/users/me/late', { requiresAuth: true })
  await Promise.all(Array.from({ length: 4 }, (_, index) => api.get(`/users/me/${index}`, { requiresAuth: true })))
  releaseLate()
  await lateRequest
  assert.equal(rotations, 1)
  assert.equal(counts.get('/users/me/late'), 2)
})

test('non-401 reissue failure is shared by in-flight requests, then a new auth check actually retries and recovers', async () => {
  for (const failure of [403, 400]) {
    let rotations = 0, recovered = false
    let releaseLate
    const late = new Promise((resolve) => { releaseLate = resolve })
    const counts = new Map()
    const api = await client(async (config) => {
      if (config.url === '/auth/reissue') {
        rotations++
        if (!recovered) return rejectHttp(config, failure, 'AUTH_001')
        return response(config, '')
      }
      const count = (counts.get(config.url) ?? 0) + 1
      counts.set(config.url, count)
      if (config.url === '/users/me/late' && count === 1) { await late; return rejectHttp(config, 401) }
      if (rotations >= 2) return response(config, { id: 1 })
      return rejectHttp(config, 401)
    }, 'XSRF-TOKEN=csrf-test')
    const lateRequest = api.get('/users/me/late', { requiresAuth: true }).catch((error) => error)
    const results = await Promise.allSettled(Array.from({ length: 3 }, () => api.get('/users/me', { requiresAuth: true })))
    assert.ok(results.every((result) => result.status === 'rejected' && result.reason.status === failure))
    assert.equal(rotations, 1)
    // 실패 전에 시작한 요청의 늦은 401은 같은 실패를 공유하고 새 재발급을 시작하지 않는다.
    releaseLate()
    assert.equal((await lateRequest).status, failure)
    assert.equal(rotations, 1)
    recovered = true
    const { data } = await api.get('/users/me', { requiresAuth: true })
    assert.deepEqual(data, { id: 1 })
    assert.equal(rotations, 2)
  }
})

test('reissue 401 (invalid refresh token) is shared and retained until confirmed authentication changes', async () => {
  for (const failure of [401]) {
    let rotations = 0
    const api = await client(async (config) => {
      if (config.url === '/auth/reissue') {
        rotations++
        return rejectHttp(config, failure, 'AUTH_001')
      }
      return rejectHttp(config, 401)
    }, 'XSRF-TOKEN=csrf-test')
    const results = await Promise.allSettled(Array.from({ length: 3 }, () => api.get('/users/me', { requiresAuth: true })))
    assert.ok(results.every((result) => result.status === 'rejected' && result.reason instanceof ApiError))
    await assert.rejects(api.get('/users/me', { requiresAuth: true }))
    assert.equal(rotations, 1)
  }
})

test('a failed replay and auth endpoints never trigger recursive reissue', async () => {
  let rotations = 0
  let protectedRequests = 0
  const api = await client(async (config) => {
    if (config.url === '/auth/reissue') { rotations++; return response(config) }
    protectedRequests++
    return rejectHttp(config, 401)
  }, 'XSRF-TOKEN=csrf-test')
  await assert.rejects(api.get('/users/me', { requiresAuth: true }))
  assert.equal(rotations, 1)
  assert.equal(protectedRequests, 2)
  await assert.rejects(api.post('/auth/logout', undefined, { requiresAuth: true }))
  assert.equal(rotations, 1)
})

// 같은 시도에 참여한 5개 요청은 실패를 공유한다. 실패 전 출발한 지연 요청은
// 새 시도가 진행 중이어도 과거 실패로 종료하고, 실패 후 새 요청만 복구한다.
for (const failure of ['network', 'timeout', '503']) {
  test(`transient ${failure}: waiters settle, late old 401 stays failed, new request recovers`, async () => {
    const firstStarted = deferred()
    const firstFinish = deferred()
    const secondStarted = deferred()
    const secondFinish = deferred()
    const lateStarted = deferred()
    const lateFinish = deferred()
    let rotations = 0
    let authorized = false
    let freshRequests = 0
    const api = await client(async (config) => {
      if (config.url === '/auth/reissue') {
        rotations++
        if (rotations === 1) {
          firstStarted.resolve()
          await firstFinish.promise
          if (failure === '503') return rejectHttp(config, 503, 'COMMON_500')
          throw new AxiosError('temporary failure', failure === 'network' ? 'ERR_NETWORK' : 'ETIMEDOUT', config)
        }
        secondStarted.resolve()
        await secondFinish.promise
        authorized = true
        return response(config)
      }
      if (config.url === '/users/me/late') {
        lateStarted.resolve()
        await lateFinish.promise
        return rejectHttp(config, 401)
      }
      if (config.url === '/users/me/fresh') freshRequests++
      if (!authorized) return rejectHttp(config, 401)
      return response(config)
    }, 'XSRF-TOKEN=csrf-test')

    const late = api.get('/users/me/late', { requiresAuth: true })
    // 게이트를 조작하는 동안 처리되지 않은 rejection이 생기지 않도록 먼저 연결한다.
    const lateResult = Promise.allSettled([late])
    const waiting = Promise.allSettled(Array.from({ length: 5 }, (_, index) =>
      api.get(`/users/me/waiting-${index}`, { requiresAuth: true })))
    await Promise.all([firstStarted.promise, lateStarted.promise])
    firstFinish.resolve()
    const failed = await waiting
    assert.ok(failed.every((item) => item.status === 'rejected'))
    const sharedError = failed[0].reason
    assert.ok(failed.every((item) => item.reason === sharedError))
    assert.equal(sharedError.kind, failure === '503' ? 'http' : failure)
    assert.equal(rotations, 1)

    const fresh = api.get('/users/me/fresh', { requiresAuth: true })
    await secondStarted.promise
    lateFinish.resolve()
    const [old] = await lateResult
    assert.equal(old.status, 'rejected')
    assert.equal(old.reason, sharedError)
    assert.equal(rotations, 2)
    secondFinish.resolve()
    await fresh
    assert.equal(freshRequests, 2)
    assert.equal(rotations, 2)
  })
}

test('CSRF bootstrap timeout transitions to a new attempt and recovers before the first reissue POST', async () => {
  let online = false
  let bootstrap = 0
  let rotations = 0
  let authorized = false
  const api = await client(async (config) => {
    if (config.url === '/interests/options') {
      bootstrap++
      if (!online) throw new AxiosError('timeout', 'ECONNABORTED', config)
      document.cookie = 'XSRF-TOKEN=recovered'
      return response(config)
    }
    if (config.url === '/auth/reissue') {
      rotations++
      assert.equal(config.headers.get('X-XSRF-TOKEN'), 'recovered')
      authorized = true
      return response(config)
    }
    if (!authorized) return rejectHttp(config, 401)
    return response(config)
  })
  const failed = await Promise.allSettled(Array.from({ length: 5 }, () => api.get('/users/me', { requiresAuth: true })))
  assert.ok(failed.every((item) => item.status === 'rejected' && item.reason.kind === 'timeout'))
  assert.equal(bootstrap, 1)
  assert.equal(rotations, 0)
  online = true
  await api.get('/users/me', { requiresAuth: true })
  assert.equal(bootstrap, 2)
  assert.equal(rotations, 1)
})

test('confirmed authentication change releases terminal failure and rejects a stale session 401', async () => {
  let canRefresh = false
  let authorized = false
  let rotations = 0
  let lateRequests = 0
  const lateStarted = deferred()
  const lateFinish = deferred()
  const api = await client(async (config) => {
    if (config.url === '/auth/reissue') {
      rotations++
      if (!canRefresh) return rejectHttp(config, 401, 'AUTH_003')
      authorized = true
      return response(config)
    }
    if (config.url === '/users/me/old-session') {
      lateRequests++
      lateStarted.resolve()
      await lateFinish.promise
      return rejectHttp(config, 401)
    }
    if (!authorized) return rejectHttp(config, 401)
    return response(config)
  }, 'XSRF-TOKEN=csrf-test')
  const { resetAuthRecovery } = await import(`../src/api/client.ts?test=${clientNumber}`)
  const late = Promise.allSettled([api.get('/users/me/old-session', { requiresAuth: true })])
  await lateStarted.promise
  await assert.rejects(api.get('/users/me', { requiresAuth: true }), (error) => error.code === 'AUTH_003')
  await assert.rejects(api.get('/users/me', { requiresAuth: true }), (error) => error.code === 'AUTH_003')
  assert.equal(rotations, 1)

  // 모의 서버에서 새 로그인이 확인된 뒤 호출한다. 단순 장애 복구와 구분한다.
  canRefresh = true
  resetAuthRecovery()
  await api.get('/users/me', { requiresAuth: true })
  lateFinish.resolve()
  const [old] = await late
  assert.equal(old.status, 'rejected')
  assert.equal(old.reason.kind, 'cancelled')
  assert.equal(lateRequests, 1)
  assert.equal(rotations, 2)
})

test('authentication reset aborts an in-flight old reissue without blocking the new session', async () => {
  const started = deferred()
  let rotations = 0
  let authorized = false
  let oldSignal
  const api = await client(async (config) => {
    if (config.url === '/auth/reissue') {
      rotations++
      if (rotations === 1) {
        oldSignal = config.signal
        started.resolve()
        return new Promise((resolve, reject) => {
          config.signal.addEventListener('abort', () => reject(new axios.CanceledError()), { once: true })
        })
      }
      authorized = true
      return response(config)
    }
    if (!authorized) return rejectHttp(config, 401)
    return response(config)
  }, 'XSRF-TOKEN=csrf-test')
  const { resetAuthRecovery } = await import(`../src/api/client.ts?test=${clientNumber}`)
  const old = Promise.allSettled([api.get('/users/me', { requiresAuth: true })])
  await started.promise
  resetAuthRecovery()
  await api.get('/users/me', { requiresAuth: true })
  const [result] = await old
  assert.equal(result.status, 'rejected')
  assert.equal(result.reason.kind, 'cancelled')
  assert.equal(oldSignal.aborted, true)
  assert.equal(rotations, 2)
})

test('CSRF 403 and manual auth endpoints remain outside automatic reissue', async () => {
  for (const [url, status] of [['/users/me', 403], ['/auth/logout', 401], ['/auth/reissue', 401]]) {
    const paths = []
    const api = await client(async (config) => {
      paths.push(config.url)
      return rejectHttp(config, status)
    }, 'XSRF-TOKEN=csrf-test')
    await assert.rejects(api.post(url, undefined, { requiresAuth: true }), (error) => error.status === status)
    assert.deepEqual(paths, [url])
  }
})

test('error normalization preserves validated contract and omits raw request data', () => {
  const config = { headers: { Authorization: 'synthetic-secret' } }
  const error = new AxiosError('synthetic-secret', 'ERR_BAD_RESPONSE', config, {}, response(config, {
    code: 'COMMON_001', message: 'Invalid input', fieldErrors: { name: 'Required' },
  }, 400))
  const normalized = toApiError(error)
  assert.equal(normalized.status, 400)
  assert.equal(normalized.code, 'COMMON_001')
  assert.deepEqual(normalized.fieldErrors, { name: 'Required' })
  assert.equal(normalized.config, undefined)
  assert.equal(normalized.cause, undefined)
  assert.ok(!JSON.stringify(normalized).includes('synthetic-secret'))
  const html = toApiError(new AxiosError('private', 'ERR_BAD_RESPONSE', {}, {}, response({}, '<html>Forbidden</html>', 403)))
  assert.equal(html.status, 403)
  assert.equal(html.code, undefined)
  assert.equal(toApiError(new AxiosError('private', 'ETIMEDOUT')).kind, 'timeout')
  assert.equal(toApiError(new axios.CanceledError()).kind, 'cancelled')
})

test('QueryClient does not retry 401/403/CSRF/cancellation, retries transient failures once', async () => {
  for (const error of [
    new ApiError('http', 'unauthorized', 401),
    new ApiError('http', 'forbidden', 403),
    new ApiError('csrf', 'missing'),
    new ApiError('cancelled', 'cancelled'),
    new ApiError('network', 'offline'),
    new ApiError('timeout', 'timeout'),
    new ApiError('http', 'server failure', 503),
  ]) {
    let attempts = 0
    await assert.rejects(queryClient.fetchQuery({
      queryKey: [error.kind, error.status], retryDelay: 0,
      queryFn: () => { attempts++; throw error },
    }))
    assert.equal(attempts, ['network', 'timeout'].includes(error.kind) || error.status === 503 ? 2 : 1)
  }
  assert.equal(queryClient.getDefaultOptions().mutations.retry, false)
})
