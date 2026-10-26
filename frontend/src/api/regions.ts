import type { RegionOption } from '../pages/profile/regionSelection.ts'

// 예정 필드다. 실제 API 경로·배열/객체 래퍼는 미확정이며 HTTP 요청은 구현하지 않는다.
export type RegionOptionDto = { code: string; name: string; parentCode: string | null; leaf: boolean }

// 추후 확정된 응답에서 행 목록을 꺼낸 뒤 이 경계에서 UI 모델로 변환한다.
// 코드 변환·이름 추측·불완전한 계층 보정은 하지 않는다. 계층 검증은 buildRegionTree에서 수행한다.
export function toRegionOptions(rows: readonly RegionOptionDto[]): RegionOption[] {
  return rows.map((row) => ({ value: row.code, label: row.name, parentValue: row.parentCode, isLeaf: row.leaf }))
}
