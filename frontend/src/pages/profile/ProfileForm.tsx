import { useEffect, useId, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ChevronDown } from 'lucide-react'
import type { AuthUser } from '../../api/auth'
import { ApiError } from '../../api/ApiError'
import { assertOwner, saveProfile } from '../../api/profile'
import Button from '../../components/ui/Button'
import TextField from '../../components/ui/TextField'
import ProfileError from './ProfileError'
import { choices, emptyProfile, profilePatch, todayInKorea, validateProfile, validateProfileRegions } from './profileModel'
import type { AnswerStatus, Profile, ProfileInput } from './profileModel'
import RegionSelect from './RegionSelect'
import { unavailableRegions } from './regionSelection'
import type { RegionSource } from './regionSelection'
import styles from './Profile.module.css'

function ProfileForm({ user, initial, onSaved, onContinue, regions = unavailableRegions }: { user: AuthUser; initial: Profile | null; onSaved?: () => void; onContinue?: () => void; regions?: RegionSource }) {
  const client = useQueryClient()
  const [value, setValue] = useState<ProfileInput>(initial ?? emptyProfile)
  const [baseline, setBaseline] = useState<ProfileInput>(initial ?? emptyProfile)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saved, setSaved] = useState(false)
  const controller = useRef<AbortController | null>(null)
  const submitting = useRef(false)
  const id = useId()
  useEffect(() => { const active = new AbortController(); controller.current = active; return () => active.abort() }, [])
  const mutation = useMutation({ mutationFn: async (body: ProfileInput | Partial<ProfileInput>) => {
    const signal = controller.current!.signal
    const result = await saveProfile(client, user, body, initial === null, signal)
    assertOwner(client, user, signal)
    return result
  } })
  const update = <K extends keyof ProfileInput>(key: K, next: ProfileInput[K]) => { setValue((previous) => ({ ...previous, [key]: next })); setSaved(false); setErrors((previous) => ({ ...previous, [key]: '' })) }
  const select = <T extends string>(key: string, label: string, options: Record<T, string>, selected: T | null, change: (next: T | null) => void, required = false) => <div className={styles.field}>
    <label htmlFor={`${id}-${key}`}>{label} ({required ? '필수' : '선택'})</label>
    <select id={`${id}-${key}`} value={selected ?? ''} required={required} onChange={(event) => change((event.target.value || null) as T | null)} aria-invalid={!!errors[key]} aria-describedby={errors[key] ? `${id}-${key}-error` : undefined}>
      <option value="">{required ? '선택해 주세요' : '미입력'}</option>{Object.entries<string>(options).map(([code, text]) => <option key={code} value={code}>{text}</option>)}
    </select>{errors[key] && <p id={`${id}-${key}-error`} className={styles.error}>{errors[key]}</p>}
  </div>
  const numberField = (label: string, number: number | null, change: (next: number | null) => void, min = 0, max = 999999999999999, step = 1) => <TextField label={label} type="number" min={min} max={max} step={step} value={number ?? ''} onChange={(event) => change(event.target.value === '' ? null : event.target.valueAsNumber)} />
  const incomeOptions = { PROVIDED: choices.answer.PROVIDED, UNKNOWN: choices.answer.UNKNOWN }
  // 소득 항목 이름을 응답 select의 라벨로 쓴다. '값 입력'을 고른 경우에만 금액·단위를 묻는다.
  const income = (key: 'personalIncome' | 'householdIncome', label: string) => <div className={styles.incomeGroup}>
    {select(key, label, incomeOptions, value[key]?.status ?? null, (status) => update(key, status ? { status, amount: null, period: null } : null))}
    {value[key]?.status === 'PROVIDED' && <>{numberField(`${label} 금액 (원, 0원 가능)`, value[key].amount, (amount) => update(key, { ...value[key]!, amount }))}{select(`${key}-period`, `${label} 단위`, choices.period, value[key].period, (period) => update(key, { ...value[key]!, period }))}</>}
  </div>
  const submit = async () => {
    if (submitting.current) return
    const nextErrors = { ...validateProfile(value), ...validateProfileRegions(value, initial, regions) }
    setErrors(nextErrors); setSaved(false)
    if (Object.keys(nextErrors).length) return
    const body = initial ? profilePatch(value, baseline) : value
    submitting.current = true
    try {
      const result = await mutation.mutateAsync(body)
      if (controller.current?.signal.aborted) return
      setBaseline(result); setValue(result); setSaved(true); onSaved?.()
    } catch (error) {
      if (!controller.current?.signal.aborted && error instanceof ApiError && error.status === 400) {
        // 서버 상세 메시지는 노출하지 않고 알려진 필드에 안전한 안내만 연결한다.
        setErrors(Object.fromEntries(Object.keys(error.fieldErrors).map((field) => [field.split('.')[0], '입력값과 응답 상태를 확인해 주세요.'])))
      }
    } finally { submitting.current = false }
  }
  return <form className={styles.form} noValidate onSubmit={(event) => { event.preventDefault(); void submit() }}>
    <fieldset className={styles.formSection} disabled={mutation.isPending}><legend>필수 정보</legend><div className={styles.grid}>
      <TextField label="생년월일 (필수)" type="date" required min="1900-01-01" max={todayInKorea()} value={value.birthDate} onChange={(event) => update('birthDate', event.target.value)} error={errors.birthDate} />
      <RegionSelect source={regions} mode="residence" value={value.residenceRegionCode || null} onChange={(next) => update('residenceRegionCode', next ?? '')} error={errors.residenceRegionCode} />
      {select('employmentStatus', '고용 상태', choices.employmentStatus, value.employmentStatus, (next) => update('employmentStatus', next), true)}
      {select('educationLevel', '최종 학력', choices.educationLevel, value.educationLevel, (next) => update('educationLevel', next), true)}
    </div></fieldset>
    <details className={styles.extra}><summary><span>추가 정보 (선택)<span className={styles.note}>입력하면 신청 가능성 판정에 사용돼요.</span></span><ChevronDown size={20} aria-hidden="true" /></summary><div>
      <fieldset className={styles.formSection} disabled={mutation.isPending}><legend>학적·전공</legend><div className={styles.grid}>
        {select('enrollmentStatus', '학적', choices.enrollmentStatus, value.enrollmentStatus, (next) => update('enrollmentStatus', next))}
        {select('majorField', '전공 계열', choices.majorField, value.majorField, (next) => update('majorField', next))}
        <RegionSelect source={regions} mode="school" value={value.schoolRegionCode} onChange={(next) => update('schoolRegionCode', next)} error={errors.schoolRegionCode} />
      </div></fieldset>
      <fieldset className={styles.formSection} disabled={mutation.isPending}><legend>혼인·주거</legend><div className={styles.grid}>
        {select('maritalStatus', '혼인 상태', choices.maritalStatus, value.maritalStatus, (next) => update('maritalStatus', next))}
        {select('housingStatus', '주거 상태', choices.housingStatus, value.housingStatus, (next) => update('housingStatus', next))}
        {select('homelessStatus', '무주택 여부', choices.yesNo, value.homelessStatus, (next) => update('homelessStatus', next))}
        {select('householderStatus', '세대주 여부', choices.yesNo, value.householderStatus, (next) => update('householderStatus', next))}
        {numberField('가구원 수 (선택)', value.householdSize, (next) => update('householdSize', next), 1, 99)}
        {errors.householdSize && <p className={styles.error}>{errors.householdSize}</p>}
      </div></fieldset>
      <fieldset className={styles.formSection} disabled={mutation.isPending}><legend>소득·학자금</legend><p className={styles.note}>미입력, 모름, 0원은 서로 달라요. 소득에는 ‘해당 없음’을 사용할 수 없어요.</p>
        <div className={styles.grid}>{income('personalIncome', '개인 소득')}{income('householdIncome', '가구 소득')}</div>
        <div className={styles.grid}>
          {select('medianIncome', '기준중위소득 비율 응답', choices.answer, value.medianIncome?.status ?? null, (status) => update('medianIncome', status ? { status: status as AnswerStatus, percent: null } : null))}
          {value.medianIncome?.status === 'PROVIDED' && numberField('기준중위소득 비율 (%)', value.medianIncome.percent, (percent) => update('medianIncome', { status: 'PROVIDED', percent }), 0, 999.99, 0.01)}
          {select('studentLoanBracket', '학자금 지원구간 응답', choices.answer, value.studentLoanBracket?.status ?? null, (status) => update('studentLoanBracket', status ? { status: status as AnswerStatus, bracket: null } : null))}
          {value.studentLoanBracket?.status === 'PROVIDED' && numberField('학자금 지원구간', value.studentLoanBracket.bracket, (bracket) => update('studentLoanBracket', { status: 'PROVIDED', bracket }), 1, 10)}
        </div>
      </fieldset>
      <fieldset className={styles.formSection} disabled={mutation.isPending}><legend>특화 대상·병역</legend><p className={styles.note}>민감한 정보는 필요한 경우에만 선택해 주세요.</p>
        {select('specialTargets', '특화 대상 응답', choices.answer, value.specialTargets?.status ?? null, (status) => update('specialTargets', status ? { status, targets: [] } : null))}
        {value.specialTargets?.status === 'PROVIDED' && <div className={styles.grid}>{Object.entries(choices.targets).map(([code, label]) => <label key={code} className={styles.check}><input type="checkbox" checked={value.specialTargets!.targets.includes(code as keyof typeof choices.targets)} onChange={(event) => { const target = code as keyof typeof choices.targets; update('specialTargets', { status: 'PROVIDED', targets: event.target.checked ? [...value.specialTargets!.targets, target] : value.specialTargets!.targets.filter((item) => item !== target) }) }} />{label}</label>)}</div>}
        {select('militaryService', '병역 상태', choices.military, value.militaryService?.status ?? null, (status) => update('militaryService', status ? { status, months: null } : null))}
        {value.militaryService && ['SERVING', 'COMPLETED'].includes(value.militaryService.status) && numberField('복무 개월 수 (선택)', value.militaryService.months, (months) => update('militaryService', { ...value.militaryService!, months }), 0, 2147483647)}
        {errors.militaryService && <p className={styles.error}>{errors.militaryService}</p>}
      </fieldset>
      <fieldset className={styles.formSection} disabled={mutation.isPending}><legend>직업훈련</legend><div className={styles.grid}>
        {select('trainingCardStatus', '국민내일배움카드', choices.trainingCardStatus, value.trainingCardStatus, (next) => update('trainingCardStatus', next))}
        {select('kdtParticipation', 'KDT 참여 이력', choices.trainingParticipation, value.kdtParticipation, (next) => update('kdtParticipation', next))}
        {select('otherTrainingParticipation', '기타 훈련 참여 이력', choices.trainingParticipation, value.otherTrainingParticipation, (next) => update('otherTrainingParticipation', next))}
      </div></fieldset>
    </div></details>
    {Object.values(errors).some(Boolean) && <div role="alert" className={styles.error}>입력 항목을 확인해 주세요.{Object.entries(errors).filter(([, message]) => message).map(([key, message]) => <p key={key}>{message}</p>)}</div>}
    {mutation.isError && <ProfileError error={mutation.error} />}
    {saved && <p role="status">맞춤 조건을 저장했어요. 판정 결과 반영에는 시간이 걸릴 수 있어요.</p>}
    <div className={`${styles.actions} ${styles.formActions}`}><Button type="submit" loading={mutation.isPending} disabled={Object.keys(validateProfileRegions(value, initial, regions)).length > 0 || mutation.isPending}>맞춤 조건 저장</Button>{onContinue && <Button variant="secondary" disabled={mutation.isPending} onClick={onContinue}>기존 조건으로 다음 단계</Button>}</div>
  </form>
}
export default ProfileForm
