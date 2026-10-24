import { QueryClient } from '@tanstack/react-query'
import { ApiError } from './ApiError.ts'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (!(error instanceof ApiError)) return false
        if (error.kind === 'network' || error.kind === 'timeout') return failureCount < 1
        return error.kind === 'http' && error.status !== undefined && error.status >= 500 && failureCount < 1
      },
    },
    // 상태 변경 요청은 결과가 불확실해도 자동으로 반복하지 않는다.
    mutations: { retry: false },
  },
})
