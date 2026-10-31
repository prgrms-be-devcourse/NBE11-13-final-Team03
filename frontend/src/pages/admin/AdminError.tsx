import { Link, useLocation } from 'react-router-dom'
import { adminErrorKind, adminErrorMessage } from '../../api/admin'
import { loginPath } from '../../auth/returnPath'
import Button from '../../components/ui/Button'
import styles from './Admin.module.css'

// 관리자 API 오류 안내. 401은 재로그인, 권한 거부는 AdminLayout이 화면을 닫으므로 다시 시도를 두지 않는다.
// title은 조회 실패를 '결과 0건'과 구분해 무엇을 못 불러왔는지 알린다.
function AdminError({ error, onRetry, title }: { error: unknown; onRetry?: () => void; title?: string }) {
  const location = useLocation()
  const kind = adminErrorKind(error)
  return <div className={styles.errorBox} role="alert">
    {title && <p className={styles.errorTitle}>{title}</p>}
    <p>{adminErrorMessage(error)}</p>
    {kind === 'login' && <Link to={loginPath(location.pathname)}>다시 로그인하기</Link>}
    {onRetry && kind !== 'denied' && kind !== 'login' && <Button variant="secondary" onClick={onRetry}>다시 시도</Button>}
  </div>
}
export default AdminError
