/** register() can resolve while installation is still running. PushManager needs an activated worker. */
export function waitForWorkerActivation(registration, timeoutMs = 30000) {
  const worker = registration.installing || registration.waiting || registration.active;
  if (!worker) return Promise.reject(new Error('서비스 워커가 등록되지 않았습니다. 다시 시도하세요.'));
  if (worker.state === 'activated') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const finish = (error) => {
      clearTimeout(timer);
      worker.removeEventListener('statechange', changed);
      if (error) reject(error);
      else resolve();
    };
    const changed = () => {
      if (worker.state === 'activated') finish();
      else if (worker.state === 'redundant') finish(new Error('서비스 워커 설치가 실패했습니다. 네트워크·브라우저 콘솔을 확인하세요.'));
    };
    const timer = setTimeout(() => finish(new Error('서비스 워커 활성화 대기 시간이 초과됐습니다. 다시 시도하세요.')), timeoutMs);
    worker.addEventListener('statechange', changed);
    changed();
  });
}
