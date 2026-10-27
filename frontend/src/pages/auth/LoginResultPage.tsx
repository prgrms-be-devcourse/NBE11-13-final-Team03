import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthContext'
import { needsProfileSetup } from '../../api/profile'
import { consumeLoginReturn, loginPath, peekLoginReturn } from '../../auth/returnPath'
import { createOAuthCompletionRunner } from '../../auth/oauthCompletion'
import AsyncState from '../../components/ui/AsyncState'
import Card from '../../components/ui/Card'
import styles from './LoginPage.module.css'

function LoginResultPage({ success }: { success: boolean }) {
  const { completeOAuth } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<Error>()
  const client = useQueryClient()
  // 맞춤 조건이 없으면 첫 화면을 맞춤 조건 설정으로 한다. 저장된 복귀 경로는 성공 시 함께 소비한다.
  const completion = useMemo(() => createOAuthCompletionRunner(
    async () => { await completeOAuth(); return needsProfileSetup(client) },
    (setup) => { const returnTo = consumeLoginReturn(); navigate(setup ? '/onboarding' : returnTo, { replace: true }) }, setError,
  ), [completeOAuth, navigate, client])
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
