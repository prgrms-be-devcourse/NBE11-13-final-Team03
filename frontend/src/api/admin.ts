import type { QueryClient } from '@tanstack/react-query'
import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'
import type { AuthUser } from './auth.ts'
import { authQueryKey, authTransitionKey } from '../auth/session.ts'

// backend CollectionAdminController·RetentionAdminController와 DTO, contracts collection.proto의 enum을 그대로 따른다.
// 원본 레코드 저장(POST raw-records)은 화면에서 제공하지 않으므로 함수도 두지 않는다.
export type SyncSource = 'YOUTH_POLICY' | 'EMPLOYMENT24'
export type SyncStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED' | 'INTERRUPTED'
export const syncSources: readonly SyncSource[] = ['YOUTH_POLICY', 'EMPLOYMENT24']
const syncStatuses: readonly SyncStatus[] = ['PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'INTERRUPTED']
// data-service SyncJobService: 실패·부분 성공·중단된 수집만 재시도할 수 있다.
export const retryableStatuses: readonly SyncStatus[] = ['FAILED', 'PARTIAL_SUCCESS', 'INTERRUPTED']

export type SyncStart = { jobId: number; source: SyncSource; status: SyncStatus; message: string }
export type PageFailure = { pageNumber: number; errorCode: string; errorMessage: string; retryable: boolean }
export type Employment24PageFailure = { request: unknown; errorCode: string; errorMessage: string; retryable: boolean }
export type RecordFailure = {
  recordResultId: number; externalId: string; pageNumber: number | null; itemIndex: number | null; rawRecordId: number | null
  status: string; resultType: string | null; errorCode: string; errorMessage: string; processedAt: string | null
}
export type SyncJob = {
  jobId: number; source: SyncSource; status: SyncStatus; startedAt: string | null; completedAt: string | null; totalCount: number | null
  fetchedCount: number; successCount: number; failedRecordCount: number; pendingCount: number; failedPageCount: number
  newCount: number; updatedCount: number; unchangedCount: number
  pageFailures: PageFailure[]; employment24PageFailures: Employment24PageFailure[]; incompleteNcsCodes: string[]; recordFailures: RecordFailure[]
  failureReason: string | null
}
export type RawRecord = { rawRecordId: number; source: SyncSource; externalId: string; payloadHash: string; collectedAt: string | null; lastSeenAt: string | null; payload: unknown }
export type RetentionPolicy = { retainedMonths: number; futureMonths: number; schedule: string; schedulerEnabled: boolean; schedulerDryRun: boolean; adminExecuteEnabled: boolean }
export type RetentionDrop = { name: string; from: string | null; to: string | null; estimatedRows: number; totalBytes: number }
export type RetentionCreate = { name: string; from: string | null; to: string | null; conflict: boolean }
export type RetentionIgnored = { name: string; bound: string | null }
export type RetentionPreview = {
  status: string; policy: RetentionPolicy; baseDate: string | null; keepFrom: string | null
  toDrop: RetentionDrop[]; toCreate: RetentionCreate[]; ignored: RetentionIgnored[]; defaultPartitionHasRows: boolean; planToken: string | null
}
export type RetentionResult = { status: string; keepFrom: string | null; created: string[]; dropped: string[]; failed: string[]; ignored: string[]; executedAt: string | null }

const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
const id = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const text = (value: unknown): value is string => typeof value === 'string'
const optionalText = (value: unknown) => value == null || typeof value === 'string'
const optionalCount = (value: unknown) => value == null || count(value)
const optionalId = (value: unknown) => value == null || id(value)
// 테스트 변환기가 .ts도 JSX로 읽으므로 제네릭 화살표 함수 대신 함수 선언을 쓴다.
function list<T>(value: unknown, item: (entry: unknown) => boolean): value is T[] { return Array.isArray(value) && value.every(item) }
function member<T extends string>(value: unknown, values: readonly T[]): value is T { return typeof value === 'string' && (values as readonly string[]).includes(value) }
const malformed = () => new ApiError('unknown', '관리자 API 응답 형식을 확인할 수 없어요.')
// 응답의 nullable 필드는 null과 생략을 모두 허용하고 화면에서는 null로 다룬다.
function nullable<T>(value: T | undefined | null): T | null { return value ?? null }

export function isSyncStart(value: unknown): value is SyncStart {
  return record(value) && id(value.jobId) && member(value.source, syncSources) && member(value.status, syncStatuses) && optionalText(value.message)
}
function isPageFailure(value: unknown): boolean {
  return record(value) && count(value.pageNumber) && text(value.errorCode) && text(value.errorMessage) && typeof value.retryable === 'boolean'
}
function isEmployment24Failure(value: unknown): boolean {
  return record(value) && text(value.errorCode) && text(value.errorMessage) && typeof value.retryable === 'boolean'
}
function isRecordFailure(value: unknown): boolean {
  return record(value) && id(value.recordResultId) && text(value.externalId) && optionalCount(value.pageNumber) && optionalCount(value.itemIndex) &&
    optionalId(value.rawRecordId) && text(value.status) && optionalText(value.resultType) && text(value.errorCode) && text(value.errorMessage) && optionalText(value.processedAt)
}
export function isSyncJob(value: unknown): value is SyncJob {
  return record(value) && id(value.jobId) && member(value.source, syncSources) && member(value.status, syncStatuses) &&
    optionalText(value.startedAt) && optionalText(value.completedAt) && optionalCount(value.totalCount) &&
    ['fetchedCount', 'successCount', 'failedRecordCount', 'pendingCount', 'failedPageCount', 'newCount', 'updatedCount', 'unchangedCount'].every(key => count(value[key])) &&
    list(value.pageFailures, isPageFailure) && list(value.employment24PageFailures, isEmployment24Failure) && list(value.incompleteNcsCodes, text) &&
    list(value.recordFailures, isRecordFailure) && optionalText(value.failureReason)
}
function toSyncJob(value: SyncJob): SyncJob {
  return { ...value, startedAt: nullable(value.startedAt), completedAt: nullable(value.completedAt), totalCount: nullable(value.totalCount), failureReason: nullable(value.failureReason),
    recordFailures: value.recordFailures.map(item => ({ ...item, pageNumber: nullable(item.pageNumber), itemIndex: nullable(item.itemIndex), rawRecordId: nullable(item.rawRecordId), resultType: nullable(item.resultType), processedAt: nullable(item.processedAt) })) }
}
export function isRawRecord(value: unknown): value is RawRecord {
  return record(value) && id(value.rawRecordId) && member(value.source, syncSources) && text(value.externalId) && text(value.payloadHash) &&
    optionalText(value.collectedAt) && optionalText(value.lastSeenAt) && 'payload' in value
}
function isPolicy(value: unknown): value is RetentionPolicy {
  return record(value) && count(value.retainedMonths) && count(value.futureMonths) && text(value.schedule) &&
    ['schedulerEnabled', 'schedulerDryRun', 'adminExecuteEnabled'].every(key => typeof value[key] === 'boolean')
}
export function isRetentionPreview(value: unknown): value is RetentionPreview {
  return record(value) && text(value.status) && isPolicy(value.policy) && optionalText(value.baseDate) && optionalText(value.keepFrom) &&
    list(value.toDrop, item => record(item) && text(item.name) && optionalText(item.from) && optionalText(item.to) && count(item.estimatedRows) && count(item.totalBytes)) &&
    list(value.toCreate, item => record(item) && text(item.name) && optionalText(item.from) && optionalText(item.to) && typeof item.conflict === 'boolean') &&
    list(value.ignored, item => record(item) && text(item.name) && optionalText(item.bound)) &&
    typeof value.defaultPartitionHasRows === 'boolean' && optionalText(value.planToken)
}
export function isRetentionResult(value: unknown): value is RetentionResult {
  return record(value) && text(value.status) && optionalText(value.keepFrom) && ['created', 'dropped', 'failed', 'ignored'].every(key => list(value[key], text)) && optionalText(value.executedAt)
}

/** 서버 LocalDate 기준(Asia/Seoul)의 오늘. 수집 이력 날짜 기본값이다. */
export function seoulToday(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now)
}
export function isIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
/** 경로 ID는 양의 안전한 정수만 허용한다. */
export function parseAdminId(value: string | null | undefined): number | null {
  return value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : null
}

