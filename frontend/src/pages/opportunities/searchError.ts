import { ApiError } from '../../api/ApiError.ts'

export function searchErrorMessage(error: Error): { title: string; description: string } {
  if (error instanceof ApiError) {
    if (error.code === 'DEV_BACKEND_UNAVAILABLE') return { title: '개발 백엔드에 연결할 수 없어요', description: '연결이 거부됐어요. 백엔드 실행 상태와 BACKEND_URL 설정을 확인해 주세요.' }
    if (error.kind === 'network') return { title: '네트워크 연결을 확인해 주세요', description: '서버 응답을 받지 못했어요. 네트워크 연결이나 서버 접근 설정을 확인한 후 다시 시도해 주세요.' }
    if (error.kind === 'timeout') return { title: '응답이 지연되고 있어요', description: '잠시 후 다시 시도해 주세요.' }
    if (error.status === 400) return { title: '검색 조건을 확인해 주세요', description: '서버에서 검색 조건을 처리하지 못했어요. 조건을 변경하거나 전체 초기화해 주세요.' }
    if (error.status === 401) return { title: '인증이 필요한 요청이에요', description: '공개 검색은 로그인 없이 사용할 수 있어요. 전체 초기화 후 다시 검색해 주세요.' }
    if (error.status === 403) return { title: '요청을 처리할 수 없어요', description: '요청이 거부되었어요. 잠시 후 다시 시도해 주세요.' }
    if (error.status === 404) return { title: '요청한 API를 찾을 수 없어요', description: '서버 주소와 API 제공 상태를 확인해 주세요.' }
    if (error.status && error.status >= 500) return { title: '검색 서버에 문제가 생겼어요', description: '잠시 후 다시 시도해 주세요. 검색 조건은 그대로 유지돼요.' }
    if (error.status) return { title: '서버에서 요청을 처리하지 못했어요', description: `HTTP ${error.status} 응답을 받았어요. 잠시 후 다시 시도해 주세요.` }
  }
  return { title: '검색 결과를 불러오지 못했어요', description: '잠시 후 다시 시도해 주세요.' }
}
