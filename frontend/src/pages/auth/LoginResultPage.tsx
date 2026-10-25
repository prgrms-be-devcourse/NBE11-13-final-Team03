import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../../auth/AuthContext'
import { consumeLoginReturn, loginPath, peekLoginReturn } from '../../auth/returnPath'
import { createOAuthCompletionRunner } from '../../auth/oauthCompletion'
import AsyncState from '../../components/ui/AsyncState'
import Card from '../../components/ui/Card'
import styles from './LoginPage.module.css'

function LoginResultPage({ success }: { success: boolean }) {
  const { completeOAuth } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<Error>()
  const completion = useMemo(() => createOAuthCompletionRunner(completeOAuth,
    () => navigate(consumeLoginReturn(), { replace: true }), setError), [completeOAuth, navigate])
  useEffect(() => {
    if (!success) return
    completion.activate()
    void completion.run()
    return () => { completion.deactivate() }
  }, [success, completion])
  function retry() {
    setError(undefined)
    void completion.run()
  }
  return <section className={styles.page} aria-labelledby="login-result-title"><Card className={styles.card}>
    <h1 id="login-result-title">{success ? '로그인 확인' : '로그인을 완료하지 못했어요'}</h1>
    {success ? error ? <AsyncState status="error" title="로그인 완료를 확인하지 못했어요" description="사용자 정보 확인에 실패했어요. 연결 상태를 확인하고 다시 확인하거나 카카오 로그인을 다시 진행해 주세요." onRetry={retry} />
      : <AsyncState status="loading" title="서버에서 로그인 상태를 확인하고 있어요" description="사용자 정보가 확인되면 이전 화면으로 이동해요." />
      : <p>로그인이 취소됐거나 처리 중 문제가 생겼을 수 있어요. 다시 시도하거나 공개 기회를 살펴보세요.</p>}
    {(!success || error) && <Link className={styles.link} to={loginPath(peekLoginReturn())}>카카오 로그인 다시 진행</Link>}
    <Link className={styles.link} to="/opportunities">공개 기회 탐색하기 →</Link>
  </Card></section>
}

export default LoginResultPage
