import { createContext, useContext, useEffect, useRef } from 'react'
import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query'
import type { AuthUser } from '../../api/auth'
import { adminErrorKind, adminMutationKey, assertAdminOwner } from '../../api/admin'

// 관리자 화면 안에서만 쓰는 접근 상태. 서버가 관리자 권한을 거부하면 deny로 화면 전체를 닫는다.
export type AdminAccess = { owner: AuthUser; deny: () => void }
export const AdminAccessContext = createContext<AdminAccess | undefined>(undefined)

export function useAdminAccess(): AdminAccess {
  const access = useContext(AdminAccessContext)
  if (!access) throw new Error('AdminLayout 안에서만 사용할 수 있어요.')
  return access
}

/** 관리자 API의 403 권한 거부(COLLECTION_007·RETENTION_001)를 최종 기준으로 화면을 닫는다. */
export function useDenyOnForbidden(error: unknown): void {
  const { deny } = useAdminAccess()
  useEffect(() => { if (adminErrorKind(error) === 'denied') deny() }, [error, deny])
}

/**
 * 운영 작업 공통 실행기. 같은 관리자 계정의 실행 작업(수집 시작·재시도·보관 정리)은 화면 전체에서 한 번에 하나만 진행한다.
 * 자동 재시도는 하지 않으며(queryClient 기본값), 요청 전후로 같은 관리자 세션인지 확인한다.
 */
export function useAdminAction<T, V>(owner: AuthUser, run: (variables: V) => Promise<T>) {
  const client = useQueryClient()
  const running = useRef(false)
  const busy = useIsMutating({ mutationKey: adminMutationKey(owner.id) }) > 0
  const mutation = useMutation({ mutationKey: adminMutationKey(owner.id), mutationFn: async (variables: V) => {
    assertAdminOwner(client, owner)
    const result = await run(variables)
    assertAdminOwner(client, owner)
    return result
  } })
  const start = (variables: V, onSuccess: (result: T) => void, onError?: (error: unknown) => void) => {
    if (running.current || busy) return
    running.current = true
    mutation.mutate(variables, { onSuccess, onError, onSettled: () => { running.current = false } })
  }
  return { mutation, pending: busy || mutation.isPending, start, client }
}
