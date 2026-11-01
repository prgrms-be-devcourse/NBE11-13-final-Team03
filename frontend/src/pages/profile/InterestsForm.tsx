import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, X } from 'lucide-react'
import type { AuthUser } from '../../api/auth'
import { assertOwner, interestKey, saveInterests } from '../../api/profile'
import type { Interest } from '../../api/profile'
import { getInterestOptions } from '../../api/opportunities'
import Button from '../../components/ui/Button'
import AsyncState from '../../components/ui/AsyncState'
import ProfileError from './ProfileError'
import { interestOptions, toggleInterest } from './interestModel'
import { categoryDisplayLabel, trainingCategory } from '../opportunities/searchParams'
import styles from './Profile.module.css'

function InterestsForm({ user, initial, onSaved, onSkip, onBack }: { user: AuthUser; initial: Interest[]; onSaved?: () => void; onSkip?: () => void; onBack?: () => void }) {
  const client = useQueryClient()
  const options = useQuery({ queryKey: ['interest-options'], queryFn: ({ signal }) => getInterestOptions(signal), staleTime: 300_000 })
  const [selected, setSelected] = useState(initial)
  // 마지막으로 서버에서 확인한 값. PUT은 전체 교체라 순서와 무관하게 선택 키 집합이 다를 때만 저장할 변경이 있다.
  const [baseline, setBaseline] = useState(initial)
  const [saved, setSaved] = useState(false)
  const baselineKeys = new Set(baseline.map(interestKey))
  const changed = selected.length !== baseline.length || selected.some((item) => !baselineKeys.has(interestKey(item)))
  const controller = useRef<AbortController | null>(null)
  const submitting = useRef(false)
  useEffect(() => { const active = new AbortController(); controller.current = active; return () => active.abort() }, [])
  const mutation = useMutation({ mutationFn: async () => {
    const signal = controller.current!.signal
    const result = await saveInterests(client, user, selected, signal)
    assertOwner(client, user, signal)
    return result
  } })
  const available = options.data ? interestOptions(options.data) : []
  const keys = new Set(available.map(interestKey))
  const unknown = selected.filter((item) => !keys.has(interestKey(item)))
  const checked = (item: Interest) => selected.some((value) => interestKey(value) === interestKey(item))
  const toggle = (item: Interest) => { setSelected((current) => toggleInterest(current, item)); setSaved(false) }
  const checkbox = (item: Interest, label: string) => <label key={interestKey(item)} className={styles.chip}><input type="checkbox" checked={checked(item)} disabled={mutation.isPending || (!checked(item) && selected.length >= 30)} onChange={() => toggle(item)} />{label}</label>
  // 선택 요약의 이름은 API 문자열을 그대로 쓴다. 대분류만 화면 이름과 '대분류'를 붙인다.
  const selectedLabel = (item: Interest) => !keys.has(interestKey(item)) ? `${item.code} (기존 값)` : item.type === 'CATEGORY' ? `${categoryDisplayLabel(item.code)} 대분류` : item.code
  // 직업훈련 추천은 중분류(NCS 1차)·세부 분야만 매칭한다(RecommendationInterestMatcher). 대분류는 이미 저장된 경우에만 해제할 수 있게 보여 준다.
  const showCategory = (category: string) => category !== trainingCategory || initial.some((item) => interestKey(item) === interestKey({ type: 'CATEGORY', code: category }))
  const submit = async () => {
    if (submitting.current || !options.isSuccess || !changed) return
    submitting.current = true
    try {
      const result = await mutation.mutateAsync()
      if (!controller.current?.signal.aborted) { setSelected(result); setBaseline(result); setSaved(true); onSaved?.() }
    } catch { /* mutation 오류를 아래에서 안전하게 표시한다. */ }
    finally { submitting.current = false }
  }
  return <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void submit() }}>
    <div className={styles.interestIntro}>
      <p className={styles.note}>선택 사항이에요. 대분류·중분류·세부 분야는 각각 선택되며 최대 30개까지 고를 수 있어요.</p>
      <p role="status" className={styles.counter}>선택한 관심 분야 <strong>{selected.length}</strong> / 30</p>
    </div>
    {options.isPending ? <AsyncState status="loading" title="관심 분야 선택지를 불러오고 있어요" />
      : options.isError ? <ProfileError error={options.error} onRetry={() => { void options.refetch() }} />
        : <>
          {/* 펼치지 않아도 고른 항목을 확인하고 바로 해제할 수 있다. 같은 selected 상태를 쓰므로 저장 값은 바뀌지 않는다. */}
          {selected.length > 0 && <ul className={styles.selectedList} aria-label="선택한 관심 분야">{selected.map((item) => <li key={interestKey(item)}>
            <span>{selectedLabel(item)}</span>
            <button type="button" disabled={mutation.isPending} aria-label={`${selectedLabel(item)} 선택 해제`} onClick={() => toggle(item)}><X size={14} aria-hidden="true" /></button>
          </li>)}</ul>}
          {unknown.length > 0 && <fieldset className={styles.interestSection}><legend>현재 선택지에 없는 기존 관심 분야</legend><p className={styles.note}>목록에서 제외된 분류도 자동으로 지우지 않아요. 해제할 항목만 선택을 취소해 주세요.</p>
            <div className={styles.chips}>{unknown.map((item) => checkbox(item, `${item.code} (${item.type}) · 기존 값 유지`))}</div></fieldset>}
          {options.data.categories.length === 0 && <AsyncState status="empty" title="현재 제공되는 분류가 없어요" description="새 관심 분야는 추가할 수 없어요. 기존 선택은 유지되며 직접 해제할 수 있어요." />}
          {/* API 계층(대분류 → 중분류 → 세부 분야)을 그대로 따르는 한 줄 목록이다. 한 번에 한 대분류만 펼쳐 다른 분야를 길게 밀어내지 않는다.
              펼침은 표시만 바꾸며 선택 상태는 위 selected가 소유한다. */}
          <div className={styles.interestList}>{options.data.categories.map((category) => {
            const label = categoryDisplayLabel(category.category)
            const withDetails = category.subCategories.filter((sub) => sub.detailCategories.length > 0)
            // 개수는 저장 키(type+code) 기준이다. 같은 문자열이 여러 번 나와도 한 선택지로 센다.
            const items = [...new Map<string, Interest>([{ type: 'CATEGORY', code: category.category } as Interest, ...category.subCategories.map((sub) => ({ type: 'SUB_CATEGORY' as const, code: sub.subCategory })),
              ...withDetails.flatMap((sub) => sub.detailCategories.map((detail) => ({ type: 'DETAIL_CATEGORY' as const, code: detail })))].map((item) => [interestKey(item), item])).values()]
            const total = items.length - (showCategory(category.category) ? 0 : 1)
            const picked = items.filter(checked).length
            return <details key={category.category} name="interest-category" className={styles.interestGroup}>
              <summary><span className={styles.groupTitle}><strong>{label}</strong><span className={styles.note}>선택지 {total}개</span></span>
                {picked > 0 && <span className={styles.status} data-state="done">{picked}개 선택</span>}<ChevronDown size={18} aria-hidden="true" /></summary>
              <div className={styles.groupBody}>
                <div className={styles.chips} role="group" aria-label={`${label} 분류`}>
                  {showCategory(category.category) && checkbox({ type: 'CATEGORY', code: category.category }, `${label} 대분류`)}
                  {category.subCategories.map((sub) => checkbox({ type: 'SUB_CATEGORY', code: sub.subCategory }, sub.subCategory))}
                </div>
                {/* NCS 2차 세부 분야는 필요할 때만 펼친다. 선택 개수는 접힌 상태에서도 보여 준다. */}
                {withDetails.length > 0 && <div className={styles.detailGrid}>{withDetails.map((sub) => {
                  const detailPicked = sub.detailCategories.filter((detail) => checked({ type: 'DETAIL_CATEGORY', code: detail })).length
                  return <details key={sub.subCategory} className={styles.detailGroup}><summary><ChevronDown size={16} aria-hidden="true" />{sub.subCategory} 세부 분야 {sub.detailCategories.length}개{detailPicked > 0 && ` · ${detailPicked}개 선택`}</summary>
                    <div className={styles.chips} role="group" aria-label={`${sub.subCategory} 세부 분야`}>{sub.detailCategories.map((detail) => checkbox({ type: 'DETAIL_CATEGORY', code: detail }, detail))}</div>
                  </details>
                })}</div>}
              </div>
            </details>
          })}</div>
        </>}
    {selected.length === 0 && changed && <p className={styles.note}>저장하면 관심 분야가 모두 해제돼요.</p>}
    {mutation.isError && <ProfileError error={mutation.error} />}
    {saved && <p role="status">관심 분야를 저장했어요.</p>}
    <div className={`${styles.actions} ${styles.formActions}`}>
      {onBack && <Button variant="secondary" disabled={mutation.isPending} onClick={onBack}>이전 단계로 돌아가기</Button>}
      {onSkip && <Button variant="ghost" disabled={mutation.isPending} onClick={onSkip}>기존 관심 분야를 유지하고 완료</Button>}
      <Button type="submit" loading={mutation.isPending} disabled={!options.isSuccess || mutation.isPending || !changed}>관심 분야 저장</Button>
    </div>
  </form>
}
export default InterestsForm
