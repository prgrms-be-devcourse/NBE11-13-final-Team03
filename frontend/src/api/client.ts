import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { ApiError, toApiError } from './ApiError.ts'

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * 미지정/false는 401 자동 재발급을 하지 않는다. 인증 API 호출에만 true로 지정해
     * 401 + AUTH_006/AUTH_007 응답의 재발급·1회 재전송을 허용한다.
     * 쿠키 전송 여부나 서버의 인증·권한 검사를 켜고 끄는 옵션이 아니다.
     */
    requiresAuth?: boolean
  }
}

type RequestConfig = InternalAxiosRequestConfig & {
  _authRetried?: boolean
  _reissueAttempt?: ReissueAttempt
}

type ReissueAttempt = {
  generation: number
  session: number
  promise?: Promise<void>
  controller?: AbortController
}

const defaults = {
  baseURL: '/api',
  withCredentials: true,
  timeout: 10_000,
  headers: { Accept: 'application/json' },
  // 확인한 Spring Security 계약에 따라 안전하지 않은 메서드에만 직접 설정한다.
  withXSRFToken: false,
}

export const apiClient = axios.create(defaults)
// bootstrap/재발급에는 인증 재시도 인터셉터를 붙이지 않는다.
const sessionClient = axios.create(defaults)
const safeMethods = new Set(['get', 'head', 'options', 'trace'])
let csrfBootstrap: Promise<void> | undefined
let authGeneration = 0
let authSession = 0
let reissueAttempt: ReissueAttempt = { generation: authGeneration, session: authSession }

function assertCurrentSession(session: number): void {
  if (session !== authSession) {
    throw new ApiError('cancelled', '인증 상태가 변경되어 요청을 취소했습니다.')
  }
}

/** 서버에서 재로그인 성공을 확인한 뒤에만 호출한다. 단순 401/네트워크 오류에는 호출하지 않는다. */
export function resetAuthRecovery(): void {
  const previousAttempt = reissueAttempt
  authSession += 1
  authGeneration += 1
  reissueAttempt = { generation: authGeneration, session: authSession }
  previousAttempt.controller?.abort()
}

function readCsrfToken(): string | undefined {
  if (typeof document === 'undefined') return undefined
  const cookie = document.cookie.split(';').find((item) => item.trim().startsWith('XSRF-TOKEN='))
  if (!cookie) return undefined
  try {
    return decodeURIComponent(cookie.trim().slice('XSRF-TOKEN='.length)) || undefined
  } catch {
    return undefined
  }
}

async function csrfToken(): Promise<string> {
  const existing = readCsrfToken()
  if (existing) return existing

  if (!csrfBootstrap) {
    // 전용 CSRF endpoint는 없다. 확인된 공개 GET으로 쿠키 발급을 유도한다.
    // 이 흐름은 GET /api/interests/options가 비로그인 공개 API로 유지되는 데 의존한다.
    csrfBootstrap = sessionClient.get('/interests/options')
      .then(() => undefined)
      .finally(() => { csrfBootstrap = undefined })
  }
  await csrfBootstrap
  const token = readCsrfToken()
  if (!token) {
    throw new ApiError('csrf', 'CSRF 토큰을 확인할 수 없습니다. 쿠키 설정을 확인한 후 다시 시도해 주세요.')
  }
  return token
}

function isTransientFailure(error: ApiError): boolean {
  return error.kind === 'network' || error.kind === 'timeout' ||
    (error.kind === 'http' && error.status !== undefined && error.status >= 500 && error.status < 600)
}

function reissue(attempt: ReissueAttempt): Promise<void> {
  if (!attempt.promise) {
    const controller = new AbortController()
    attempt.controller = controller
    attempt.promise = (async () => {
      try {
        const token = await csrfToken()
        assertCurrentSession(attempt.session)
        await sessionClient.post('/auth/reissue', undefined, {
          headers: { 'X-XSRF-TOKEN': token },
          signal: controller.signal,
        })
        assertCurrentSession(attempt.session)
        authGeneration += 1
        reissueAttempt = { generation: authGeneration, session: authSession }
      } catch (error: unknown) {
        const normalized = toApiError(error)
        if (attempt === reissueAttempt && isTransientFailure(normalized)) {
          // 기존 요청은 실패 Promise를 계속 공유한다. 실패 이후 시작한 새 요청만 복구를 시도한다.
          reissueAttempt = { generation: authGeneration, session: authSession }
        }
        throw normalized
      } finally {
        attempt.controller = undefined
      }
    })()
  }
  return attempt.promise
}

apiClient.interceptors.request.use(async (config: RequestConfig) => {
  const url = config.url
  if (!url || !url.startsWith('/') || url.startsWith('//') || url.includes('\\') ||
      /^\/api(?:\/|\?|$)/.test(url) || config.baseURL !== '/api') {
    throw new ApiError('configuration', 'API 경로는 /api를 제외한 상대 경로를 사용해야 합니다.')
  }
  // 요청 시작 시 참조를 고정한다. 실패 뒤 늦게 도착한 401이 새 재발급을 시작하지 않게 한다.
  config._reissueAttempt = reissueAttempt
  if (!safeMethods.has(config.method?.toLowerCase() ?? 'get')) {
    config.headers.set('X-XSRF-TOKEN', await csrfToken())
  }
  assertCurrentSession(config._reissueAttempt.session)
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    const normalized = toApiError(error)
    const config = axios.isAxiosError(error) ? error.config as RequestConfig | undefined : undefined
    if (config?.requiresAuth && !config._authRetried && normalized.status === 401 &&
        (normalized.code === 'AUTH_006' || normalized.code === 'AUTH_007') &&
        config.url !== '/auth/reissue' && config.url !== '/auth/logout') {
      config._authRetried = true
      const attempt = config._reissueAttempt
      if (!attempt) throw normalized
      assertCurrentSession(attempt.session)
      if (attempt.generation === authGeneration) await reissue(attempt)
      assertCurrentSession(attempt.session)
      return apiClient.request(config)
    }
    throw normalized
  },
)
