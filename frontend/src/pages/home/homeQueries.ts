import { searchOpportunities } from '../../api/opportunities.ts'
import { ApiError } from '../../api/ApiError.ts'
import { changeSearchConditions, defaultSearchParams, serializeSearchParams } from '../opportunities/searchParams.ts'

export function homeLatestQuery() {
  const params = { ...defaultSearchParams(), size: 6 }
  return {
    queryKey: ['home', 'opportunities', serializeSearchParams(params).toString()],
    queryFn: ({ signal }: { signal: AbortSignal }) => searchOpportunities(params, signal),
  }
}

export function homeSearchPath(keyword: string): string {
  const query = serializeSearchParams(changeSearchConditions(defaultSearchParams(), { keyword })).toString()
  return `/opportunities${query ? `?${query}` : ''}`
}

export function homeErrorMessage(error: Error): { title: string; description: string } {
  if (error instanceof ApiError) {
    if (error.code === 'DEV_BACKEND_UNAVAILABLE') return { title: '개발 백엔드에 연결할 수 없어요', description: '백엔드 실행 상태와 BACKEND_URL 설정을 확인해 주세요.' }
    if (error.kind === 'network') return { title: '네트워크 연결을 확인해 주세요', description: '서버 응답을 받지 못했어요. 연결 상태를 확인한 후 다시 시도해 주세요.' }
    if (error.kind === 'timeout') return { title: '응답이 지연되고 있어요', description: '잠시 후 다시 시도해 주세요.' }
    if (error.status && error.status >= 500) return { title: '서버에 문제가 생겼어요', description: '잠시 후 다시 시도해 주세요.' }
    if (error.status === 401 || error.status === 403) return { title: '요청을 처리할 수 없어요', description: '서버에서 요청이 거부됐어요. 잠시 후 다시 확인해 주세요.' }
  }
  return { title: '기회를 불러오지 못했어요', description: '응답을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.' }
}