export async function listSyncs(date: string, signal?: AbortSignal): Promise<SyncJob[]> {
  if (!isIsoDate(date)) throw new ApiError('configuration', '조회할 날짜를 확인해 주세요.')
  const { data } = await apiClient.get<unknown>('/admin/collection/syncs', { params: { date }, requiresAuth: true, signal })
  if (!list<SyncJob>(data, isSyncJob)) throw malformed()
  return data.map(toSyncJob)
}
export async function getSync(jobId: number, signal?: AbortSignal): Promise<SyncJob> {
  if (!id(jobId)) throw new ApiError('configuration', '수집 실행 번호를 확인해 주세요.')
  const { data } = await apiClient.get<unknown>(`/admin/collection/syncs/${jobId}`, { requiresAuth: true, signal })
  if (!isSyncJob(data) || data.jobId !== jobId) throw malformed()
  return toSyncJob(data)
}
export type SyncLimits = { pageSize?: number; maxPages?: number }
export function syncLimitError(limits: SyncLimits): string | null {
  if (limits.pageSize !== undefined && (!Number.isInteger(limits.pageSize) || limits.pageSize < 1 || limits.pageSize > 100)) return '페이지 크기는 1~100 사이 정수예요.'
  if (limits.maxPages !== undefined && (!Number.isInteger(limits.maxPages) || limits.maxPages < 1 || limits.maxPages > 1000)) return '최대 페이지 수는 1~1000 사이 정수예요.'
  return null
}
// 202 응답만 시작 확인으로 본다. 생략한 제한값은 서버 기본값을 쓴다.
export async function startSync(source: SyncSource, limits: SyncLimits = {}): Promise<SyncStart> {
  if (!member(source, syncSources) || syncLimitError(limits)) throw new ApiError('configuration', '수집 시작 조건을 확인해 주세요.')
  const params = { ...(limits.pageSize !== undefined ? { pageSize: limits.pageSize } : {}), ...(limits.maxPages !== undefined ? { maxPages: limits.maxPages } : {}) }
  const response = await apiClient.post<unknown>(`/admin/collection/syncs/${source}`, undefined, { params, requiresAuth: true })
  if (response.status !== 202 || !isSyncStart(response.data)) throw malformed()
  return response.data
}
export async function retrySync(jobId: number): Promise<SyncStart> {
  if (!id(jobId)) throw new ApiError('configuration', '수집 실행 번호를 확인해 주세요.')
  const response = await apiClient.post<unknown>(`/admin/collection/syncs/${jobId}/retry`, undefined, { requiresAuth: true })
  if (response.status !== 202 || !isSyncStart(response.data)) throw malformed()
  return response.data
}
export async function getRawRecord(rawRecordId: number, signal?: AbortSignal): Promise<RawRecord> {
  if (!id(rawRecordId)) throw new ApiError('configuration', '원본 레코드 번호를 확인해 주세요.')
  const { data } = await apiClient.get<unknown>(`/admin/collection/raw-records/${rawRecordId}`, { requiresAuth: true, signal })
  if (!isRawRecord(data) || data.rawRecordId !== rawRecordId) throw malformed()
  return { ...data, collectedAt: nullable(data.collectedAt), lastSeenAt: nullable(data.lastSeenAt) }
}
export async function getRetentionPreview(signal?: AbortSignal): Promise<RetentionPreview> {
  const { data } = await apiClient.get<unknown>('/admin/behavior-events/retention/preview', { requiresAuth: true, signal })
  if (!isRetentionPreview(data)) throw malformed()
  return { ...data, baseDate: nullable(data.baseDate), keepFrom: nullable(data.keepFrom), planToken: nullable(data.planToken) }
}
/**
 * 보관 실행만 공통 10초보다 길게 기다린다. 서버는 계획 조회(문장 제한 10초) 뒤 파티션을 하나씩 만들고 지우며
 * 각 DDL이 잠금을 최대 3초(lock-timeout) 기다린다. 배포 경로의 CloudFront 원본 응답 대기(기본 30초)보다 길게 두어
 * 프록시보다 먼저 끊지 않는다. 시간 초과·5xx는 isUncertainExecution으로 '결과 불확실'로 다룬다.
 */
