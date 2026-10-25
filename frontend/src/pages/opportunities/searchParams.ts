import type { ApplicationStatus, SearchParams, SearchSort } from './types.ts'

export const typeLabels = { YOUTH_POLICY: '청년정책', VOCATIONAL_TRAINING: '직업훈련' }
export const statusLabels: Record<ApplicationStatus, string> = {
  UPCOMING: '모집 예정', OPEN: '모집 중', ALWAYS_OPEN: '상시 모집',
  CLOSED: '모집 마감', UNKNOWN: '상태 미확인',
}
export const sortLabels: Record<SearchSort, string> = {
  LATEST: '최초 등록일 최신순', OLDEST: '최초 등록일 오래된순',
  DEADLINE_PRIORITY: '정책 마감 우선순', START_DATE_ASC: '교육 시작일 빠른순',
}

export function applicationStatusLabel(status: ApplicationStatus, type: SearchParams['type']): string {
  if (status !== 'OPEN' || type === 'YOUTH_POLICY') return statusLabels[status]
  return type === 'VOCATIONAL_TRAINING' ? '개강 예정' : '모집 중 · 개강 예정'
}

export function defaultSearchParams(): SearchParams {
  return {
    keyword: '', type: '', category: '', subCategory: '', detailCategory: '', applicationStatus: '',
    deadlineFrom: '', deadlineTo: '', startDateFrom: '', startDateTo: '',
    sort: 'LATEST', page: 0, size: 20,
  }
}

export function availableSorts(type: SearchParams['type']): SearchSort[] {
  return ['LATEST', 'OLDEST', ...(type === 'YOUTH_POLICY' ? ['DEADLINE_PRIORITY' as const] : []),
    ...(type === 'VOCATIONAL_TRAINING' ? ['START_DATE_ASC' as const] : [])]
}

export function changeSearchType(params: SearchParams, type: SearchParams['type']): SearchParams {
  return {
    ...params, type, page: 0,
    deadlineFrom: type === 'YOUTH_POLICY' ? params.deadlineFrom : '',
    deadlineTo: type === 'YOUTH_POLICY' ? params.deadlineTo : '',
    startDateFrom: type === 'VOCATIONAL_TRAINING' ? params.startDateFrom : '',
    startDateTo: type === 'VOCATIONAL_TRAINING' ? params.startDateTo : '',
    sort: availableSorts(type).includes(params.sort) ? params.sort : 'LATEST',
  }
}

export function changeSearchCategory(params: SearchParams, level: 'category' | 'subCategory' | 'detailCategory', value: string): SearchParams {
  return {
    ...params, [level]: value, page: 0,
    ...(level === 'category' ? { subCategory: '', detailCategory: '' } : {}),
    ...(level === 'subCategory' ? { detailCategory: '' } : {}),
  }
}

export function isValidDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

export function dateRangeError(params: SearchParams): string | undefined {
  const [from, to] = params.type === 'YOUTH_POLICY'
    ? [params.deadlineFrom, params.deadlineTo] : [params.startDateFrom, params.startDateTo]
  if ((from && !isValidDate(from)) || (to && !isValidDate(to))) return '날짜를 올바르게 입력해 주세요.'
  if (from && to && from > to) return '시작 범위는 끝 범위보다 늦을 수 없어요.'
  return undefined
}

export function parseSearchParams(url: URLSearchParams): { params: SearchParams; notices: string[] } {
  const params = defaultSearchParams()
  const notices: string[] = []
  const invalid = () => { if (!notices.includes('잘못되거나 호환되지 않는 검색 조건을 정리했어요.')) notices.push('잘못되거나 호환되지 않는 검색 조건을 정리했어요.') }
  params.keyword = (url.get('keyword') ?? '').trim()
  // 분류 API의 문자열은 특수문자·공백을 포함해 원본 그대로 유지한다.
  for (const key of ['category', 'subCategory', 'detailCategory'] as const) params[key] = url.get(key) ?? ''
  const type = url.get('type')
  if (type === 'YOUTH_POLICY' || type === 'VOCATIONAL_TRAINING') params.type = type
  else if (type) invalid()
  const status = url.get('applicationStatus')
  if (status && Object.hasOwn(statusLabels, status)) params.applicationStatus = status as ApplicationStatus
  else if (status) invalid()
  const sort = url.get('sort')
  if (sort && availableSorts(params.type).includes(sort as SearchSort)) params.sort = sort as SearchSort
  else if (sort) invalid()
  for (const [key, minimum, maximum] of [['page', 0, 2147483647], ['size', 1, 100]] as const) {
    const value = url.get(key)
    if (value && /^(0|[1-9]\d*)$/.test(value) && Number(value) >= minimum && Number(value) <= maximum) params[key] = Number(value)
    else if (value) invalid()
  }
  for (const key of ['deadlineFrom', 'deadlineTo', 'startDateFrom', 'startDateTo'] as const) {
    const value = url.get(key)
    const compatible = key.startsWith('deadline') ? params.type === 'YOUTH_POLICY' : params.type === 'VOCATIONAL_TRAINING'
    if (value && compatible && isValidDate(value)) params[key] = value
    else if (value) invalid()
  }
  if (dateRangeError(params)) {
    params.deadlineFrom = params.deadlineTo = params.startDateFrom = params.startDateTo = ''
    invalid()
  }
  if (url.get('eligibilityStatuses')) notices.push('신청 가능성 필터는 로그인 연동 전이라 적용하지 않았어요.')
  if (url.get('region')) notices.push('지역 필터는 유효한 지역 목록이 연결되기 전이라 적용하지 않았어요.')
  return { params, notices }
}

// URL과 API에 같은 직렬화를 사용한다. 기본값·빈 값은 생략한다.
export function serializeSearchParams(params: SearchParams): URLSearchParams {
  const url = new URLSearchParams()
  for (const key of ['keyword', 'type', 'category', 'subCategory', 'detailCategory', 'applicationStatus',
    'deadlineFrom', 'deadlineTo', 'startDateFrom', 'startDateTo'] as const) {
    if (params[key].trim()) url.set(key, key === 'keyword' ? params[key].trim() : params[key])
  }
  if (params.sort !== 'LATEST') url.set('sort', params.sort)
  if (params.page !== 0) url.set('page', String(params.page))
  if (params.size !== 20) url.set('size', String(params.size))
  return url
}

export function changeSearchConditions(params: SearchParams, patch: Partial<SearchParams>): SearchParams {
  return parseSearchParams(serializeSearchParams({ ...params, ...patch, page: 0 })).params
}

export function paginationPages(page: number, total: number): number[] {
  return [...new Set([0, page - 1, page, page + 1, total - 1])]
    .filter((value) => value >= 0 && value < total).sort((a, b) => a - b)
}
