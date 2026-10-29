import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import Button from '../../components/ui/Button'
import TextField from '../../components/ui/TextField'
import { applicationStatusLabel, categoryDisplayLabel, categoryMatchesType, changeSearchCategory, changeSearchType, dateRangeError, defaultSearchParams, defaultSort, statusLabels, typeLabels } from './searchParams'
import { searchErrorMessage } from './searchError'
import type { ApplicationStatus, InterestOptions, SearchParams } from './types'
import type { RegionSource } from '../profile/regionSelection'
import { buildRegionTree } from '../profile/regionSelection'
import StatusBadge from '../../components/ui/StatusBadge'
import { eligibilityStatuses, isSearchRegionCode } from './searchParams'
import SearchRegionFilter from './SearchRegionFilter'
import styles from './OpportunitiesPage.module.css'

type SearchFiltersProps = {
  applied: SearchParams
  options?: InterestOptions
  optionsLoading: boolean
  optionsError?: Error
  onRetryOptions: () => void
  onApply: (params: SearchParams) => void
  regions?: RegionSource
  authenticated?: boolean
}

function SearchFilters({ applied, options, optionsLoading, optionsError, onRetryOptions, onApply, regions = { status: 'loading' }, authenticated = false }: SearchFiltersProps) {
  const [draft, setDraft] = useState(applied)
  const id = useId()
  const categories = (options?.categories ?? []).filter(item => categoryMatchesType(item.category, draft.type))
  const category = categories.find((item) => item.category === draft.category)
  const subCategories = category?.subCategories ?? []
  const subCategory = subCategories.find((item) => item.subCategory === draft.subCategory)
  const details = subCategory?.detailCategories ?? []
  const ready = !!options && !optionsLoading && !optionsError
  const invalidCategory = !!draft.category && !category
  const invalidSubCategory = !!draft.subCategory && !subCategory
  const invalidDetail = !!draft.detailCategory && !details.includes(draft.detailCategory)
  const invalidSelection = ready && (invalidCategory || invalidSubCategory || invalidDetail)
  const optionError = optionsError ? searchErrorMessage(optionsError) : undefined
  const error = dateRangeError(draft)
  const regionTree = regions.status === 'ready' ? buildRegionTree(regions.options) : undefined
  // 목록 로딩·실패 중에는 형식만 검사해 다른 필터 적용을 막지 않는다. 목록을 받은 뒤에는 존재 여부까지 검증한다.
  const invalidRegion = !!draft.region && (!isSearchRegionCode(draft.region) || (regions.status === 'ready' && (!regionTree?.valid || !regionTree.byValue.has(draft.region))))
  const update = (key: 'detailCategory' | 'applicationStatus' | 'deadlineFrom' | 'deadlineTo' | 'startDateFrom' | 'startDateTo', value: string) => setDraft((current) => ({ ...current, [key]: value }))

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!error && !invalidSelection && !invalidRegion) onApply({ ...draft, eligibilityStatuses: authenticated ? draft.eligibilityStatuses : [] })
  }

  return (
    <form className={styles.filters} onSubmit={submit}>
      <Button className={styles.resetFilters} variant="ghost" onClick={() => setDraft({ ...defaultSearchParams(), keyword: applied.keyword, sort: defaultSort(applied.keyword), size: applied.size })}>필터 초기화</Button>
      <label className={styles.field}>공고 유형
        <select value={draft.type} onChange={(event) => setDraft(changeSearchType(draft, event.target.value as SearchParams['type']))}>
          <option value="">전체 유형</option>
          {Object.entries(typeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className={styles.field}>모집 상태
        <select value={draft.applicationStatus} onChange={(event) => update('applicationStatus', event.target.value as ApplicationStatus)}>
          <option value="">전체 상태</option>
          {Object.keys(statusLabels).map((value) => <option key={value} value={value}>{applicationStatusLabel(value as ApplicationStatus, draft.type)}</option>)}
        </select>
      </label>
      {draft.type !== 'YOUTH_POLICY' && <p className={styles.help}>훈련의 개강 예정은 접수 여부가 아닌 교육 시작일 기준이에요.</p>}

      <fieldset className={styles.group}>
        <legend>분류</legend>
        <label className={styles.field}>대분류
          <select value={draft.category} disabled={!ready || categories.length === 0} aria-describedby={`${id}-category-help`}
            onChange={(event) => setDraft(changeSearchCategory(draft, 'category', event.target.value))}>
            <option value="">전체 대분류</option>
            {invalidCategory && categoryMatchesType(draft.category, draft.type) && <option value={draft.category} disabled>기존 조건: {categoryDisplayLabel(draft.category)} (목록 확인 필요)</option>}
            {categories.map((item) => <option key={item.category} value={item.category}>{categoryDisplayLabel(item.category)}</option>)}
          </select>
        </label>
        <label className={styles.field}>중분류
          <select value={draft.subCategory} disabled={!ready || !category || subCategories.length === 0} aria-describedby={`${id}-category-help`}
            onChange={(event) => setDraft(changeSearchCategory(draft, 'subCategory', event.target.value))}>
            <option value="">전체 중분류</option>
            {invalidSubCategory && <option value={draft.subCategory} disabled>기존 조건: {draft.subCategory} (목록 확인 필요)</option>}
            {subCategories.map((item) => <option key={item.subCategory} value={item.subCategory}>{item.subCategory}</option>)}
          </select>
        </label>
        <label className={styles.field}>세부 분류
          <select value={draft.detailCategory} disabled={!ready || !subCategory || details.length === 0} aria-describedby={`${id}-category-help`}
            onChange={(event) => setDraft(changeSearchCategory(draft, 'detailCategory', event.target.value))}>
            <option value="">전체 세부 분류</option>
            {invalidDetail && <option value={draft.detailCategory} disabled>기존 조건: {draft.detailCategory} (목록 확인 필요)</option>}
            {details.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        <span id={`${id}-category-help`} className={styles.srOnly}>상위 분류를 선택하면 하위 분류를 선택할 수 있어요.</span>
        {ready && category && subCategories.length === 0 && <p className={styles.help}>이 대분류에는 중분류가 없어요.</p>}
        {ready && subCategory && details.length === 0 && <p className={styles.help}>이 중분류에는 세부 분류가 없어요.</p>}
        {invalidSelection && <p className={styles.validation} role="alert">현재 분류 조건이 목록에 없거나 상위 분류와 맞지 않아요. 상위 분류부터 다시 선택하거나 필터를 초기화해 주세요.</p>}
        {optionsLoading && <p className={styles.help} role="status">분류 목록을 불러오는 중이에요.</p>}
        {ready && categories.length === 0 && <p className={styles.help} role="status">선택할 수 있는 분류가 아직 없어요. 분류 없이 검색할 수 있어요.</p>}
        {optionError && <div className={styles.optionError}>
          <p role="alert">분류 목록을 불러오지 못했어요. {optionError.title}. {optionError.description}</p>
          <Button variant="ghost" onClick={onRetryOptions}>분류 목록 다시 불러오기</Button>
        </div>}
      </fieldset>

      {draft.type && <fieldset className={styles.group}>
        <legend>{draft.type === 'YOUTH_POLICY' ? '신청 마감일 범위' : '교육 시작일 범위'}</legend>
        <TextField label="시작 범위 (포함)" type="date" min="0001-01-01" max="9999-12-31"
          value={draft.type === 'YOUTH_POLICY' ? draft.deadlineFrom : draft.startDateFrom}
          aria-describedby={error ? `${id}-date-error` : undefined} aria-invalid={!!error}
          onChange={(event) => update(draft.type === 'YOUTH_POLICY' ? 'deadlineFrom' : 'startDateFrom', event.target.value)} />
        <TextField label="끝 범위 (포함)" type="date" min="0001-01-01" max="9999-12-31"
          value={draft.type === 'YOUTH_POLICY' ? draft.deadlineTo : draft.startDateTo}
          aria-describedby={error ? `${id}-date-error` : undefined} aria-invalid={!!error}
          onChange={(event) => update(draft.type === 'YOUTH_POLICY' ? 'deadlineTo' : 'startDateTo', event.target.value)} />
        {error && <p id={`${id}-date-error`} className={styles.validation} role="alert">{error}</p>}
      </fieldset>}

      <SearchRegionFilter source={regions} value={draft.region} onChange={region => setDraft(current => ({ ...current, region }))} />
      {authenticated && <fieldset className={styles.group}>
        <legend>신청 가능성</legend>
        {eligibilityStatuses.map(status => <label className={styles.checkbox} key={status}>
          <input type="checkbox" checked={draft.eligibilityStatuses.includes(status)} onChange={event => setDraft(current => ({ ...current, eligibilityStatuses: event.target.checked ? [...current.eligibilityStatuses, status] : current.eligibilityStatuses.filter(value => value !== status) }))} />
          <StatusBadge status={status} />
        </label>)}
      </fieldset>}
      <div className={styles.filterActions}>
        <Button type="submit" disabled={!!error || invalidSelection || invalidRegion}>필터 적용</Button>
      </div>
    </form>
  )
}

export default SearchFilters
