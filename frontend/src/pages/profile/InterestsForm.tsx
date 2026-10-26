import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { AuthUser } from '../../api/auth'
import { assertOwner, interestKey, saveInterests } from '../../api/profile'
import type { Interest } from '../../api/profile'
import { getInterestOptions } from '../../api/opportunities'
import Button from '../../components/ui/Button'
import AsyncState from '../../components/ui/AsyncState'
import ProfileError from './ProfileError'
import { interestOptions, toggleInterest } from './interestModel'
import styles from './Profile.module.css'

function InterestsForm({ user, initial, onSaved, onSkip, onBack }: { user: AuthUser; initial: Interest[]; onSaved?: () => void; onSkip?: () => void; onBack?: () => void }) {
  const client = useQueryClient()
  const options = useQuery({ queryKey: ['interest-options'], queryFn: ({ signal }) => getInterestOptions(signal), staleTime: 300_000 })
  const [selected, setSelected] = useState(initial)
  const [saved, setSaved] = useState(false)
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
  const checkbox = (item: Interest, label: string) => <label className={styles.check}><input type="checkbox" checked={checked(item)} disabled={mutation.isPending || (!checked(item) && selected.length >= 30)} onChange={() => { setSelected((current) => toggleInterest(current, item)); setSaved(false) }} />{label}</label>
  const submit = async () => {
    if (submitting.current || !options.isSuccess) return
    submitting.current = true
    try {
      const result = await mutation.mutateAsync()
      if (!controller.current?.signal.aborted) { setSelected(result); setSaved(true); onSaved?.() }
    } catch { /* mutation 오류를 아래에서 안전하게 표시한다. */ }
    finally { submitting.current = false }
  }
  return <form className={styles.page} onSubmit={(event) => { event.preventDefault(); void submit() }}>
    <p className={styles.help}>관심 분야는 선택 사항이에요. 상위·하위 분류는 각각 선택되며 최대 30개까지 저장할 수 있어요. 저장은 기존 목록 전체를 교체해요.</p>
    <p role="status">선택한 관심 분야 {selected.length} / 30</p>
    {options.isPending ? <AsyncState status="loading" title="관심 분야 선택지를 불러오고 있어요" />
      : options.isError ? <ProfileError error={options.error} onRetry={() => { void options.refetch() }} />
        : <>
          {unknown.length > 0 && <fieldset><legend>현재 선택지에 없는 기존 관심 분야</legend><p className={styles.help}>원본 NCS 코드나 목록에서 제외된 분류를 자동 삭제하지 않아요. 해제할 항목만 직접 선택을 취소해 주세요.</p>{unknown.map((item) => <div key={interestKey(item)}>{checkbox(item, `${item.code} (${item.type}) · 기존 값 유지`)}</div>)}</fieldset>}
          {options.data.categories.length === 0 && <AsyncState status="empty" title="현재 제공되는 분류가 없어요" description="새 관심 분야는 추가할 수 없어요. 기존 선택은 유지되며 직접 해제할 수 있어요." />}
          {options.data.categories.map((category) => <fieldset key={category.category}><legend>{category.category}</legend>
            {checkbox({ type: 'CATEGORY', code: category.category }, `${category.category} · 대분류`)}
            {category.subCategories.map((sub) => <details key={sub.subCategory}><summary>{category.category} › {sub.subCategory}</summary><div>
              {checkbox({ type: 'SUB_CATEGORY', code: sub.subCategory }, `${sub.subCategory} · 중분류 / NCS 1차`)}
              {sub.detailCategories.map((detail) => <div key={detail}>{checkbox({ type: 'DETAIL_CATEGORY', code: detail }, `${sub.subCategory} › ${detail} · NCS 2차`)}</div>)}
            </div></details>)}
          </fieldset>)}
        </>}
    {selected.length === 0 && <p>저장하면 관심 분야가 모두 해제돼요.</p>}
    {mutation.isError && <ProfileError error={mutation.error} />}
    {saved && <p role="status">관심 분야를 저장했어요.</p>}
    <Button type="submit" loading={mutation.isPending} disabled={!options.isSuccess || mutation.isPending}>관심 분야 저장</Button>
    {onSkip && <Button variant="ghost" disabled={mutation.isPending} onClick={onSkip}>기존 관심 분야를 유지하고 완료</Button>}
    {onBack && <Button variant="secondary" disabled={mutation.isPending} onClick={onBack}>이전 단계로 돌아가기</Button>}
  </form>
}
export default InterestsForm