export const retentionExecuteTimeoutMs = 60_000
export async function executeRetention(planToken: string): Promise<RetentionResult> {
  if (!planToken || planToken.length > 128) throw new ApiError('configuration', '미리보기 확인 값이 없어요. 미리보기를 다시 불러와 주세요.')
  const { status, data } = await apiClient.post<unknown>('/admin/behavior-events/retention/execute', { planToken }, { requiresAuth: true, timeout: retentionExecuteTimeoutMs })
  if (status !== 200 || !isRetentionResult(data)) throw malformed()
  return data
}

// 관리자 Query는 사용자 ID를 키에 넣고, 인증 전환 시 resetPersonalizedQueries가 'admin' 루트를 모두 지운다.
export const adminKeys = {
  syncs: (userId: number, date: string) => ['admin', userId, 'syncs', date] as const,
  sync: (userId: number, jobId: number) => ['admin', userId, 'sync', jobId] as const,
  raw: (userId: number, rawRecordId: number) => ['admin', userId, 'raw', rawRecordId] as const,
  retention: (userId: number) => ['admin', userId, 'retention'] as const,
}
export const adminMutationKey = (userId: number) => ['admin-change', userId] as const

/** 실행 요청 전후로, 서버에서 확인한 같은 관리자 세션인지 확인한다. 전환 중이거나 바뀌었으면 결과를 반영하지 않는다. */
export function assertAdminOwner(client: QueryClient, owner: AuthUser): void {
  const state = client.getQueryState(authQueryKey)
  if (client.getQueryData(authQueryKey) !== owner || owner.role !== 'ADMIN' || state?.status !== 'success' || client.isMutating({ mutationKey: authTransitionKey }) > 0) {
    throw new ApiError('cancelled', '인증 상태가 변경되어 요청을 중단했어요.')
  }
}

