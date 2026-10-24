import axios from 'axios'

export type ApiErrorKind =
  | 'http'
  | 'network'
  | 'timeout'
  | 'cancelled'
  | 'csrf'
  | 'configuration'
  | 'unknown'

export type ErrorResponse = {
  code: string
  message: string
  fieldErrors: Record<string, string>
}

// AxiosError의 config/request/cause에는 쿠키나 요청 데이터가 포함될 수 있으므로 보관하지 않는다.
export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly status?: number
  readonly code?: string
  readonly fieldErrors: Readonly<Record<string, string>>

  constructor(kind: ApiErrorKind, message: string, status?: number, body?: ErrorResponse) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
    this.code = body?.code
    this.fieldErrors = body?.fieldErrors ?? {}
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseErrorResponse(value: unknown): ErrorResponse | undefined {
  if (!isRecord(value) || typeof value.code !== 'string' || typeof value.message !== 'string') {
    return undefined
  }
  if (!isRecord(value.fieldErrors) || !Object.values(value.fieldErrors).every((item) => typeof item === 'string')) {
    return undefined
  }
  return { code: value.code, message: value.message, fieldErrors: value.fieldErrors as Record<string, string> }
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  if (axios.isCancel(error)) return new ApiError('cancelled', '요청이 취소되었습니다.')
  if (!axios.isAxiosError<unknown>(error)) {
    return new ApiError('unknown', '요청을 처리하지 못했습니다.')
  }
  if (error.response) {
    const body = parseErrorResponse(error.response.data)
    return new ApiError('http', body?.message ?? '서버 요청에 실패했습니다.', error.response.status, body)
  }
  if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
    return new ApiError('timeout', '서버 응답 시간이 초과되었습니다.')
  }
  if (error.code === 'ERR_NETWORK' || error.request) {
    return new ApiError('network', '서버에 연결할 수 없습니다.')
  }
  return new ApiError('unknown', '요청을 처리하지 못했습니다.')
}
