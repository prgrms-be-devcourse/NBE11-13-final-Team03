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
    /**
     * 비회원도 조회할 수 있지만 로그인 사용자에게는 개인화(찜 여부 등)가 붙는 공개 조회에 지정한다.
     * 만료·잘못된 Access Token의 401 + AUTH_006/AUTH_007이면 requiresAuth와 같은 재발급 가드로 1회 재발급한다.
     * 재발급 성공이면 같은 요청을 재전송하고, 재발급이 401로 확정 실패하면 인증 손실을 알린 뒤 같은 요청을 익명으로 1회 재전송한다.
     * 재발급이 네트워크·타임아웃·5xx 등으로 불확실하면 익명으로 바꾸지 않고 그 오류를 그대로 낸다.
     * 다른 탭과의 재발급 충돌(AUTH_004)은 두 옵션 모두 그 탭의 새 쿠키로 같은 요청을 1회 재전송하고 실패로 고정하지 않는다.
     * requiresAuth가 함께 지정되면 requiresAuth의 규칙이 우선한다.
     */
    recoverAuth?: boolean
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
  /** 이 시도의 401 확정 실패를 인증 손실 listener에 한 번만 알렸다. */
  lossNotified?: boolean
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
let authLostListener: (() => void) | undefined

/**
 * recoverAuth 공개 조회의 재발급이 401로 확정 실패했을 때 한 번 호출된다(동시 요청 여러 개여도 재발급 시도당 1회).
 * 세션 Query 소유자가 등록해 인증 상태 재확인·개인화 캐시 정리를 수행한다. 해제 함수를 돌려준다.
 */
export function onAuthLost(listener: () => void): () => void {
  authLostListener = listener
  return () => { if (authLostListener === listener) authLostListener = undefined }
}

function assertCurrentSession(session: number): void {
  if (session !== authSession) {
    throw new ApiError('cancelled', '인증 상태가 변경되어 요청을 취소했습니다.')
  }
}

/**
 * 재발급 401 중 세션 상실로 확정된 실패. 저장 토큰 불일치(AUTH_004)는 다른 탭의 동시 재발급에 진 경우라
 * 브라우저가 이미 그 탭의 새 쿠키를 갖고 있을 수 있어 확정 실패로 보지 않는다.
 */
function isTerminalReissueFailure(error: ApiError): boolean {
  return error.status === 401 && error.code !== 'AUTH_004'
}

function isRecoverable401(config: RequestConfig, error: ApiError): boolean {
  return !config._authRetried && error.status === 401 && (error.code === 'AUTH_006' || error.code === 'AUTH_007') &&
    config.url !== '/auth/reissue' && config.url !== '/auth/logout'
}

/** 서버에서 재로그인 성공을 확인한 뒤에만 호출한다. 단순 401/네트워크 오류에는 호출하지 않는다. */
export function resetAuthRecovery(): void {
  advanceAuthSession()
}

/** 서버 로그아웃 204 확인 후 이전 세션의 재발급·늦은 401을 차단한다. 로그인 성공을 의미하지 않는다. */
export function endAuthSession(): void {
  advanceAuthSession()
}

function advanceAuthSession(): void {
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
        // 세션 상실로 확정된 401은 재로그인 확인 전까지 유지한다.
        // 그 밖의 실패(네트워크·5xx·CSRF 403·다른 탭과의 재발급 충돌 등)는 고정하지 않는다.
        if (attempt === reissueAttempt && !isTerminalReissueFailure(normalized)) {
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
    if (!config || !(config.requiresAuth || config.recoverAuth) || !isRecoverable401(config, normalized)) throw normalized
    config._authRetried = true
    const attempt = config._reissueAttempt
    if (!attempt) throw normalized
    assertCurrentSession(attempt.session)
    try {
      if (attempt.generation === authGeneration) await reissue(attempt)
    } catch (failure: unknown) {
      const reissueError = toApiError(failure)
      // 다른 탭과의 재발급 충돌(AUTH_004)은 그 탭의 새 쿠키로 같은 요청을 다시 보낸다. 세션 상실이 아니므로 알리지 않는다.
      if (reissueError.status === 401 && reissueError.code === 'AUTH_004') {
        assertCurrentSession(attempt.session)
        return apiClient.request(config)
      }
      // 세션 상실로 확정된 401만 익명으로 대체한다. 네트워크·타임아웃·5xx·취소는 오류를 그대로 낸다.
      if (config.requiresAuth || reissueError.status !== 401) throw reissueError
      assertCurrentSession(attempt.session)
      if (!attempt.lossNotified) {
        attempt.lossNotified = true
        authLostListener?.()
      }
    }
    assertCurrentSession(attempt.session)
    // 재발급 성공이면 새 쿠키로, 확정 실패(recoverAuth)면 비회원으로 같은 요청을 1회 재전송한다.
    return apiClient.request(config)
  },
)