export type AdminErrorKind = 'login' | 'denied' | 'disabled' | 'other'
// 관리자 권한 거부는 각 컨트롤러의 403 코드로만 판정한다. 같은 403이라도 RETENTION_002는 서버 설정으로 실행이 꺼진 상태다.
const deniedCodes = new Set(['COLLECTION_007', 'RETENTION_001'])
export function adminErrorKind(error: unknown): AdminErrorKind {
  if (!(error instanceof ApiError)) return 'other'
  if (error.status === 401) return 'login'
  if (error.status === 403 && error.code === 'RETENTION_002') return 'disabled'
  if (error.status === 403 && error.code && deniedCodes.has(error.code)) return 'denied'
  return 'other'
}
/**
 * 보관 실행 요청 뒤 서버 반영 여부를 확정할 수 없는 오류다. 파티션별로 따로 커밋되므로 일부가 이미 반영됐을 수 있다.
 * 시간 초과·네트워크 오류·5xx(프록시 504 포함)·응답 형식 불명이 해당한다.
 * 4xx는 서버가 거부한 확정 응답이고, csrf·configuration·cancelled는 서버 응답을 받기 전에 끝난 경우라 제외한다.
 */
export function isUncertainExecution(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true
  if (error.kind === 'timeout' || error.kind === 'network' || error.kind === 'unknown') return true
  return error.kind === 'http' && error.status !== undefined && error.status >= 500
}
const messages: Record<string, string> = {
  COLLECTION_001: '수집 실행기를 사용할 수 없어요. 잠시 후 다시 시도해 주세요.',
  COLLECTION_003: '요청 값을 확인해 주세요.',
  COLLECTION_004: '수집 실행 또는 원본 레코드를 찾을 수 없어요.',
  COLLECTION_005: '현재 상태에서는 처리할 수 없어요. 같은 출처의 수집이 이미 대기·실행 중이거나 재시도할 수 없는 상태일 수 있어요.',
  COLLECTION_006: '원본 데이터 저장소 처리에 실패했어요.',
  COLLECTION_008: '수집 서비스에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.',
  RETENTION_002: '서버 설정에서 관리자 보관 정리 실행이 꺼져 있어요.',
  RETENTION_003: '미리보기 이후 보관 기준이나 정리 대상이 바뀌었어요. 미리보기를 다시 확인해 주세요.',
  RETENTION_004: '다른 보관 정리가 진행 중이에요. 잠시 후 미리보기를 다시 확인해 주세요.',
  RETENTION_005: '현재 DB 상태에서는 보관 정리를 실행할 수 없어요. 미리보기 상태를 확인해 주세요.',
}
export function adminErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '요청을 처리하지 못했어요.'
  if (error.code && messages[error.code]) return messages[error.code]
  if (error.status === 401) return '로그인 상태를 다시 확인해 주세요.'
  if (adminErrorKind(error) === 'denied') return '관리자 권한이 확인되지 않았어요.'
  if (error.status === 403 || error.kind === 'csrf') return '요청이 거부됐어요. 보안 토큰과 로그인 상태를 확인해 주세요.'
  if (error.kind === 'network' || error.kind === 'timeout') return '서버 응답을 확인하지 못했어요. 처리 여부를 목록에서 다시 확인해 주세요.'
  if (error.kind === 'cancelled') return '인증 상태가 바뀌어 요청 결과 처리를 중단했어요.'
  if (error.status && error.status >= 500) return '서버에서 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
  return error.message || '요청을 처리하지 못했어요.'
}
