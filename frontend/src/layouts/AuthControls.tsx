import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { loginPath } from '../auth/returnPath'
import { ApiError } from '../api/ApiError'
import Button from '../components/ui/Button'
import styles from './Header.module.css'

function AuthControls({ menuOpen = true }: { menuOpen?: boolean }) {
  const auth = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const logout = () => { void auth.logout().then(() => navigate('/', { replace: true })).catch(() => {}) }
  const uncertain = auth.logoutError instanceof ApiError && ['network', 'timeout', 'unknown'].includes(auth.logoutError.kind)
  return <><div className={`${styles.auth} ${menuOpen ? styles.authOpen : ''}`}>
    {auth.logoutPending ? <Button variant="ghost" disabled loading>로그아웃 중</Button>
      : auth.status === 'checking' ? <span role="status">로그인 상태 확인 중</span>
        : auth.status === 'error' ? <><span>인증 확인 실패</span>{auth.retry ? <Button variant="ghost" onClick={auth.retry}>다시 확인</Button> : <span>로그인 완료 화면에서 다시 확인해 주세요.</span>}</>
          : auth.status === 'anonymous' ? <Link className={styles.menuLink} to={loginPath(location.pathname + location.search)}>로그인</Link>
            : <><span className={styles.nickname}>{auth.user?.nickname?.trim() || '회원'}님</span><Button variant="ghost" onClick={logout}>로그아웃</Button></>}
  </div>
    {auth.logoutError && <div className={`${styles.authError} ${menuOpen ? styles.authErrorOpen : ''}`} role="alert"><p>{uncertain ? '서버 로그아웃 완료 여부를 확인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.' : '로그아웃 요청이 완료되지 않았어요. 인증 상태와 요청 설정을 확인한 뒤 다시 시도해 주세요.'}</p>{auth.retry && <Button variant="ghost" onClick={auth.retry}>로그인 상태 다시 확인</Button>}</div>}
  </>
}

export default AuthControls
