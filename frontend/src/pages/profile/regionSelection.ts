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

// 전체 목록을 검증한다. 오류가 있는 행을 임의로 승격·보정하지 않는다.
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
    if (!parent || parent.isLeaf || parent.value === option.value) return { valid: false }
    const siblings = children.get(parent.value) ?? []
    siblings.push(option); children.set(parent.value, siblings)
  }
  if (options.some((option) => option.isLeaf !== !children.has(option.value))) return { valid: false }
  // 루트에 연결되지 않은 순환도 검출한다. 깊이에 의존한 재귀는 사용하지 않는다.
  const checked = new Set<string>()
  for (const option of options) {
    const visiting = new Set<string>()
    let current: RegionOption | undefined = option
    while (current && !checked.has(current.value)) {
      if (visiting.has(current.value)) return { valid: false }
      visiting.add(current.value)
      current = current.parentValue === null ? undefined : byValue.get(current.parentValue)
    }
    for (const value of visiting) checked.add(value)
  }
  return { valid: true, roots, byValue, children }
}
export function isSelectableRegion(tree: RegionTree, value: string | null, mode: RegionMode): boolean {
  if (!tree.valid || !value) return false
  const option = tree.byValue.get(value)
  return !!option && (mode === 'school' || option.isLeaf)
}
export function regionParent(tree: RegionTree, value: string | null): string {
  return regionPath(tree, value)[0] ?? ''
}
export function regionPath(tree: RegionTree, value: string | null): string[] {
  if (!tree.valid || !value || !tree.byValue.has(value)) return []
  const result: string[] = []
  let current = tree.byValue.get(value)
  while (current) { result.push(current.value); current = current.parentValue === null ? undefined : tree.byValue.get(current.parentValue) }
  return result.reverse()
}
export function chooseRegionParent(tree: RegionTree, parentValue: string, mode: RegionMode): { parentValue: string; value: string | null } {
  if (!tree.valid) return { parentValue: '', value: null }
  const parent = tree.roots.find((option) => option.value === parentValue)
  if (!parent) return { parentValue: '', value: null }
  return { parentValue: parent.value, value: mode === 'school' || parent.isLeaf ? parent.value : null }
}
export function chooseRegionChild(tree: RegionTree, parentValue: string, childValue: string, mode: RegionMode): string | null {
  if (!tree.valid) return null
  const parent = tree.byValue.get(parentValue)
  if (!parent || parent.isLeaf) return null
  if (mode === 'school' && childValue === '') return parent.value
  const child = tree.children.get(parentValue)?.find((option) => option.value === childValue)
  return child && (mode === 'school' || child.isLeaf) ? child.value : null
}
export function chooseRegionLevel(tree: RegionTree, path: readonly string[], level: number, value: string, mode: RegionMode): { path: string[]; value: string | null } {
  if (!tree.valid || level < 0 || level > path.length) return { path: [], value: null }
  const prefix = path.slice(0, level)
  const parent = prefix.at(-1)
  if (prefix.length && JSON.stringify(regionPath(tree, parent!)) !== JSON.stringify(prefix)) return { path: [], value: null }
  const options = parent ? tree.children.get(parent) ?? [] : tree.roots
  const option = options.find((item) => item.value === value)
  if (value && !option) return { path: prefix, value: null }
  const nextPath = option ? [...prefix, option.value] : prefix
  const selected = nextPath.at(-1) ?? null
  return { path: nextPath, value: isSelectableRegion(tree, selected, mode) ? selected : null }
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
