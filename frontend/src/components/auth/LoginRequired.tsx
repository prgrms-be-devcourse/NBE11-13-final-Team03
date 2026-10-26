import { Link } from 'react-router-dom'
import { loginPath } from '../../auth/returnPath'
import AsyncState from '../ui/AsyncState'
import styles from './LoginRequired.module.css'

// 비로그인 상태 안내. 인증 확인 중·오류 상태는 각 화면에서 따로 보여 준다.
function LoginRequired({ description, returnTo }: { description: string; returnTo: string }) {
  return <AsyncState status="empty" title="로그인이 필요해요" description={description}
    action={<Link className={styles.loginLink} to={loginPath(returnTo)}>카카오로 로그인하기</Link>} />
}

export default LoginRequired
