import { useId, useState } from 'react'
import Button from '../../components/ui/Button'
import { buildRegionTree, chooseRegionLevel, isSelectableRegion, regionPath } from './regionSelection'
import type { RegionMode, RegionSource } from './regionSelection'
import styles from './Profile.module.css'

type RegionSelectProps = {
  source: RegionSource
  mode: RegionMode
  value: string | null
  onChange: (value: string | null) => void
  disabled?: boolean
  error?: string
}
function RegionSelect({ source, mode, value, onChange, disabled = false, error }: RegionSelectProps) {
  const id = useId()
  const [draft, setDraft] = useState<{ path: string[]; value: string | null } | null>(null)
  const title = mode === 'residence' ? '거주지역 (필수)' : '학교 소재지 (선택)'
  const tree = source.status === 'ready' ? buildRegionTree(source.options) : undefined
  const available = tree?.valid && tree.roots.length > 0
  const draftValid = tree?.valid && draft && draft.value === value && JSON.stringify(regionPath(tree, draft.path.at(-1) ?? null)) === JSON.stringify(draft.path)
  const path = draftValid ? draft.path : tree ? regionPath(tree, value) : []
  const selected = tree?.valid ? tree.byValue.get(path.at(-1) ?? '') : undefined
  const levels = tree?.valid ? [{ options: tree.roots, value: path[0] ?? '' }] : []
  if (tree?.valid) for (let index = 0; index < path.length; index++) {
    const children = tree.children.get(path[index]) ?? []
    if (children.length) levels.push({ options: children, value: path[index + 1] ?? '' })
  }
  const missing = !!value && tree?.valid && !tree.byValue.has(value)
  const invalidValue = !!value && tree?.valid && !missing && !isSelectableRegion(tree, value, mode)
  // 거주지는 말단 지역만 저장할 수 있다. 상위 지역만 고른 상태일 때만 추가 선택을 안내한다.
  const needsChild = mode === 'residence' && path.length > 0 && !value && !!selected && !selected.isLeaf
  const description = [needsChild && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined
  let status = ''
  if (source.status === 'unavailable') status = '지역 선택 API가 없어 신규 등록과 지역 변경을 제공하지 못해요. 기존에 등록된 코드는 유지해요.'
  else if (source.status === 'loading') status = '지역 목록을 불러오고 있어요. 기존 선택은 변경하지 않아요.'
  else if (source.status === 'error') status = '지역 목록을 불러오지 못했어요. 기존 선택은 변경하지 않아요.'
  else if (!tree?.valid) status = '지역 목록의 계층을 확인할 수 없어 선택할 수 없어요. 기존 선택은 변경하지 않아요.'
  else if (!tree.roots.length) status = '제공되는 지역이 없어요. 기존 선택은 변경하지 않아요.'
  return <fieldset className={styles.region} disabled={disabled} aria-describedby={description} aria-busy={source.status === 'loading'}>
    <legend>{title}</legend>
    {!available ? <div className={styles.field}><label className={styles.srOnly} htmlFor={`${id}-current`}>{title}</label><select id={`${id}-current`} disabled value={value ?? ''} aria-describedby={`${id}-status`}>
      <option value={value ?? ''}>{value ? `등록된 지역 코드 ${value} (유지)` : '지역 선택지 제공 대기'}</option>
    </select><p id={`${id}-status`} role={source.status === 'error' || (tree && !tree.valid) ? 'alert' : 'status'}>{status}</p>{source.status === 'error' && source.onRetry && <Button variant="secondary" onClick={source.onRetry}>지역 목록 다시 시도</Button>}</div>
      : <><div className={styles.regionLevels}>
        {levels.map((level, index) => {
          const label = index === 0 ? '시·도' : index === 1 ? '시·군·구' : `하위 지역 (${index + 1}단계)`
          // 범례가 보이는 라벨 역할을 하고, 단계별 라벨은 보조기기용으로 유지한다.
          return <div key={index === 0 ? 'root' : path[index - 1]} className={styles.field}><label className={styles.srOnly} htmlFor={`${id}-level-${index}`}>{mode === 'residence' ? '거주' : '학교'} {label}</label><select id={`${id}-level-${index}`} value={level.value} aria-invalid={!!error || invalidValue} aria-describedby={description} onChange={(event) => {
            const next = chooseRegionLevel(tree, path, index, event.target.value, mode)
            setDraft(next); onChange(next.value)
          }}><option value="">{mode === 'school' && index > 0 ? '선택한 상위 지역 단위 사용' : `${label} 선택`}</option>{level.options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></div>
        })}
      </div>
        {needsChild && <p id={`${id}-hint`} className={styles.hint} role="status">하위 지역까지 선택해 주세요.</p>}
        {missing && <p className={styles.hint} role="status">현재 지역 코드 {value}는 목록에서 찾을 수 없어요. 다른 지역으로 자동 변경하지 않아요. 기존 프로필에서 변경하지 않으면 그대로 유지돼요.</p>}
        {invalidValue && <p className={styles.error} role="alert">현재 코드는 거주지로 새로 선택할 수 없는 중간 지역이에요. 유효한 말단 지역을 선택해 주세요.</p>}
      </>}
    {error && <p id={`${id}-error`} className={styles.error} role="alert">{error}</p>}
  </fieldset>
}
export default RegionSelect
