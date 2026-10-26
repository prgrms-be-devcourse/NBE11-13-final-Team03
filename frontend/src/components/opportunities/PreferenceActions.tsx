import { useContext, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import { AuthContext } from '../../auth/AuthContext'
import { loginPath } from '../../auth/returnPath'
import { ApiError } from '../../api/ApiError'
import { preferenceMutationKey, refreshPreferenceQueries, runPreferenceChange } from '../../api/preferences'
import type { PreferenceChange, PreferenceSource } from '../../api/preferences'
import Button from '../ui/Button'
import styles from './PreferenceActions.module.css'

function errorMessage(error: Error): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인 상태를 다시 확인해 주세요.'
    if (error.status === 404) return error.code === 'USER_001' ? '사용자 정보를 확인하지 못했어요. 다시 로그인해 주세요.' : '공고를 찾을 수 없어요. 목록을 다시 확인해 주세요.'
    if (error.status === 403 || error.kind === 'csrf') return '요청이 거부됐어요. 인증·보안 토큰 상태를 확인해 주세요.'
    if (error.status === 400) return '공고 번호와 요청 조건을 확인해 주세요.'
    if (error.kind === 'cancelled') return '인증 상태나 화면이 변경되어 결과 확인을 중단했어요.'
    if (['network', 'timeout', 'unknown'].includes(error.kind)) return '변경 완료 여부를 확인하지 못했어요. 연결을 확인하고 최신 상태를 다시 조회해 주세요.'
  }
  return '변경 또는 최신 상태 조회에 실패했어요. 상태를 다시 조회해 주세요.'
}
type PreferenceActionsProps = { opportunityId: number; title: string; saved: boolean; source: PreferenceSource; allowHide?: boolean; disabled?: boolean }
function PreferenceActions({ opportunityId, title, saved, source, allowHide = false, disabled = false }: PreferenceActionsProps) {
  const auth = useContext(AuthContext)
  const owner = auth?.status === 'authenticated' ? auth.user : undefined
  const client = useQueryClient()
  const location = useLocation()
  const running = useRef(false)
  const key = preferenceMutationKey(owner?.id, opportunityId)
  const mutation = useMutation({ mutationKey: key, mutationFn: (change: PreferenceChange) => {
    if (!owner) return Promise.reject(new ApiError('cancelled', '로그인 상태를 확인해 주세요.'))
    // 카드가 사라지거나 페이지가 바뀌어도 전송한 변경은 끝까지 확인해 현재 화면의 캐시를 갱신한다.
    // 계정 전환은 서비스의 owner 검사로 차단하며, UI 생존 여부로 서버 작업을 성공/실패 처리하지 않는다.
    return runPreferenceChange(client, owner, opportunityId, change, source)
  } })
  const pending = useIsMutating({ mutationKey: key }) > 0
  const run = (change: PreferenceChange) => {
    if (running.current || pending || disabled) return
    running.current = true
    void mutation.mutateAsync(change).catch(() => {}).finally(() => { running.current = false })
  }
  const returnTo = loginPath(location.pathname + location.search)
  return <div className={styles.actions}>
    <span>{saved ? '저장됨' : '저장 안 함'}</span>
    {auth?.status === 'anonymous' ? <Link to={returnTo} aria-label={`${title} 저장하려면 로그인`}>저장하려면 로그인</Link>
      : owner ? <>
        <Button variant="secondary" disabled={disabled || pending} loading={pending} aria-label={`${title} ${saved ? '저장 해제' : '저장'}`} aria-pressed={saved} onClick={() => run(saved ? 'unsave' : 'save')}>{saved ? '저장 해제' : '저장'}</Button>
        {allowHide && <Button variant="ghost" disabled={disabled || pending} aria-label={`${title} 추천에서 숨기기`} onClick={() => run('hide')}>추천에서 숨기기</Button>}
      </> : auth?.status === 'error' ? <><span>인증 확인 실패</span>{auth.retry && <Button variant="ghost" onClick={auth.retry}>로그인 상태 다시 확인</Button>}</>
        : <Button variant="secondary" disabled>로그인 확인 중</Button>}
    {mutation.isError && owner && <div className={styles.error} role="alert"><p>{errorMessage(mutation.error)}</p>
      {mutation.error instanceof ApiError && mutation.error.status === 401 && <Link to={returnTo}>다시 로그인하기</Link>}
      <Button variant="ghost" disabled={pending} onClick={() => { void refreshPreferenceQueries(client, owner, opportunityId, mutation.variables ?? 'save').then(() => mutation.reset()).catch(() => {}) }}>상태 다시 조회</Button>
    </div>}
  </div>
}
export default PreferenceActions
