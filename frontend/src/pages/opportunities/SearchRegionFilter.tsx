import { useId } from 'react'
import Button from '../../components/ui/Button'
import { buildRegionTree, chooseRegionLevel, regionPath } from '../profile/regionSelection'
import type { RegionSource } from '../profile/regionSelection'
import { isSearchRegionCode } from './searchParams'
import styles from './OpportunitiesPage.module.css'

function SearchRegionFilter({ source, value, onChange }: { source: RegionSource; value: string; onChange: (value: string) => void }) {
  const id = useId()
  const invalidCodes = source.status === 'ready' && source.options.some(option => !isSearchRegionCode(option.value))
  const tree = source.status === 'ready' && !invalidCodes ? buildRegionTree(source.options) : undefined
  const path = tree ? regionPath(tree, value) : []
  const levels = tree?.valid ? [{ options: tree.roots, value: path[0] ?? '' }] : []
  if (tree?.valid) for (let index = 0; index < path.length; index++) {
    const children = tree.children.get(path[index]) ?? []
    if (children.length) levels.push({ options: children, value: path[index + 1] ?? '' })
  }
  const missing = !!value && tree?.valid && !tree.byValue.has(value)
  return <fieldset className={styles.group} aria-busy={source.status === 'loading'}>
    <legend>지역</legend>
    {source.status === 'loading' ? <p className={styles.help} role="status">지역 목록을 불러오는 중이에요.</p>
      : source.status === 'error' ? <div className={styles.optionError}><p role="alert">지역 목록을 불러오지 못했어요.</p><Button variant="ghost" onClick={source.onRetry}>지역 목록 다시 불러오기</Button></div>
        : invalidCodes ? <p role="alert">지역 코드 형식을 확인할 수 없어요.</p>
          : !tree?.valid ? <p role="alert">지역 목록을 확인할 수 없어요.</p>
          : tree.roots.length === 0 ? <p className={styles.help} role="status">선택할 수 있는 지역이 없어요.</p>
            : levels.map((level, index) => <label className={styles.field} key={index === 0 ? 'root' : path[index - 1]}>
              {index === 0 ? '시·도' : index === 1 ? '시·군·구' : '하위 지역'}
              <select value={level.value} onChange={event => {
                // 검색은 모든 계층을 허용한다. 기존 학교 소재지의 계층 선택 규칙을 재사용한다.
                if (tree?.valid) onChange(chooseRegionLevel(tree, path, index, event.target.value, 'school').value ?? '')
              }} aria-describedby={missing ? `${id}-missing` : undefined}>
                <option value="">{index === 0 ? '전체 지역' : '선택한 상위 지역 전체'}</option>
                {level.options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </label>)}
    {missing && <p id={`${id}-missing`} className={styles.validation} role="alert">선택한 지역이 현재 목록에 없어요. 다시 선택해 주세요.</p>}
    {value && <Button variant="ghost" onClick={() => onChange('')}>지역 선택 해제</Button>}
  </fieldset>
}

export default SearchRegionFilter
