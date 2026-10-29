import { parseSearchParams, serializeSearchParams } from '../pages/opportunities/searchParams.ts'
import { parsePushClick, pushClickPath } from '../push/protocol.ts'

const returnKey = 'yon.oauth.return'
export function safeReturnPath(value?: string | null): string {
  if (!value || value.length > 2000 || !value.startsWith('/') || value.startsWith('//') || value.includes('\\') ||
    Array.from(value).some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)) return '/'
  try {
    const url = new URL(value, 'https://yon.invalid')
    if (url.origin !== 'https://yon.invalid') return '/'
    if (url.pathname === '/notifications') {
      const click = parsePushClick(url.search)
      return click ? pushClickPath(click) : '/notifications'
    }
    if (url.pathname === '/opportunities') {
      const query = serializeSearchParams(parseSearchParams(url.searchParams).params).toString()
      return `/opportunities${query ? `?${query}` : ''}`
    }
    if (['/', '/saved', '/onboarding', '/mypage', '/mypage/profile', '/mypage/interests', '/notifications', '/settings/notifications'].includes(url.pathname) || /^\/opportunities\/[1-9]\d*$/.test(url.pathname)) return url.pathname
  } catch { /* 잘못된 URL은 홈으로 복귀한다. */ }
  return '/'
}
export function loginPath(path: string): string {
  return `/login?${new URLSearchParams({ returnTo: safeReturnPath(path) })}`
}
export function saveLoginReturn(path: string): void {
  try { sessionStorage.setItem(returnKey, safeReturnPath(path)) } catch { /* 저장 불가 시 완료 후 홈으로 이동한다. */ }
}
export function peekLoginReturn(): string {
  try { return safeReturnPath(sessionStorage.getItem(returnKey)) } catch { return '/' }
}
export function consumeLoginReturn(): string {
  try {
    const path = peekLoginReturn()
    sessionStorage.removeItem(returnKey)
    return safeReturnPath(path)
  } catch { return '/' }
}
