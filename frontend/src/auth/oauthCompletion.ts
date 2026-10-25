// 인증 작업은 계속 완료할 수 있지만, 이 화면을 떠난 요청의 UI 부작용은 실행하지 않는다.
export function createOAuthCompletionRunner(complete: () => Promise<void>, onSuccess: () => void, onError: (error: Error) => void) {
  let active = false
  let version = 0
  let pending: Promise<void> | undefined
  return {
    activate() { active = true },
    deactivate() { active = false; version++; pending = undefined },
    run(): Promise<void> {
      if (!active) return Promise.resolve()
      if (pending) return pending
      const request = ++version
      const current = () => active && version === request
      pending = Promise.resolve().then(complete).then(() => {
        if (current()) onSuccess()
      }).catch((reason: unknown) => {
        if (current()) onError(reason instanceof Error ? reason : new Error('로그인 확인에 실패했습니다.'))
      }).finally(() => {
        if (request === version) pending = undefined
      })
      return pending
    },
  }
}
