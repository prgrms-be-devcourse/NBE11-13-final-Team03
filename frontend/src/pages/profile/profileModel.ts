import { regionSelectionError } from './regionSelection.ts'
import type { RegionSource } from './regionSelection.ts'

// EligibilityProfileUpdateRequest, ProfileAnswers 및 domain/entity enum 기준이다.
export const choices = {
  employmentStatus: { UNEMPLOYED: '미취업', EMPLOYED: '재직', SELF_EMPLOYED: '자영업', FREELANCER: '프리랜서', PART_TIME: '단기근로·아르바이트', STARTUP_PREPARING: '창업 준비', DAY_LABORER: '일용근로', FARMER: '농업인' },
  educationLevel: { MIDDLE_SCHOOL_OR_BELOW: '중학교 졸업 이하', HIGH_SCHOOL: '고등학교', JUNIOR_COLLEGE: '전문대학', UNIVERSITY: '대학교', GRADUATE_SCHOOL: '대학원' },
  enrollmentStatus: { ENROLLED: '재학', LEAVE_OF_ABSENCE: '휴학', EXPECTED_GRADUATION: '졸업 예정', GRADUATED: '졸업', DROPPED_OUT: '중퇴' },
  maritalStatus: { SINGLE: '미혼', MARRIED: '기혼', UNKNOWN: '모름' },
  housingStatus: { OWNED: '자가', JEONSE: '전세', MONTHLY_RENT: '월세', DORMITORY: '기숙사', WITH_FAMILY: '가족과 거주', OTHER: '기타' },
  yesNo: { YES: '예', NO: '아니오', UNKNOWN: '모름' },
  majorField: { HUMANITIES: '인문', SOCIAL_SCIENCE: '사회과학', BUSINESS_ECONOMICS: '경영·경제', NATURAL_SCIENCE: '자연과학', ENGINEERING: '공학', ARTS_AND_PHYSICAL: '예체능', AGRICULTURE: '농학', OTHER: '기타' },
  trainingCardStatus: { ISSUED: '발급', APPLYING: '신청 중', NOT_ISSUED: '미발급', UNKNOWN: '모름' },
  trainingParticipation: { NONE: '없음', IN_PROGRESS: '참여 중', COMPLETED: '수료', DROPPED_OUT: '중도 이탈', UNKNOWN: '모름' },
  military: { COMPLETED: '복무 완료', SERVING: '복무 중', NOT_COMPLETED: '미필', EXEMPTED: '면제', NOT_APPLICABLE: '해당 없음', UNKNOWN: '모름' },
  targets: { SME_EMPLOYEE: '중소기업 근로자', WOMAN: '여성', BASIC_LIVELIHOOD_RECIPIENT: '기초생활수급자', SINGLE_PARENT: '한부모', DISABLED: '장애인', FARMER: '농업인', SOLDIER: '군인', LOCAL_TALENT: '지역인재', NEAR_POVERTY: '차상위계층' },
  answer: { PROVIDED: '값 입력', UNKNOWN: '모름', NOT_APPLICABLE: '해당 없음' },
  period: { MONTHLY: '월 소득', YEARLY: '연 소득' },
} as const
type Key<T extends keyof typeof choices> = keyof typeof choices[T]
export type AnswerStatus = Key<'answer'>
export type Income = { status: 'PROVIDED' | 'UNKNOWN'; amount: number | null; period: Key<'period'> | null }
export type ProfileInput = {
  birthDate: string; residenceRegionCode: string; employmentStatus: Key<'employmentStatus'> | null; educationLevel: Key<'educationLevel'> | null
  enrollmentStatus: Key<'enrollmentStatus'> | null; schoolRegionCode: string | null
  maritalStatus: Key<'maritalStatus'> | null; housingStatus: Key<'housingStatus'> | null; homelessStatus: Key<'yesNo'> | null
  personalIncome: Income | null; householdIncome: Income | null
  medianIncome: { status: AnswerStatus; percent: number | null } | null
  studentLoanBracket: { status: AnswerStatus; bracket: number | null } | null
  householdSize: number | null; householderStatus: Key<'yesNo'> | null; majorField: Key<'majorField'> | null
  specialTargets: { status: AnswerStatus; targets: Key<'targets'>[] } | null
  militaryService: { status: Key<'military'>; months: number | null } | null
  trainingCardStatus: Key<'trainingCardStatus'> | null; kdtParticipation: Key<'trainingParticipation'> | null; otherTrainingParticipation: Key<'trainingParticipation'> | null
}
export type Profile = ProfileInput & { updatedAt: string | null }
export const emptyProfile: ProfileInput = {
  birthDate: '', residenceRegionCode: '', employmentStatus: null, educationLevel: null, enrollmentStatus: null, schoolRegionCode: null,
  maritalStatus: null, housingStatus: null, homelessStatus: null, personalIncome: null, householdIncome: null,
  medianIncome: null, studentLoanBracket: null, householdSize: null, householderStatus: null, majorField: null,
  specialTargets: null, militaryService: null, trainingCardStatus: null, kdtParticipation: null, otherTrainingParticipation: null,
}
export function todayInKorea(): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}
export function validateProfile(value: ProfileInput, today = todayInKorea()): Record<string, string> {
  const errors: Record<string, string> = {}
  const fail = (key: string, message: string) => { errors[key] = message }
  const date = new Date(`${value.birthDate}T00:00:00Z`)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value.birthDate) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value.birthDate || value.birthDate < '1900-01-01' || value.birthDate > today) fail('birthDate', '1900년 1월 1일부터 오늘까지의 올바른 날짜를 입력해 주세요.')
  if (!value.residenceRegionCode) fail('residenceRegionCode', '유효한 거주지역 선택지가 필요해요.')
  if (!value.employmentStatus) fail('employmentStatus', '고용 상태를 선택해 주세요.')
  if (!value.educationLevel) fail('educationLevel', '최종 학력을 선택해 주세요.')
  for (const key of ['personalIncome', 'householdIncome'] as const) {
    const income = value[key]
    if (income?.status === 'PROVIDED' && (income.amount === null || !Number.isSafeInteger(income.amount) || income.amount < 0 || income.amount > 999999999999999 || !income.period)) fail(key, '소득은 0~999999999999999원 정수와 월·연 단위를 입력해 주세요.')
    if (income?.status === 'UNKNOWN' && (income.amount !== null || income.period !== null)) fail(key, '모름 상태에는 소득·단위를 입력할 수 없어요.')
  }
  for (const [key, field, max, min] of [['medianIncome', 'percent', 999.99, 0], ['studentLoanBracket', 'bracket', 10, 1]] as const) {
    const answer = value[key]
    const number = answer && (field === 'percent' ? (answer as ProfileInput['medianIncome'])?.percent : (answer as ProfileInput['studentLoanBracket'])?.bracket)
    if (answer?.status === 'PROVIDED' && (number == null || !Number.isFinite(number) || number < min || number > max || (key === 'medianIncome' ? !/^\d+(\.\d{1,2})?$/.test(String(number)) : !Number.isInteger(number)))) fail(key, key === 'medianIncome' ? '0~999.99%를 소수 둘째 자리까지 입력해 주세요.' : '1~10구간을 입력해 주세요.')
    if (answer && answer.status !== 'PROVIDED' && number !== null) fail(key, '값 입력 상태에서만 수치를 보낼 수 있어요.')
  }
  if (value.householdSize !== null && (!Number.isInteger(value.householdSize) || value.householdSize < 1 || value.householdSize > 99)) fail('householdSize', '가구원 수는 1~99명으로 입력해 주세요.')
  const targets = value.specialTargets
  if (targets && (targets.status === 'PROVIDED' ? targets.targets.length === 0 : targets.targets.length !== 0)) fail('specialTargets', '대상을 선택하거나 응답 상태를 변경해 주세요.')
  const military = value.militaryService
  if (military?.months !== null && military?.months !== undefined && (!['COMPLETED', 'SERVING'].includes(military.status) || !Number.isInteger(military.months) || military.months < 0 || military.months > 2147483647)) fail('militaryService', '복무 완료·복무 중에만 0 이상의 정수 개월을 입력해 주세요.')
  return errors
}
// 변경한 최상위 필드만 보낸다. 빈 선택은 null이며, untouched 필드는 PATCH에 넣지 않는다.
export function profilePatch(current: ProfileInput, initial: ProfileInput): Partial<ProfileInput> {
  return Object.fromEntries(Object.keys(emptyProfile).filter((key) => JSON.stringify(current[key as keyof ProfileInput]) !== JSON.stringify(initial[key as keyof ProfileInput])).map((key) => [key, current[key as keyof ProfileInput]]))
}
export function validateProfileRegions(value: ProfileInput, initial: ProfileInput | null, source: RegionSource): Record<string, string> {
  const errors: Record<string, string> = {}
  const residence = regionSelectionError(source, value.residenceRegionCode, initial?.residenceRegionCode ?? null, 'residence', initial === null)
  const school = regionSelectionError(source, value.schoolRegionCode, initial?.schoolRegionCode ?? null, 'school', initial === null)
  if (residence) errors.residenceRegionCode = residence
  if (school) errors.schoolRegionCode = school
  return errors
}
