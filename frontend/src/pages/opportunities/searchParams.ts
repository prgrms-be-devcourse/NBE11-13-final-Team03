import type { ApplicationStatus, InterestOptions, OpportunityCategory, OpportunityType, SearchParams, SearchSort } from './types.ts'
import type { EligibilityStatus } from '../../components/ui/StatusBadge.tsx'

export const eligibilityStatuses: EligibilityStatus[] = ['ELIGIBLE', 'POSSIBLY_ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN']

export function isSearchRegionCode(value: string): boolean {
  // 개발 지역 사전의 시·도 코드와 전체 법정동 코드를 원문 그대로 허용한다.
  return /^(?:\d{2}|\d{10})$/.test(value)
}

// 직업훈련 대분류의 원본 이름. 청년정책 공고 원문에도 옛 분류명으로 같은 이름이 남아 있으므로 이름만으로 유형을 판단하지 않는다.
// 공고 유형은 분류 API의 opportunityTypes와 검색 결과의 type으로 정한다. 이 상수는 직업훈련 대분류의 표시 이름을 바꿀 때만 쓴다.
export const trainingCategory = '교육･직업훈련'

// 직업훈련 대분류는 정책 '교육'과 겹치지 않도록 '직업훈련'으로 보여 준다. 청년정책 분류는 받은 이름 그대로 보여 준다.
export function categoryLabel(type: OpportunityType | '', category: string): string {
  return type === 'VOCATIONAL_TRAINING' && category === trainingCategory ? typeLabels.VOCATIONAL_TRAINING : category
}

// 유형 전체 목록·적용 조건처럼 유형이 정해지지 않은 곳에서는 어느 유형의 분류인지 붙인다 ('직업훈련'은 이미 유형 이름이다).
export function categoryChoiceLabel(type: OpportunityType | '', category: string): string {
  const label = categoryLabel(type, category)
  return type && label !== typeLabels[type] ? `${label} · ${typeLabels[type]}` : label
}

// 분류 API의 같은 이름 대분류를 공고 유형별 선택지로 나눈 것. 하위 분류도 그 유형의 것만 남긴다 (세부 분류는 직업훈련에만 있다).
export type CategoryEntry = {
  type: OpportunityType
  category: string
  subCategories: { subCategory: string; detailCategories: string[] }[]
}
const opportunityTypes: OpportunityType[] = ['YOUTH_POLICY', 'VOCATIONAL_TRAINING']
export function categoryEntries(options: InterestOptions | undefined, type: OpportunityType | '' = ''): CategoryEntry[] {
  return opportunityTypes.filter(value => !type || value === type).flatMap(value => (options?.categories ?? [])
    .filter(item => item.opportunityTypes.includes(value))
    .map(item => ({
      type: value, category: item.category,
      subCategories: item.subCategories.filter(sub => sub.opportunityTypes.includes(value))
        .map(sub => ({ subCategory: sub.subCategory, detailCategories: value === 'VOCATIONAL_TRAINING' ? sub.detailCategories : [] })),
    })))
}

// 검색 조건의 대분류가 가리키는 선택지. 유형(type 또는 categoryType)이 없고 두 유형에 같은 대분류가 있으면 하나로 정할 수 없다.
export function selectedCategoryEntry(entries: CategoryEntry[], params: Pick<SearchParams, 'type' | 'categoryType' | 'category'>): { entry?: CategoryEntry; ambiguous: boolean } {
  const scope = params.type || params.categoryType
  const matches = entries.filter(item => item.category === params.category && (!scope || item.type === scope))
  return { entry: matches.length === 1 ? matches[0] : undefined, ambiguous: matches.length > 1 }
}

// select의 값. 유형 전체 목록에서는 같은 대분류명이 유형별로 두 번 나오므로 유형을 앞에 붙인다.
export function categoryEntryKey(entry: Pick<CategoryEntry, 'type' | 'category'>): string {
  return `${entry.type}:${entry.category}`
}
export function parseCategoryEntryKey(value: string): { type: OpportunityType; category: string } | undefined {
  const index = value.indexOf(':')
  const type = value.slice(0, index)
  return index > 0 && (type === 'YOUTH_POLICY' || type === 'VOCATIONAL_TRAINING') ? { type, category: value.slice(index + 1) } : undefined
}

// 청년정책 분류는 서버가 정규화한 분류 행(categories)으로만 만든다. 공고 원문(category·subCategory)은 쉼표로 이은 옛 분류명일 수 있어
// 쓰지 않고, 이름 통합·짝 나누기도 프런트에서 다시 하지 않는다. 같은 대분류는 한 번만 쓰고 그 대분류 행의 중분류만 묶는다.
// 중분류만 있는 행(원본 개수가 맞지 않아 짝을 정하지 않은 행)은 어느 대분류에도 붙이지 않고 따로 보여 준다.
// 예: (일자리, 취업)·(일자리, 창업)·(교육, 미래역량강화) → '일자리 · 취업, 창업 / 교육 · 미래역량강화'
export function policyClassificationLabel(categories: OpportunityCategory[] | null | undefined): string {
  const groups = new Map<string, string[]>()
  const unpaired: string[] = []
  for (const row of categories ?? []) {
    const large = row.largeCategory?.trim()
    const medium = row.mediumCategory?.trim()
    if (large) {
      const mediums = groups.get(large) ?? []
      if (medium && !mediums.includes(medium)) mediums.push(medium)
      groups.set(large, mediums)
    } else if (medium && !unpaired.includes(medium)) unpaired.push(medium)
  }
  const labels = [...groups].map(([large, mediums]) => mediums.length ? `${large} · ${mediums.join(', ')}` : large)
  return [...labels, ...(unpaired.length ? [unpaired.join(', ')] : [])].join(' / ')
}

