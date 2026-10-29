import { Link, useLocation } from 'react-router-dom'
import { ApiError } from '../../api/ApiError'
import { loginPath } from '../../auth/returnPath'
import AsyncState from '../../components/ui/AsyncState'

function profileErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '로그인이 필요해요. 로그인 상태를 다시 확인해 주세요.'
    if (error.status === 403 || error.kind === 'csrf') return '요청이 거부되었어요. 인증·보안 토큰 상태를 확인한 뒤 다시 시도해 주세요.'
    if (error.status === 400) return '입력값을 확인해 주세요. 표시된 항목과 조건을 확인한 뒤 다시 저장해 주세요.'
    if (error.kind === 'network') return '서버에 연결하지 못했어요. 네트워크 연결을 확인해 주세요.'
    if (error.kind === 'timeout') return '응답 시간이 초과됐어요. 저장 요청이었다면 다시 조회해 실제 반영 여부를 확인해 주세요.'
    if (error.kind === 'cancelled') return '인증 상태 또는 화면이 변경되어 결과 표시를 중단했어요. 다시 조회해 주세요.'
  }
  return '정보를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
}
function ProfileError({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const location = useLocation()
  return <><AsyncState status="error" title={profileErrorMessage(error)} onRetry={onRetry} />{error instanceof ApiError && error.status === 401 && <Link to={loginPath(location.pathname)}>다시 로그인하기</Link>}</>
}
export default ProfileError
