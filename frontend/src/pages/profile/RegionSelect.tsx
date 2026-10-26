import { useId, useState } from 'react'
import Button from '../../components/ui/Button'
import { buildRegionTree, chooseRegionChild, chooseRegionParent, isSelectableRegion, regionParent } from './regionSelection'
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
  const [draft, setDraft] = useState<{ parentValue: string; value: string | null } | null>(null)
  const title = mode === 'residence' ? '거주지역 (필수)' : '학교 소재지 (선택)'
  const help = mode === 'residence' ? '시·도 선택 후 말단 시·군·구를 선택해 주세요. 하위 지역이 없는 시·도는 바로 선택돼요.' : '학교 소재지는 시·도 또는 시·군·구를 선택할 수 있어요.'
  const tree = source.status === 'ready' ? buildRegionTree(source.options) : undefined
  const available = tree?.valid && tree.roots.length > 0
  const parentValue = tree?.valid ? draft?.value === value && tree.roots.some((root) => root.value === draft.parentValue) ? draft.parentValue : regionParent(tree, value) : ''
  const parent = tree?.valid ? tree.byValue.get(parentValue) : undefined
  const children = tree?.valid ? tree.children.get(parentValue) ?? [] : []
  const missing = !!value && tree?.valid && !tree.byValue.has(value)
  const invalidValue = !!value && tree?.valid && !missing && !isSelectableRegion(tree, value, mode)
  const description = `${id}-help${error ? ` ${id}-error` : ''}`
  let status = ''
  if (source.status === 'unavailable') status = '지역 선택 API가 없어 신규 등록과 지역 변경을 제공하지 못해요. 기존에 등록된 코드는 유지해요.'
  else if (source.status === 'loading') status = '지역 목록을 불러오고 있어요. 기존 선택은 변경하지 않아요.'
  else if (source.status === 'error') status = '지역 목록을 불러오지 못했어요. 기존 선택은 변경하지 않아요.'
  else if (!tree?.valid) status = '지역 목록의 계층을 확인할 수 없어 선택할 수 없어요. 기존 선택은 변경하지 않아요.'
  else if (!tree.roots.length) status = '제공되는 지역이 없어요. 기존 선택은 변경하지 않아요.'
  return <fieldset className={styles.region} disabled={disabled} aria-describedby={description} aria-busy={source.status === 'loading'}>
    <legend>{title}</legend>
    <p id={`${id}-help`} className={styles.help}>{help}</p>
    {!available ? <div className={styles.field}><label htmlFor={`${id}-current`}>{title}</label><select id={`${id}-current`} disabled value={value ?? ''} aria-describedby={`${id}-status`}>
      <option value={value ?? ''}>{value ? `등록된 지역 코드 ${value} (유지)` : '지역 선택지 제공 대기'}</option>
    </select><p id={`${id}-status`} role={source.status === 'error' || (tree && !tree.valid) ? 'alert' : 'status'}>{status}</p>{source.status === 'error' && source.onRetry && <Button variant="secondary" onClick={source.onRetry}>지역 목록 다시 시도</Button>}</div>
      : <><div className={styles.grid}>
        <div className={styles.field}><label htmlFor={`${id}-parent`}>{mode === 'residence' ? '거주 시·도' : '학교 시·도'}</label><select id={`${id}-parent`} value={parentValue} aria-invalid={!!error || invalidValue} aria-describedby={description} onChange={(event) => {
          const next = chooseRegionParent(tree, event.target.value, mode)
          setDraft(next); onChange(next.value)
        }}><option value="">시·도 선택</option>{tree.roots.map((root) => <option key={root.value} value={root.value}>{root.label}</option>)}</select></div>
        {parent?.isLeaf ? <p role="status">{parent.label} 선택 완료 · 추가 지역 선택이 필요 없어요.</p> : <div className={styles.field}><label htmlFor={`${id}-child`}>{mode === 'residence' ? '거주 시·군·구' : '학교 시·군·구'}</label><select id={`${id}-child`} value={children.some((child) => child.value === value) ? value! : ''} disabled={!parent} aria-invalid={!!error || invalidValue} aria-describedby={description} onChange={(event) => {
          const next = chooseRegionChild(tree, parentValue, event.target.value, mode)
          setDraft({ parentValue, value: next }); onChange(next)
        }}><option value="">{mode === 'school' && parent ? '선택한 시·도 단위 사용' : '시·군·구 선택'}</option>{children.map((child) => <option key={child.value} value={child.value}>{child.label}</option>)}</select></div>}
      </div>
        {missing && <p role="status">현재 지역 코드 {value}는 목록에서 찾을 수 없어요. 다른 지역으로 자동 변경하지 않아요. 기존 프로필에서 변경하지 않으면 그대로 유지돼요.</p>}
        {invalidValue && <p role="alert">현재 코드는 거주지로 새로 선택할 수 없는 중간 지역이에요. 유효한 말단 지역을 선택해 주세요.</p>}
      </>}
    {error && <p id={`${id}-error`} className={styles.error} role="alert">{error}</p>}
  </fieldset>
}
export default RegionSelect
