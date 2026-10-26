import { Link, useSearchParams } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { peekLoginReturn, safeReturnPath, saveLoginReturn } from '../../auth/returnPath'
import Card from '../../components/ui/Card'
import kakaoLogin from '../../assets/kakao/kakao_login_kr_large.svg'
import styles from './LoginPage.module.css'

function LoginPage() {
  const [params] = useSearchParams()
  const auth = useAuth()
  const returnTo = safeReturnPath(params.get('returnTo') ?? peekLoginReturn())
  return <section className={styles.page} aria-labelledby="login-title"><Card className={styles.card}>
    <span className={styles.eyebrow}>Y:ON 로그인</span>
    <h1 id="login-title">나에게 맞는 기회를 이어서 만나세요</h1>
    <p>카카오 계정으로 로그인하고 개인화 추천을 확인하세요.<br />추천을 받으려면 판정 프로필 설정이 필요해요.</p>
    {auth.status === 'authenticated' && <p className={styles.notice}>현재 로그인되어 있어요. <Link to={returnTo}>이전 화면으로 이동</Link>하거나 카카오 로그인을 다시 진행할 수 있어요.</p>}
    <a className={styles.kakao} href="/oauth2/authorization/kakao" onClick={() => saveLoginReturn(returnTo)}><img src={kakaoLogin} alt="카카오로 로그인" width={336} height={46} /></a>
    <p className={styles.help}>카카오에서 동의한 닉네임·이메일 정보를 계정 식별과 사용자 정보 표시에 사용해요.<br />비밀번호는 Y:ON에 입력하지 않아요.</p>
    <Link className={styles.link} to="/opportunities">로그인 없이 기회 탐색하기 →</Link>
  </Card></section>
}

export default LoginPage
