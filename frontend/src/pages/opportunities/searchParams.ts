import type { ApplicationStatus, OpportunitySearchItem, SearchParams, SearchSort } from './types.ts'
import type { EligibilityStatus } from '../../components/ui/StatusBadge.tsx'

export const eligibilityStatuses: EligibilityStatus[] = ['ELIGIBLE', 'POSSIBLY_ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN']

export function isSearchRegionCode(value: string): boolean {
  // 개발 지역 사전의 시·도 코드와 전체 법정동 코드를 원문 그대로 허용한다.
  return /^(?:\d{2}|\d{10})$/.test(value)
}

// InterestOptionController의 직업훈련 대분류. 표시 이름과 달리 요청 값은 원문을 유지한다.
export const trainingCategory = '교육･직업훈련'
export function categoryMatchesType(category: string, type: SearchParams['type']): boolean {
  if (!type || !category) return true
  const training = category === trainingCategory
  return type === 'VOCATIONAL_TRAINING' ? training : !training
}

// 정책 '교육'과 겹치지 않도록 직업훈련 대분류는 '직업훈련'으로 보여 준다.
export function categoryDisplayLabel(category: string): string {
  return category === trainingCategory ? '직업훈련' : category
}

export function classificationLabel(item: Pick<OpportunitySearchItem, 'type' | 'category' | 'subCategory' | 'detailCategory'>): string {
  // 직업훈련은 중분류(NCS 1차)·세부 분류(NCS 2차)를 보여 준다. 미분류 대체값 '직업훈련'은 실제 NCS 분류가 있으면 생략하고,
  // 하나도 없으면 '직업훈련'으로 남긴다.
  const training = item.type === 'VOCATIONAL_TRAINING'
  const labels = (training ? [item.subCategory, item.detailCategory] : [item.category?.trim() && categoryDisplayLabel(item.category.trim()), item.subCategory, item.detailCategory])
    .map(value => value?.trim()).filter((value): value is string => !!value && !(training && value === typeLabels.VOCATIONAL_TRAINING))
  if (training && !labels.length) return typeLabels.VOCATIONAL_TRAINING
  return [...new Set(labels)].join(' · ')
}

export const typeLabels = { YOUTH_POLICY: '청년정책', VOCATIONAL_TRAINING: '직업훈련' }
export const statusLabels: Record<ApplicationStatus, string> = {
  UPCOMING: '모집 예정', OPEN: '모집 중', ALWAYS_OPEN: '상시 모집',
  CLOSED: '모집 마감', UNKNOWN: '상태 미확인',
}
export const sortLabels: Record<SearchSort, string> = {
  RELEVANCE: '관련도순', LATEST: '최초 등록일 최신순', OLDEST: '최초 등록일 오래된순',
  DEADLINE_PRIORITY: '정책 마감 우선순', START_DATE_ASC: '교육 시작일 빠른순',
}

