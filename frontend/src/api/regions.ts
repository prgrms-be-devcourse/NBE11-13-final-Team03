import type { RegionOption } from '../pages/profile/regionSelection.ts'
import { buildRegionTree } from '../pages/profile/regionSelection.ts'
import { apiClient } from './client.ts'
import { ApiError } from './ApiError.ts'

// RegionResponse / RegionsResponse의 확정 계약. UI 모델과 분리한다.
export type RegionOptionDto = { code: string; name: string; parentCode: string | null; leaf: boolean }
export type RegionsResponse = { regions: RegionOptionDto[] }

// 코드 변환·이름 추측·불완전한 계층 보정은 하지 않는다. 계층 검증은 buildRegionTree에서 수행한다.
export function toRegionOptions(rows: readonly RegionOptionDto[]): RegionOption[] {
  return rows.map((row) => ({ value: row.code, label: row.name, parentValue: row.parentCode, isLeaf: row.leaf }))
}
export function parseRegions(data: unknown): RegionOption[] {
  const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value)
  if (!record(data) || !Array.isArray(data.regions) || !data.regions.every((row: unknown) => record(row) &&
    typeof row.code === 'string' && row.code.trim().length > 0 && typeof row.name === 'string' && row.name.trim().length > 0 &&
    (row.parentCode === null || typeof row.parentCode === 'string') && typeof row.leaf === 'boolean')) throw new ApiError('unknown', '지역 목록 응답 형식을 확인할 수 없어요.')
  const options = toRegionOptions(data.regions as RegionOptionDto[])
  if (!buildRegionTree(options).valid) throw new ApiError('unknown', '지역 목록의 계층을 확인할 수 없어요.')
  return options
}
export async function getRegions(signal?: AbortSignal): Promise<RegionOption[]> {
  return parseRegions((await apiClient.get<unknown>('/regions', { signal })).data)
}
export const regionsQuery = () => ({
  queryKey: ['regions'] as const,
  queryFn: ({ signal }: { signal: AbortSignal }) => getRegions(signal),
  staleTime: 60 * 60 * 1000,
  gcTime: 24 * 60 * 60 * 1000,
  refetchOnWindowFocus: false,
})
