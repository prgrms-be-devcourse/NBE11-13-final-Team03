export const impressionRatio = 0.5
export const impressionDelayMs = 1000

/**
 * 요소의 50% 이상이 1초 연속 보이면 onImpression을 한 번 호출하고 관찰을 끝낸다.
 * 비율이 떨어지거나 탭이 숨겨지면 연속 표시가 끊긴 것으로 보고 다시 센다. 정리 함수를 돌려준다.
 */
export function observeImpression(element: Element, onImpression: () => void): () => void {
  if (typeof IntersectionObserver === 'undefined') return () => {}
  let visible = false
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const stopTimer = () => {
    if (timer !== undefined) clearTimeout(timer)
    timer = undefined
  }
  const update = () => {
    if (stopped || !visible || document.visibilityState !== 'visible') return stopTimer()
    timer ??= setTimeout(() => {
      cleanup()
      onImpression()
    }, impressionDelayMs)
  }
  const observer = new IntersectionObserver(entries => {
    const entry = entries[entries.length - 1]
    visible = entry.isIntersecting && entry.intersectionRatio >= impressionRatio
    update()
  }, { threshold: impressionRatio })
  const cleanup = () => {
    stopped = true
    stopTimer()
    observer.disconnect()
    document.removeEventListener('visibilitychange', update)
  }
  observer.observe(element)
  document.addEventListener('visibilitychange', update)
  return cleanup
}