// 목록 카드·홈 카드·저장 목록·상세가 같은 분류 문구를 쓰도록 공고 유형으로 나눈다.
export function classificationLabel(item: { type: OpportunityType; subCategory?: string | null; detailCategory?: string | null; categories?: OpportunityCategory[] | null }): string {
  if (item.type !== 'VOCATIONAL_TRAINING') return policyClassificationLabel(item.categories)
  // 직업훈련은 중분류(NCS 1차)·세부 분류(NCS 2차)를 보여 준다. 미분류 대체값 '직업훈련'은 실제 NCS 분류가 있으면 생략하고,
  // 하나도 없으면 '직업훈련'으로 남긴다.
  const labels = [item.subCategory, item.detailCategory]
    .map(value => value?.trim()).filter((value): value is string => !!value && value !== typeLabels.VOCATIONAL_TRAINING)
  return labels.length ? [...new Set(labels)].join(' · ') : typeLabels.VOCATIONAL_TRAINING
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
    keyword: '', type: '', category: '', categoryType: '', subCategory: '', detailCategory: '', region: '', eligibilityStatuses: [], applicationStatus: '',
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

// 분류 유형(type 또는 categoryType)이 바뀌면 그 유형의 분류가 아니므로 분류 선택을 지운다.
// 유형을 '전체'로 바꿀 때는 지금 분류의 유형을 categoryType으로 남겨 같은 분류 조건을 유지한다.
export function changeSearchType(params: SearchParams, type: SearchParams['type']): SearchParams {
  const scope = params.type || params.categoryType
  const hasCategory = !!(params.category || params.subCategory || params.detailCategory)
  // 직업훈련 분류는 유형을 고를 때 자동으로 정해지므로 직업훈련에서 다른 유형(전체 포함)으로 나가면 남기지 않는다.
  const keepCategory = !scope || scope === type || (!type && scope !== 'VOCATIONAL_TRAINING')
  return {
    ...params, type, page: 0,
    ...(keepCategory ? {} : { category: '', subCategory: '', detailCategory: '' }),
    categoryType: !type && hasCategory && keepCategory ? scope : '',
    deadlineFrom: type === 'YOUTH_POLICY' ? params.deadlineFrom : '',
    deadlineTo: type === 'YOUTH_POLICY' ? params.deadlineTo : '',
    startDateFrom: type === 'VOCATIONAL_TRAINING' ? params.startDateFrom : '',
    startDateTo: type === 'VOCATIONAL_TRAINING' ? params.startDateTo : '',
    sort: availableSorts(type, params.keyword).includes(params.sort) ? params.sort : defaultSort(params.keyword),
  }
}

// 필터 초기화: 키워드와 페이지 크기만 남기고 유형·분류(categoryType 포함)·기간·지역 등을 기본값으로 되돌린다.
export function resetSearchFilters(applied: SearchParams): SearchParams {
  return { ...defaultSearchParams(), keyword: applied.keyword, sort: defaultSort(applied.keyword), size: applied.size }
}

// 직업훈련 유형의 대분류는 하나(원본 교육･직업훈련)뿐이라 유형을 고르면 바로 선택한다. 분류 목록을 아직 받지 못했으면 그대로 둔다.
export function selectSingleTrainingCategory(params: SearchParams, options: InterestOptions | undefined): SearchParams {
  if (params.type !== 'VOCATIONAL_TRAINING' || params.category) return params
  const entries = categoryEntries(options, 'VOCATIONAL_TRAINING')
  return entries.length === 1 ? changeSearchCategory(params, 'category', entries[0].category) : params
}

// 대분류를 고를 때 유형 전체 검색이면 그 대분류의 유형(categoryType)을 함께 정한다. type이 있으면 type이 유형을 정한다.
export function changeSearchCategory(params: SearchParams, level: 'category' | 'subCategory' | 'detailCategory', value: string,
  categoryType: OpportunityType | '' = ''): SearchParams {
  return {
    ...params, [level]: value, page: 0,
    ...(level === 'category' ? { subCategory: '', detailCategory: '', categoryType: value && !params.type ? categoryType : '' } : {}),
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
  // 분류 유형은 유형 전체 검색에서 분류가 있을 때만 쓴다. type과 다르면 백엔드가 거절하므로 분류 조건과 함께 정리한다.
  const categoryType = url.get('categoryType')
  if (categoryType === 'YOUTH_POLICY' || categoryType === 'VOCATIONAL_TRAINING') {
    if (params.type && params.type !== categoryType) {
      params.category = params.subCategory = params.detailCategory = ''
      invalid()
    } else if (!params.type && (params.category || params.subCategory || params.detailCategory)) params.categoryType = categoryType
  } else if (categoryType) invalid()
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
  for (const key of ['keyword', 'type', 'category', 'subCategory', 'detailCategory', 'categoryType', 'region', 'applicationStatus',
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
