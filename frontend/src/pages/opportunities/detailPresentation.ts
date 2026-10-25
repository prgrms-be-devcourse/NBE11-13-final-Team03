import type { ApplicationWindow, ConditionItem, ConditionType, RegionItem, RequirementType, ValidationStatus } from '../../api/opportunityDetail.ts'
import { isValidDate, parseSearchParams, serializeSearchParams } from './searchParams.ts'

export const conditionLabels: Record<ConditionType, string> = {
  AGE: '연령', REGION: '지역', INCOME: '소득', PROPERTY: '재산', EMPLOYMENT: '취업 상태', EDUCATION: '학력·학적', MARITAL: '혼인 상태',
  HOUSING: '주거', SPECIALIZATION: '전공', SPECIAL_TARGET: '특화 대상', MILITARY_SERVICE: '병역', PARTICIPATION_HISTORY: '참여 이력',
  TRAINING_CARD: '국민내일배움카드', COMPOSITE: '복합 조건', OTHER: '기타 조건',
}
export const requirementLabels: Record<RequirementType, string> = { REQUIRED: '필수', PREFERRED: '우대', EXCLUSION: '제외 대상' }
export const conditionResultLabels: Record<string, string> = {
  MATCHED: '충족', NOT_MATCHED: '불충족', USER_INFO_MISSING: '사용자 정보 필요', CHECK_REQUIRED: '직접 확인 필요', DATA_UNCLEAR: '조건 정보 불명확', NOT_SPECIFIED: '조건 미명시',
}
const validationLabels: Record<ValidationStatus, string> = {
  VALID: '검증 완료', INVALID: '데이터 오류', DATA_CONFLICT: '정보 충돌', UNKNOWN: '미확인', NEEDS_REVIEW: '검토 필요', UNVERIFIED: '공식 근거 미확인',
}
export const validationLabel = (status?: ValidationStatus | null): string => status ? validationLabels[status] : '검증 정보 없음'

// 본문은 React 텍스트로 렌더링하고, 외부 링크만 명시적으로 허용한다.
export function safeExternalUrl(value?: string | null): string | undefined {
  if (!value || Array.from(value).some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127)) return undefined
  try {
    const url = new URL(value)
    if (!['https:', 'http:'].includes(url.protocol) || !url.hostname || url.username || url.password) return undefined
    return url.href
  } catch { return undefined }
}

export function searchReturnPath(state: unknown): string {
  if (!state || typeof state !== 'object' || !('searchReturn' in state) || typeof state.searchReturn !== 'string') return '/opportunities'
  const value = state.searchReturn
  if (value !== '/opportunities' && !value.startsWith('/opportunities?')) return '/opportunities'
  const query = serializeSearchParams(parseSearchParams(new URLSearchParams(value.split('?').slice(1).join('?'))).params).toString()
  return `/opportunities${query ? `?${query}` : ''}`
}

export function dateRange(start?: string | null, end?: string | null): string {
  if ((!start && !end) || (start && !isValidDate(start)) || (end && !isValidDate(end)) || (start && end && start > end)) return '일정 확인 필요'
  return start && end ? `${start} ~ ${end}` : start ? `${start}부터 (종료일 미제공)` : `${end}까지 (시작일 미제공)`
}
export function applicationWindowText(window: ApplicationWindow): string {
  if (window.validationStatus !== 'VALID') return `신청기간 확인 필요 · ${validationLabel(window.validationStatus)}`
  switch (window.periodType) {
    case 'ALWAYS': return '상시 신청'
    case 'CLOSED': return '신청 마감'
    case 'FIXED': return dateRange(window.startDate, window.endDate)
    default: return '신청기간 확인 필요'
  }
}

export function regionText(region: RegionItem, training: boolean): string {
  const role = { RESIDENCE: '거주지 조건', SCHOOL: '학교 소재지', BUSINESS: '사업장 소재지', IMPLEMENTATION: training ? '교육 시행 지역' : '정책 시행 지역', UNKNOWN: '역할 확인 필요' }[region.role]
  if (region.validationStatus !== 'VALID') return `${role}: 지역 확인 필요 · ${validationLabel(region.validationStatus)}`
  if (region.status === 'NATIONAL') return `${role}: 전국`
  if (region.status === 'SPECIFIC' && region.name?.trim()) return `${role}: ${region.name}`
  return `${role}: 지역 확인 필요`
}

