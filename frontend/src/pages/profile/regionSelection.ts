// UI 내부 모델이다. 서버 DTO와 래퍼·경로 계약을 공유하지 않는다.
export type RegionOption = { value: string; label: string; parentValue: string | null; isLeaf: boolean }
export type RegionMode = 'residence' | 'school'
export type RegionSource =
  | { status: 'unavailable' | 'loading' }
  | { status: 'error'; onRetry?: () => void }
  | { status: 'ready'; options: readonly RegionOption[] }
export const unavailableRegions: RegionSource = { status: 'unavailable' }
type RegionTree = { valid: false } | {
  valid: true
  roots: RegionOption[]
  byValue: Map<string, RegionOption>
  children: Map<string, RegionOption[]>
}

// 완전한 2단계 목록만 선택에 사용한다. 오류가 있는 일부 행을 임의로 승격·보정하지 않는다.
export function buildRegionTree(options: readonly RegionOption[]): RegionTree {
  const byValue = new Map<string, RegionOption>()
  const children = new Map<string, RegionOption[]>()
  for (const option of options) {
    if (!option || typeof option.value !== 'string' || !option.value.trim() || typeof option.label !== 'string' || !option.label.trim() ||
      (option.parentValue !== null && typeof option.parentValue !== 'string') || typeof option.isLeaf !== 'boolean' || byValue.has(option.value)) return { valid: false }
    byValue.set(option.value, option)
  }
  const roots: RegionOption[] = []
  for (const option of options) {
    if (option.parentValue === null) { roots.push(option); continue }
    const parent = byValue.get(option.parentValue)
    if (!parent || parent.isLeaf || parent.parentValue !== null || !option.isLeaf || parent.value === option.value) return { valid: false }
    const siblings = children.get(parent.value) ?? []
    siblings.push(option); children.set(parent.value, siblings)
  }
  if (roots.some((root) => !root.isLeaf && !children.has(root.value))) return { valid: false }
  return { valid: true, roots, byValue, children }
}
export function isSelectableRegion(tree: RegionTree, value: string | null, mode: RegionMode): boolean {
  if (!tree.valid || !value) return false
  const option = tree.byValue.get(value)
  return !!option && (mode === 'school' || option.isLeaf)
}
export function regionParent(tree: RegionTree, value: string | null): string {
  if (!tree.valid || !value) return ''
  const option = tree.byValue.get(value)
  return option ? option.parentValue ?? option.value : ''
}
export function chooseRegionParent(tree: RegionTree, parentValue: string, mode: RegionMode): { parentValue: string; value: string | null } {
  if (!tree.valid) return { parentValue: '', value: null }
  const parent = tree.roots.find((option) => option.value === parentValue)
  if (!parent) return { parentValue: '', value: null }
  return { parentValue: parent.value, value: mode === 'school' || parent.isLeaf ? parent.value : null }
}
export function chooseRegionChild(tree: RegionTree, parentValue: string, childValue: string, mode: RegionMode): string | null {
  if (!tree.valid) return null
  const parent = tree.roots.find((option) => option.value === parentValue)
  if (!parent || parent.isLeaf) return null
  if (mode === 'school' && childValue === '') return parent.value
  return tree.children.get(parentValue)?.find((option) => option.value === childValue)?.value ?? null
}
// 서버에서 읽은 기존 코드의 변경 없는 PATCH만 예외적으로 허용한다. 신규/변경 코드는 목록에서 검증한다.
export function regionSelectionError(source: RegionSource, value: string | null, previous: string | null, mode: RegionMode, creating: boolean): string | undefined {
  if (!creating && previous && value === previous) {
    const tree = source.status === 'ready' ? buildRegionTree(source.options) : undefined
    if (!tree?.valid || !tree.byValue.has(previous) || isSelectableRegion(tree, value, mode)) return undefined
  }
  if (mode === 'school' && value === null) return undefined
  if (source.status !== 'ready' || !isSelectableRegion(buildRegionTree(source.options), value, mode)) {
    return mode === 'residence' ? '유효한 시·군·구를 선택해 주세요. 말단 지역만 거주지로 저장할 수 있어요.' : '목록에 있는 학교 소재지를 선택해 주세요. 시·도 단위도 사용할 수 있어요.'
  }
  return undefined
}
