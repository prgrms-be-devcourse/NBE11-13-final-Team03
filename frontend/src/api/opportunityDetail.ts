import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { EligibilityStatus } from '../components/ui/StatusBadge.tsx'
import type { ApplicationStatus } from '../pages/opportunities/types.ts'

export type ValidationStatus = 'VALID' | 'INVALID' | 'DATA_CONFLICT' | 'UNKNOWN' | 'NEEDS_REVIEW' | 'UNVERIFIED'
export type RequirementType = 'REQUIRED' | 'PREFERRED' | 'EXCLUSION'
export type ConditionType = 'AGE' | 'REGION' | 'INCOME' | 'PROPERTY' | 'EMPLOYMENT' | 'EDUCATION' | 'MARITAL' | 'HOUSING' | 'SPECIALIZATION' | 'SPECIAL_TARGET' | 'MILITARY_SERVICE' | 'PARTICIPATION_HISTORY' | 'TRAINING_CARD' | 'COMPOSITE' | 'OTHER'
type NullableText = string | null
export type RegionItem = {
  role: 'RESIDENCE' | 'SCHOOL' | 'BUSINESS' | 'IMPLEMENTATION' | 'UNKNOWN'
  status: string
  code?: NullableText
  name?: NullableText
  validationStatus?: ValidationStatus | null
}
export type ApplicationWindow = {
  periodType: 'FIXED' | 'ALWAYS' | 'CLOSED' | 'UNKNOWN'
  startDate?: NullableText
  endDate?: NullableText
  periodText?: NullableText
  validationStatus?: ValidationStatus | null
}
export type ConditionItem = {
  type: ConditionType
  requirement: RequirementType
  field?: NullableText
  validationStatus?: ValidationStatus | null
  status?: NullableText
  min?: number | null
  max?: number | null
  values: string[]
  bornOnOrAfter?: NullableText
  bornOnOrBefore?: NullableText
  incomeType?: NullableText
  period?: NullableText
  subject?: NullableText
  minInclusive?: boolean | null
  maxInclusive?: boolean | null
  unitConfirmed?: boolean | null
  evidenceText?: NullableText
}
export type YouthPolicyDetail = {
  policyNo?: NullableText
  description?: NullableText
  supportContent?: NullableText
  keywords?: NullableText
  supervisingInstitutionName?: NullableText
  operatingInstitutionName?: NullableText
  applicationMethod?: NullableText
  screeningMethod?: NullableText
  submissionDocuments?: NullableText
  applicationUrl?: NullableText
  referenceUrl1?: NullableText
  referenceUrl2?: NullableText
  businessStartDate?: NullableText
  businessEndDate?: NullableText
  businessPeriodText?: NullableText
  supportScale?: NullableText
  otherNotes?: NullableText
  firstRegisteredDate?: NullableText
}
export type TrainingCourseDetail = {
  courseId?: NullableText
  courseRound?: number | null
  institutionName?: NullableText
  institutionCode?: NullableText
  institutionCustomerId?: NullableText
  trainingStartDate?: NullableText
  trainingEndDate?: NullableText
  actualTrainingCost?: number | null
  courseFee?: number | null
  capacity?: number | null
  ncsCode?: NullableText
  trainingTargetCode?: NullableText
  trainingTargetName?: NullableText
  weekendTypeCode?: NullableText
  address?: NullableText
  telNo?: NullableText
}
export type EligibilityItem = {
  status: EligibilityStatus
  reason?: NullableText
  conditions: { type: string; field?: NullableText; requirement: string; result: string; reason?: NullableText }[]
}
type DetailBase = {
  opportunityId: number
  title: string
  category?: NullableText
  subCategory?: NullableText
  detailUrl?: NullableText
  createdAt: string
  regions: RegionItem[]
  applicationWindows: ApplicationWindow[]
  applicationStatus: ApplicationStatus
  conditions: ConditionItem[]
  eligibility?: EligibilityItem | null
  saved: boolean
}
export type OpportunityDetail = DetailBase & (
  | { type: 'YOUTH_POLICY'; detail?: YouthPolicyDetail | null }
  | { type: 'VOCATIONAL_TRAINING'; detail?: TrainingCourseDetail | null }
)

const validations = ['VALID', 'INVALID', 'DATA_CONFLICT', 'UNKNOWN', 'NEEDS_REVIEW', 'UNVERIFIED']
const conditionTypes = ['AGE', 'REGION', 'INCOME', 'PROPERTY', 'EMPLOYMENT', 'EDUCATION', 'MARITAL', 'HOUSING', 'SPECIALIZATION', 'SPECIAL_TARGET', 'MILITARY_SERVICE', 'PARTICIPATION_HISTORY', 'TRAINING_CARD', 'COMPOSITE', 'OTHER']
const requirements = ['REQUIRED', 'PREFERRED', 'EXCLUSION']
const member = (value: unknown, values: string[]): boolean => typeof value === 'string' && values.includes(value)
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const text = (value: unknown): boolean => value == null || typeof value === 'string'
const number = (value: unknown): boolean => value == null || (typeof value === 'number' && Number.isFinite(value))
const integer = (value: unknown): boolean => value == null || (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0)
const bool = (value: unknown): boolean => value == null || typeof value === 'boolean'
const strings = (value: unknown): boolean => Array.isArray(value) && value.every((item: unknown) => typeof item === 'string')
const validation = (value: unknown): boolean => value == null || member(value, validations)
const textFields = (value: Record<string, unknown>, keys: string[]): boolean => keys.every((key) => text(value[key]))