// 백엔드 youth-policy-codes.yaml의 내부 값에 대응한다. 미지원 코드는 원문 확인으로 남긴다.
const codeLabels: Record<string, string> = {
  EMPLOYED: '재직자', SELF_EMPLOYED: '자영업자', UNEMPLOYED: '미취업자', FREELANCER: '프리랜서', DAY_LABORER: '일용근로자', STARTUP_PREPARING: '예비 창업자', PART_TIME: '단기근로자', FARMER: '영농종사자',
  BELOW_HIGH_SCHOOL: '고졸 미만', HIGH_SCHOOL_ENROLLED: '고교 재학', HIGH_SCHOOL_EXPECTED_GRADUATION: '고졸 예정', HIGH_SCHOOL_GRADUATED: '고교 졸업',
  UNIVERSITY_ENROLLED: '대학 재학', UNIVERSITY_EXPECTED_GRADUATION: '대졸 예정', UNIVERSITY_GRADUATED: '대학 졸업', GRADUATE_SCHOOL: '석·박사',
  MARRIED: '기혼', SINGLE: '미혼', HUMANITIES: '인문계열', SOCIAL_SCIENCE: '사회계열', BUSINESS_ECONOMICS: '상경계열', NATURAL_SCIENCE: '이학계열',
  ENGINEERING: '공학계열', ARTS_AND_PHYSICAL: '예체능계열', AGRICULTURE: '농산업계열',
  SME_EMPLOYEE: '중소기업 재직자', WOMAN: '여성', BASIC_LIVELIHOOD_RECIPIENT: '기초생활수급자', SINGLE_PARENT: '한부모가정', DISABLED: '장애인', SOLDIER: '군인', LOCAL_TALENT: '지역인재',
}

function validBounds(c: ConditionItem): boolean {
  if ((c.min != null && c.min < 0) || (c.max != null && c.max < 0) || (c.min != null && c.max != null && c.min > c.max)) return false
  return (c.status === 'RANGE' && c.min != null && c.max != null) ||
    (c.status === 'MIN_ONLY' && c.min != null && c.max == null) || (c.status === 'MAX_ONLY' && c.max != null && c.min == null)
}
function incomeText(c: ConditionItem): string | undefined {
  if (!validBounds(c) || c.unitConfirmed !== true || (c.min != null && c.minInclusive == null) || (c.max != null && c.maxInclusive == null)) return undefined
  const subject = c.subject === 'HOUSEHOLD' ? '가구' : c.subject === 'INDIVIDUAL' ? '본인' : undefined
  if (!subject) return undefined
  const period = c.period === 'YEARLY' ? '연' : c.period === 'MONTHLY' ? '월' : undefined
  let prefix: string, unit: string
  switch (c.incomeType) {
    case 'AMOUNT':
      if (!period) return undefined
      prefix = `${subject} ${period}소득`; unit = '원'; break
    case 'MEDIAN_PERCENT': prefix = `${subject} 기준중위소득`; unit = '%'; break
    case 'STUDENT_LOAN_BRACKET': prefix = `${subject} 학자금 지원구간`; unit = '구간'; break
    default: return undefined
  }
  const lower = c.min != null ? `${c.min.toLocaleString('ko-KR')}${unit} ${c.minInclusive ? '이상' : '초과'}` : ''
  const upper = c.max != null ? `${c.max.toLocaleString('ko-KR')}${unit} ${c.maxInclusive ? '이하' : '미만'}` : ''
  return `${prefix} ${[lower, upper].filter(Boolean).join(' · ')}`
}

export function conditionPresentation(c: ConditionItem): { confirmed: boolean; text: string } {
  const review = { confirmed: false, text: '원문에서 조건 확인 필요' }
  if (c.validationStatus !== 'VALID') return review
  if (c.status === 'NO_LIMIT') return { confirmed: true, text: '해당 조건 제한 없음' }
  if (c.type === 'AGE') {
    if (c.status === 'DATE_BOUND') {
      const dates = [c.bornOnOrAfter, c.bornOnOrBefore]
      if (!dates.some(Boolean) || dates.some((date) => date && !isValidDate(date)) || (c.bornOnOrAfter && c.bornOnOrBefore && c.bornOnOrAfter > c.bornOnOrBefore)) return review
      return { confirmed: true, text: [c.bornOnOrAfter && `${c.bornOnOrAfter} 이후 출생 (포함)`, c.bornOnOrBefore && `${c.bornOnOrBefore} 이전 출생 (포함)`].filter(Boolean).join(' · ') }
    }
    if (!validBounds(c) || [c.min, c.max].some((value) => value != null && !Number.isSafeInteger(value))) return review
    return { confirmed: true, text: [c.min != null && `만 ${c.min}세 이상`, c.max != null && `만 ${c.max}세 이하`].filter(Boolean).join(' · ') }
  }
  if (c.type === 'INCOME') {
    const text = incomeText(c)
    return text ? { confirmed: true, text } : review
  }
  if (c.type === 'REGION') return { confirmed: true, text: '아래 지역 정보에서 역할과 적용 범위를 확인해 주세요.' }
  if (['EMPLOYMENT', 'EDUCATION', 'MARITAL', 'SPECIALIZATION', 'SPECIAL_TARGET'].includes(c.type) && c.status === 'SPECIFIC' && c.values.length && c.values.every((value) => Object.hasOwn(codeLabels, value))) {
    return { confirmed: true, text: c.values.map((value) => codeLabels[value]).join(' · ') }
  }
  return review
}
