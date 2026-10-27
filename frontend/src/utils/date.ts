// 화면 표시용 날짜 형식. 원본 값과 검색 URL의 YYYY-MM-DD는 바꾸지 않는다.
// - 날짜만 있는 값과 시간대 표기가 없는 서버 현지 시각(LocalDateTime)은 숫자를 그대로 쓴다(시간대 변환으로 날짜가 바뀌지 않게).
// - 시간대가 있는 시각(Z, +09:00 등)은 서울 기준 24시간제로 바꾼다. 브라우저 지역 설정과 무관하다.
const LOCAL = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/
const ZONED = /T.*(?:Z|[+-]\d{2}:?\d{2})$/i
const seoul = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

type Parts = { year: string; month: string; day: string; hour?: string; minute?: string }
function parts(value: string): Parts | null {
  if (ZONED.test(value)) {
    const time = Date.parse(value)
    if (!Number.isFinite(time)) return null
    const part = Object.fromEntries(seoul.formatToParts(time).map(({ type, value: text }) => [type, text]))
    return { year: part.year, month: part.month, day: part.day, hour: part.hour, minute: part.minute }
  }
  const match = LOCAL.exec(value)
  return match ? { year: match[1], month: match[2], day: match[3], hour: match[4], minute: match[5] } : null
}

/** YYYY.MM.DD. 형식을 해석할 수 없으면 원본을 그대로 돌려준다. */
export function formatDate(value: string): string {
  const date = parts(value)
  return date ? `${date.year}.${date.month}.${date.day}` : value
}

/** YYYY.MM.DD HH:mm(24시간제). 시각이 없으면 날짜만 돌려준다. */
export function formatDateTime(value: string): string {
  const date = parts(value)
  if (!date) return value
  return date.hour && date.minute ? `${date.year}.${date.month}.${date.day} ${date.hour}:${date.minute}` : `${date.year}.${date.month}.${date.day}`
}

/** 문장 안의 YYYY-MM-DD를 모두 YYYY.MM.DD로 바꾼다. 예: "2026-10-01 ~ 2026-10-31" */
export function formatDatesInText(text: string): string {
  return text.replace(/\b(\d{4})-(\d{2})-(\d{2})\b/g, '$1.$2.$3')
}