function isCondition(value: unknown): boolean {
  return record(value) && member(value.type, conditionTypes) && member(value.requirement, requirements) && validation(value.validationStatus) &&
    textFields(value, ['field', 'status', 'bornOnOrAfter', 'bornOnOrBefore', 'incomeType', 'period', 'subject', 'evidenceText']) &&
    number(value.min) && number(value.max) && strings(value.values) && bool(value.minInclusive) && bool(value.maxInclusive) && bool(value.unitConfirmed)
}
function isEligibility(value: unknown): boolean {
  return value == null || (record(value) && member(value.status, ['ELIGIBLE', 'POSSIBLY_ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN']) && text(value.reason) &&
    Array.isArray(value.conditions) && value.conditions.every((item: unknown) => record(item) && typeof item.type === 'string' && typeof item.requirement === 'string' &&
      typeof item.result === 'string' && textFields(item, ['field', 'reason'])))
}

// Nullable DTO 필드는 null과 JSON에서 생략된 경우 모두 허용한다. 필수 구조가 잘못되면 오류로 처리한다.
export function isOpportunityDetail(value: unknown): value is OpportunityDetail {
  if (!record(value) || !integer(value.opportunityId) || typeof value.opportunityId !== 'number' || value.opportunityId <= 0 ||
    typeof value.title !== 'string' || typeof value.createdAt !== 'string' || typeof value.saved !== 'boolean' ||
    !textFields(value, ['category', 'subCategory', 'detailUrl']) || !member(value.applicationStatus, ['UPCOMING', 'OPEN', 'ALWAYS_OPEN', 'CLOSED', 'UNKNOWN']) ||
    !Array.isArray(value.regions) || !value.regions.every((item: unknown) => record(item) && member(item.role, ['RESIDENCE', 'SCHOOL', 'BUSINESS', 'IMPLEMENTATION', 'UNKNOWN']) &&
      typeof item.status === 'string' && textFields(item, ['code', 'name']) && validation(item.validationStatus)) ||
    !Array.isArray(value.applicationWindows) || !value.applicationWindows.every((item: unknown) => record(item) && member(item.periodType, ['FIXED', 'ALWAYS', 'CLOSED', 'UNKNOWN']) &&
      textFields(item, ['startDate', 'endDate', 'periodText']) && validation(item.validationStatus)) ||
    !Array.isArray(value.conditions) || !value.conditions.every(isCondition) || !isEligibility(value.eligibility)) return false
  const detail = value.detail
  if (value.type === 'YOUTH_POLICY') return detail == null || (record(detail) &&
    textFields(detail, ['policyNo', 'description', 'supportContent', 'keywords', 'supervisingInstitutionName', 'operatingInstitutionName', 'applicationMethod',
      'screeningMethod', 'submissionDocuments', 'applicationUrl', 'referenceUrl1', 'referenceUrl2', 'businessStartDate', 'businessEndDate', 'businessPeriodText', 'supportScale', 'otherNotes', 'firstRegisteredDate']) && !('courseId' in detail))
  if (value.type === 'VOCATIONAL_TRAINING') return detail == null || (record(detail) &&
    textFields(detail, ['courseId', 'institutionName', 'institutionCode', 'institutionCustomerId', 'trainingStartDate', 'trainingEndDate', 'ncsCode', 'trainingTargetCode', 'trainingTargetName', 'weekendTypeCode', 'address', 'telNo']) &&
    ['courseRound', 'actualTrainingCost', 'courseFee', 'capacity'].every((key) => integer(detail[key])) && !('policyNo' in detail))
  return false
}

export function isOpportunityId(id: string | undefined): id is string {
  return !!id && /^[1-9]\d*$/.test(id) && Number.isSafeInteger(Number(id))
}
export async function getOpportunityDetail(id: string, signal?: AbortSignal): Promise<OpportunityDetail> {
  if (!isOpportunityId(id)) throw new ApiError('configuration', '올바른 공고 번호가 아닙니다.')
  const { data } = await apiClient.get<unknown>(`/opportunities/${id}`, { signal, recoverAuth: true })
  if (!isOpportunityDetail(data) || String(data.opportunityId) !== id) throw new ApiError('unknown', '상세 응답 형식을 확인할 수 없습니다.')
  return data
}
export function opportunityDetailQuery(id: string | undefined) {
  return {
    queryKey: ['opportunity-detail', id ?? ''],
    queryFn: ({ signal }: { signal: AbortSignal }) => getOpportunityDetail(id ?? '', signal),
    enabled: isOpportunityId(id),
  }
}