// 백엔드 SearchKeyword.hasTokens()와 같은 판정만 옮긴다: NFKC·소문자로 바꾼 뒤 단독 '+'·구분자·하이픈을 지우고
// 남는 글자가 있어야 검색 토큰이 있다. 구분자로만 된 검색어(!!!, [, ())는 토큰이 없어 백엔드가 최신순으로 처리한다.
const SEARCH_SEPARATORS = /[\s/,·・‧ᆞㆍ|()[\]{}「」『』<>"'`~!?:;&-]/gu
export function hasSearchTokens(keyword: string): boolean {
  return keyword.normalize('NFKC').toLowerCase().replace(/(?<![a-z+])\+/g, ' ').replace(SEARCH_SEPARATORS, '') !== ''
}

// 백엔드 기본 정렬과 같다. sort를 생략하면 검색 토큰이 있을 때 관련도순, 없을 때 최신순이다.
export function defaultSort(keyword: string): SearchSort {
  return hasSearchTokens(keyword) ? 'RELEVANCE' : 'LATEST'
}

export function applicationStatusLabel(status: ApplicationStatus, type: SearchParams['type']): string {
  if (status !== 'OPEN' || type === 'YOUTH_POLICY') return statusLabels[status]
  return type === 'VOCATIONAL_TRAINING' ? '개강 예정' : '모집 중 · 개강 예정'
}

export function defaultSearchParams(): SearchParams {
  return {
    keyword: '', type: '', category: '', subCategory: '', detailCategory: '', region: '', eligibilityStatuses: [], applicationStatus: '',
    deadlineFrom: '', deadlineTo: '', startDateFrom: '', startDateTo: '',
    sort: 'LATEST', page: 0, size: 10,
  }
}

// 관련도순은 검색 토큰이 있을 때만 의미가 있다 (백엔드도 토큰 없이 요청하면 최신순으로 처리한다).
export function availableSorts(type: SearchParams['type'], keyword = ''): SearchSort[] {
  return [...(hasSearchTokens(keyword) ? ['RELEVANCE' as const] : []), 'LATEST', 'OLDEST',
    ...(type === 'YOUTH_POLICY' ? ['DEADLINE_PRIORITY' as const] : []),
    ...(type === 'VOCATIONAL_TRAINING' ? ['START_DATE_ASC' as const] : [])]
}

export function changeSearchType(params: SearchParams, type: SearchParams['type']): SearchParams {
  return {
    ...params, type, page: 0,
    ...(!categoryMatchesType(params.category, type) ? { category: '', subCategory: '', detailCategory: '' } : {}),
    deadlineFrom: type === 'YOUTH_POLICY' ? params.deadlineFrom : '',
    deadlineTo: type === 'YOUTH_POLICY' ? params.deadlineTo : '',
    startDateFrom: type === 'VOCATIONAL_TRAINING' ? params.startDateFrom : '',
    startDateTo: type === 'VOCATIONAL_TRAINING' ? params.startDateTo : '',
    sort: availableSorts(type, params.keyword).includes(params.sort) ? params.sort : defaultSort(params.keyword),
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
  params.sort = defaultSort(params.keyword)
  // 검색어 없는 관련도순은 백엔드처럼 최신순으로 처리한다 (오류가 아니다).
  if (sort && availableSorts(params.type, params.keyword).includes(sort as SearchSort)) params.sort = sort as SearchSort
  else if (sort && sort !== 'RELEVANCE') invalid()
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
  const region = url.get('region')
  if (region && isSearchRegionCode(region)) params.region = region
  else if (region) invalid()
  const requestedStatuses = url.getAll('eligibilityStatuses').flatMap(value => value.split(','))
  params.eligibilityStatuses = eligibilityStatuses.filter(status => requestedStatuses.includes(status))
  if (requestedStatuses.some(status => !eligibilityStatuses.includes(status as EligibilityStatus))) invalid()
  return { params, notices }
}

// URL과 API에 같은 직렬화를 사용한다. 기본값·빈 값은 생략한다.
export function serializeSearchParams(params: SearchParams): URLSearchParams {
  const url = new URLSearchParams()
  for (const key of ['keyword', 'type', 'category', 'subCategory', 'detailCategory', 'region', 'applicationStatus',
    'deadlineFrom', 'deadlineTo', 'startDateFrom', 'startDateTo'] as const) {
    if (params[key].trim()) url.set(key, key === 'keyword' ? params[key].trim() : params[key])
  }
  const selected = eligibilityStatuses.filter(status => params.eligibilityStatuses.includes(status))
  if (selected.length) url.set('eligibilityStatuses', selected.join(','))
  // 기본 정렬과 다를 때만 보낸다. 검색어가 있을 때 최신순은 sort=LATEST로 명시해야 관련도순과 구분된다.
  const sort = params.sort === 'RELEVANCE' && !hasSearchTokens(params.keyword) ? 'LATEST' : params.sort
  if (sort !== defaultSort(params.keyword)) url.set('sort', sort)
  if (params.page !== 0) url.set('page', String(params.page))
  if (params.size !== 10) url.set('size', String(params.size))
  return url
}

export function changeSearchConditions(params: SearchParams, patch: Partial<SearchParams>): SearchParams {
  const next = { ...params, ...patch, page: 0 }
  // 기본 정렬을 쓰던 중 검색어가 생기거나 지워지면 새 검색어의 기본 정렬을 따른다. 직접 고른 정렬은 유지한다.
  if (next.keyword.trim() !== params.keyword.trim() && next.sort === defaultSort(params.keyword)) {
    next.sort = defaultSort(next.keyword)
  }
  return parseSearchParams(serializeSearchParams(next)).params
}

export function paginationPages(page: number, total: number): number[] {
  return [...new Set([0, page - 1, page, page + 1, total - 1])]
    .filter((value) => value >= 0 && value < total).sort((a, b) => a - b)
}
