// 인증 작업은 계속 완료할 수 있지만, 이 화면을 떠난 요청의 UI 부작용은 실행하지 않는다.
// complete의 결과(예: 이동할 화면 판단)는 현재 화면이 유효할 때만 onSuccess에 전달된다.
export function createOAuthCompletionRunner<T = void>(complete: () => Promise<T>, onSuccess: (result: T) => void, onError: (error: Error) => void) {
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
      pending = Promise.resolve().then(complete).then((result) => {
        if (current()) onSuccess(result)
      }).catch((reason: unknown) => {
        if (current()) onError(reason instanceof Error ? reason : new Error('로그인 확인에 실패했습니다.'))
      }).finally(() => {
        if (request === version) pending = undefined
      })
      return pending
    },
  }
}
