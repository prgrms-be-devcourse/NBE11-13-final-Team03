import { useContext, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import { AuthContext } from '../../auth/AuthContext'
import { loginPath } from '../../auth/returnPath'
import { ApiError } from '../../api/ApiError'
import { PreferenceRefreshError, preferenceMutationKey, refreshPreferenceQueries, runPreferenceChange } from '../../api/preferences'
import type { PreferenceChange, PreferenceSource } from '../../api/preferences'
import Button from '../ui/Button'
import { Bookmark, RefreshCw } from 'lucide-react'
import styles from './PreferenceActions.module.css'

function errorMessage(error: Error): string {
  if (error instanceof PreferenceRefreshError) return error.message
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인 상태를 다시 확인해 주세요.'
    if (error.status === 404) {
      if (error.code === 'USER_001') return '사용자 정보를 확인하지 못했어요. 다시 로그인해 주세요.'
      if (error.code === 'PREFERENCE_001') return '공고를 찾을 수 없어요. 목록을 다시 확인해 주세요.'
      return '요청 경로나 대상 정보를 확인하지 못했어요. 상태를 다시 조회해 주세요.'
    }
    if (error.status === 409) return '다른 변경과 충돌했어요. 최신 상태를 다시 확인해 주세요.'
    if (error.status === 403 || error.kind === 'csrf') return '요청이 거부됐어요. 인증·보안 토큰 상태를 확인해 주세요.'
    if (error.status === 400) return '공고 번호와 요청 조건을 확인해 주세요.'
    if (error.kind === 'cancelled') return '인증 상태나 화면이 변경되어 결과 확인을 중단했어요.'
    if (['network', 'timeout', 'unknown'].includes(error.kind)) return '변경 완료 여부를 확인하지 못했어요. 연결을 확인하고 최신 상태를 다시 조회해 주세요.'
  }
  return '변경 또는 최신 상태 조회에 실패했어요. 상태를 다시 조회해 주세요.'
}
/** onHidden: 서버가 숨김 204를 확인한 뒤에만 호출한다(후속 목록 갱신만 실패한 경우 포함). 카드가 사라진 뒤에도 호출될 수 있다. */
type PreferenceActionsProps = { opportunityId: number; title: string; saved: boolean; source: PreferenceSource; allowHide?: boolean; disabled?: boolean; iconOnly?: boolean; showSaveLabel?: boolean; onHidden?: () => void }
function PreferenceActions({ opportunityId, title, saved, source, allowHide = false, disabled = false, iconOnly = false, showSaveLabel = false, onHidden }: PreferenceActionsProps) {
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
    // 숨김은 서버 반영(204)이 확인된 경우에만 알린다. PreferenceRefreshError는 반영 뒤 목록 갱신만 실패한 경우다.
    void mutation.mutateAsync(change).then(() => { if (change === 'hide') onHidden?.() }, (error: unknown) => { if (change === 'hide' && error instanceof PreferenceRefreshError) onHidden?.() })
      .finally(() => { running.current = false })
  }
  const returnTo = loginPath(location.pathname + location.search)
  const retry = () => {
    if (!owner || running.current || pending || disabled) return
    running.current = true
    void refreshPreferenceQueries(client, owner, opportunityId, mutation.variables ?? 'save')
      .then(() => mutation.reset()).catch(() => {}).finally(() => { running.current = false })
  }
  const icon = <Bookmark size={20} aria-hidden="true" fill={saved ? 'currentColor' : 'none'} />
  // 접근 가능한 이름에 화면에 보이는 문구를 포함한다.
  const saveName = showSaveLabel && !iconOnly ? (saved ? '저장됨, 저장 해제' : '저장하기') : (saved ? '저장 해제' : '저장')
  return <div className={`${styles.actions} ${iconOnly ? styles.compact : ''}`}>
    {!iconOnly && !showSaveLabel && <span>{saved ? '저장됨' : '저장 안 함'}</span>}
    {auth?.status === 'anonymous' ? <Link className={iconOnly ? styles.iconButton : showSaveLabel ? styles.saveButton : undefined} to={returnTo} aria-label={iconOnly ? `${title} ${saved ? '저장됨' : '저장 안 함'}, 저장하려면 로그인` : `${title} 저장하려면 로그인`}>{iconOnly ? icon : showSaveLabel ? <>{icon}저장하려면 로그인</> : '저장하려면 로그인'}</Link>
      : owner ? <>
        <Button className={iconOnly ? styles.iconButton : undefined} variant={iconOnly ? 'ghost' : 'secondary'} disabled={disabled || pending} loading={pending} aria-label={`${title} ${saveName}`} aria-pressed={saved} onClick={() => run(saved ? 'unsave' : 'save')}>{iconOnly ? icon : showSaveLabel ? <>{icon}{saved ? '저장됨' : '저장하기'}</> : saved ? '저장 해제' : '저장'}</Button>
        {allowHide && <Button variant="ghost" className={iconOnly ? styles.textButton : undefined} disabled={disabled || pending} aria-label={`${title} 추천에서 숨기기`} onClick={() => run('hide')}>{iconOnly ? '숨기기' : '추천에서 숨기기'}</Button>}
      </> : auth?.status === 'error' ? <><span>인증 확인 실패</span>{auth.retry && <Button variant="ghost" onClick={auth.retry}>로그인 상태 다시 확인</Button>}</>
        : <Button className={iconOnly ? styles.iconButton : undefined} variant="secondary" disabled aria-label={iconOnly ? `${title} 로그인 확인 중` : undefined} aria-pressed={iconOnly ? saved : undefined}>{iconOnly ? icon : '로그인 확인 중'}</Button>}
    {mutation.isError && owner && (iconOnly ? <span className={styles.compactError} role="alert">
      <span>확인 필요</span><span className={styles.srOnly}>{errorMessage(mutation.error)}</span>
      {mutation.error instanceof ApiError && mutation.error.status === 401 ? <Link to={returnTo} aria-label={`${title} 다시 로그인하기`}>로그인</Link>
        : <Button className={styles.iconButton} variant="ghost" disabled={pending || disabled} onClick={retry} aria-label={`${title} 상태 다시 조회`} title={errorMessage(mutation.error)}><RefreshCw size={16} aria-hidden="true" /></Button>}
    </span> : <div className={styles.error} role="alert"><p>{errorMessage(mutation.error)}</p>
      {mutation.error instanceof ApiError && mutation.error.status === 401 && <Link to={returnTo}>다시 로그인하기</Link>}
      <Button variant="ghost" disabled={pending || disabled} onClick={retry}>상태 다시 조회</Button>
    </div>)}
  </div>
}
export default PreferenceActions
