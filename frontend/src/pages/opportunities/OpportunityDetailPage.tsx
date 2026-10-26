import type { ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/ApiError'
import { isOpportunityId, opportunityDetailQuery } from '../../api/opportunityDetail'
import type { ConditionType, RequirementType } from '../../api/opportunityDetail'
import Card from '../../components/ui/Card'
import AsyncState from '../../components/ui/AsyncState'
import StatusBadge from '../../components/ui/StatusBadge'
import PreferenceActions from '../../components/opportunities/PreferenceActions'
import { statusLabels, typeLabels } from './searchParams'
import { applicationWindowText, conditionLabels, conditionPresentation, conditionResultLabels, dateRange, regionText, requirementLabels, safeExternalUrl, searchReturnPath, validationLabel } from './detailPresentation'
import styles from './OpportunityDetailPage.module.css'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return <section><Card className={styles.card}><h2>{title}</h2>{children}</Card></section>
}
function Info({ label, value }: { label: string; value?: string | null }) {
  return value?.trim() ? <div><dt>{label}</dt><dd>{value}</dd></div> : null
}
function displayDates(text: string): string {
  return text.replace(/\b(\d{4})-(\d{2})-(\d{2})\b/g, '$1.$2.$3')
}
function ExternalLink({ label, url }: { label: string; url?: string | null }) {
  const href = safeExternalUrl(url)
  return href ? <a className={styles.externalLink} href={href} target="_blank" rel="noopener noreferrer">{label} <span>(새 탭)</span></a> : null
}
function detailError(error: Error): { title: string; description: string } {
  if (error instanceof ApiError) {
    if (error.status === 404) return { title: '공고를 찾을 수 없어요', description: '공고가 없거나 현재 공개할 수 있는 정규화 정보가 없어요. 검색 목록에서 다른 기회를 찾아보세요.' }
    if (error.code === 'DEV_BACKEND_UNAVAILABLE') return { title: '개발 백엔드에 연결할 수 없어요', description: '백엔드 실행 상태와 BACKEND_URL 설정을 확인해 주세요.' }
    if (error.kind === 'network') return { title: '네트워크 연결을 확인해 주세요', description: '서버 응답을 받지 못했어요. 네트워크 연결과 서버 접근 상태를 확인해 주세요.' }
    if (error.kind === 'timeout') return { title: '상세 조회가 지연되고 있어요', description: '잠시 후 다시 시도해 주세요.' }
    if (error.status === 401 || error.status === 403) return { title: '상세 요청을 처리할 수 없어요', description: '공개 상세 조회 요청이 거부됐어요. 잠시 후 다시 시도해 주세요.' }
    if (error.status && error.status >= 500) return { title: '상세 조회 서버에 문제가 생겼어요', description: '잠시 후 다시 시도해 주세요.' }
  }
  return { title: '상세 정보를 불러오지 못했어요', description: '응답을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.' }
}

function OpportunityDetailPage() {
  const { id } = useParams()
  const location = useLocation()
  const query = useQuery(opportunityDetailQuery(id))
  const data = query.data
  const backLink = <Link className={styles.backLink} to={searchReturnPath(location.state)}>← 검색 목록으로 돌아가기</Link>
  if (!isOpportunityId(id)) return <div className={styles.page}>{backLink}<h1>기회 상세</h1><AsyncState status="error" title="공고 번호를 확인해 주세요" description="유효한 공고 주소가 아니에요. 검색 목록에서 공고를 다시 선택해 주세요." /></div>
  if (query.isPending) return <div className={styles.page}>{backLink}<h1>기회 상세</h1><AsyncState status="loading" title="기회 상세를 불러오는 중이에요" /></div>
  if (query.isError) return <div className={styles.page}>{backLink}<h1>기회 상세</h1><AsyncState status="error" {...detailError(query.error)} onRetry={query.error instanceof ApiError && query.error.status === 404 ? undefined : () => { void query.refetch() }} /></div>
  if (!data) return null
  const policy = data.type === 'YOUTH_POLICY' ? data.detail : null
  const isPolicy = data.type === 'YOUTH_POLICY'
  const training = data.type === 'VOCATIONAL_TRAINING' ? data.detail : null
  const officialLinks = [
    { label: '정책 신청 페이지', url: policy?.applicationUrl },
    { label: '원문 공고 확인', url: data.detailUrl },
    { label: '참고 자료 1', url: policy?.referenceUrl1 },
    { label: '참고 자료 2', url: policy?.referenceUrl2 },
  ].flatMap(link => {
    const url = safeExternalUrl(link.url)
    return url ? [{ label: link.label, url }] : []
  }).filter((link, index, links) => links.findIndex(other => other.url === link.url) === index)
  const classification = [...new Set([data.category, data.subCategory].map(value => value?.trim()).filter(value => value && value !== typeLabels[data.type]))].join(' · ')
  const institution = policy?.supervisingInstitutionName ?? training?.institutionName
  const hasEligibilityEvidence = !!data.eligibility && (!!data.eligibility.reason?.trim() || data.eligibility.conditions.length > 0)
  const sameInstitution = !!policy?.supervisingInstitutionName?.trim() && policy.supervisingInstitutionName.trim() === policy.operatingInstitutionName?.trim()
  const policySections = [
    { title: '정책 소개와 지원 내용', fields: [
      { label: '정책 소개', value: policy?.description }, { label: '지원 내용', value: policy?.supportContent }, { label: '지원 규모', value: policy?.supportScale },
    ] },
    { title: '신청 방법', fields: [
      { label: '신청 방법', value: policy?.applicationMethod }, { label: '선정 방법', value: policy?.screeningMethod },
      { label: '제출 서류', value: policy?.submissionDocuments }, { label: '기타 안내', value: policy?.otherNotes },
    ] },
  ]
  const trainingFields = [
    { label: '교육기관', value: training?.institutionName },
    { label: '교육기간 (신청기간과 별개)', value: training?.trainingStartDate || training?.trainingEndDate ? displayDates(dateRange(training?.trainingStartDate, training?.trainingEndDate)) : null },
    { label: '교육 장소', value: training?.address }, { label: '교육 대상', value: training?.trainingTargetName },
    { label: '실제 훈련비 (원본 제공값)', value: training?.actualTrainingCost != null ? training.actualTrainingCost.toLocaleString('ko-KR') : null },
    // 현재 상세 DTO에는 무료 여부를 확정하는 필드가 없다. 원본 0을 무료로 해석하지 않는다.
    { label: '수강비 (원본 제공값)', value: training?.courseFee === 0 ? '확인 필요' : training?.courseFee != null ? training.courseFee.toLocaleString('ko-KR') : null },
    { label: '모집 정원', value: training?.capacity != null ? `${training.capacity.toLocaleString('ko-KR')}명` : null },
    { label: '기관 연락처', value: training?.telNo },
  ].filter(field => field.value?.trim())

  const conditionsContent = !isPolicy && <>
    {data.conditions.length ? <ul className={styles.items}>{data.conditions.map((condition, index) => {
      const display = conditionPresentation(condition)
      return <li key={index}>
        <h3>{conditionLabels[condition.type]} <span className={styles.muted}>· {requirementLabels[condition.requirement]}</span></h3>
        <p className={styles.facts}>{display.text}</p>
        <p className={styles.muted}>{validationLabel(condition.validationStatus)}{!display.confirmed && ' · 확정된 신청 조건으로 해석하지 마세요.'}</p>
        {condition.evidenceText?.trim() && <details><summary>{display.confirmed ? '원문 근거 보기' : '원문 확인 필요'}</summary><p className={styles.sourceText}>{condition.evidenceText}</p></details>}
      </li>
    })}</ul> : <p>조건 정보가 제공되지 않았어요. 신청 제한이 없다는 뜻은 아니므로 공식 공고를 확인해 주세요.</p>}
  </>

  return (
    <article className={styles.page} aria-labelledby="detail-title" aria-busy={query.isFetching}>
      {backLink}
      <Card className={styles.hero}>
        <div className={styles.meta}>
          <span className={styles.type} data-opportunity-type={data.type}>{typeLabels[data.type]}</span>
          <span className={styles.applicationStatus} data-application-status={data.applicationStatus}>{data.applicationStatus === 'UNKNOWN' ? '모집 상태 미확인' : statusLabels[data.applicationStatus]}</span>
          {data.eligibility && <StatusBadge status={data.eligibility.status} />}
        </div>
        <h1 id="detail-title">{data.title || '제목 미확인'}</h1>
        {(institution?.trim() || policy?.operatingInstitutionName?.trim() || classification) && <ul className={styles.headlineInfo}>
          {institution?.trim() && <li>{isPolicy && (sameInstitution ? '주관·운영 기관: ' : '주관 기관: ')}{institution}</li>}
          {isPolicy && !sameInstitution && policy?.operatingInstitutionName?.trim() && <li>운영 기관: {policy.operatingInstitutionName}</li>}
          {classification && <li>{classification}</li>}
        </ul>}
        {isPolicy && (policy?.businessStartDate || policy?.businessEndDate) && <p className={styles.muted}>사업기간 (신청기간과 별개): {displayDates(dateRange(policy.businessStartDate, policy.businessEndDate))}</p>}
        {isPolicy && policy?.businessPeriodText?.trim() && <p className={styles.muted}>사업기간 원문 안내: {policy.businessPeriodText}</p>}
        {data.regions.length > 0 && <ul className={styles.headlineRegions}>{data.regions.map((region, index) => <li key={index}>{regionText(region, data.type === 'VOCATIONAL_TRAINING')}</li>)}</ul>}
        {data.regions.length === 0 ? <p className={styles.muted}>지역 정보 미제공 · 전국 대상 여부는 확인이 필요해요.</p>
          : !isPolicy ? <p className={styles.muted}>시행·교육 지역은 거주지 자격 조건과 다를 수 있어요.</p>
            : <p className={styles.muted}>거주지 조건은 신청 자격 기준이며, 정책 시행 지역과 다를 수 있어요.</p>}
        {!data.eligibility && <p className={styles.muted}>판정 결과가 없어요. 신청 불가를 뜻하지 않아요.</p>}
      </Card>
      {query.isFetching && <p role="status" className={styles.muted}>최신 상세 정보를 확인하고 있어요.</p>}
      {!data.detail && <p role="status" className={styles.notice}>유형별 상세 정보가 일부 제공되지 않았어요. 확인 가능한 공통 정보만 표시해요.</p>}
      <div className={styles.columns}>
        <div className={styles.summary}>
          <Section title="신청 및 저장">
            <div className={styles.summaryActions}>
              {officialLinks.map(link => <ExternalLink key={link.url} label={link.label} url={link.url} />)}
              <PreferenceActions opportunityId={data.opportunityId} title={data.title} saved={data.saved} source="OTHER" disabled={query.isFetching} showSaveLabel />
            </div>
            {officialLinks.length === 0 && <p className={styles.muted}>공식 링크가 제공되지 않았어요.</p>}
            <p className={styles.muted}>신청 조건과 기간은 제공 기관의 공식 공고에서 확인해 주세요.</p>
            {hasEligibilityEvidence && data.eligibility && <div className={styles.eligibilityDetails}>
              <h3>신청 가능성 판정 근거</h3>
              {data.eligibility.reason?.trim() && <p className={styles.sourceText}>{data.eligibility.reason}</p>}
              {data.eligibility.conditions.length > 0 && <ul className={styles.items}>{data.eligibility.conditions.map((condition, index) => <li key={index}>
                <div className={isPolicy ? styles.conditionHeader : undefined}>
                  <h3>{Object.hasOwn(conditionLabels, condition.type) ? conditionLabels[condition.type as ConditionType] : '추가 조건'} · {Object.hasOwn(requirementLabels, condition.requirement) ? requirementLabels[condition.requirement as RequirementType] : '성격 확인 필요'}</h3>
                  <p className={isPolicy ? styles.conditionResult : undefined}>{Object.hasOwn(conditionResultLabels, condition.result) ? conditionResultLabels[condition.result] : '결과 확인 필요'}</p>
                </div>
                {condition.reason?.trim() && <p className={styles.sourceText}>{condition.reason}</p>}
              </li>)}</ul>}
              <p className={styles.muted}>저장된 판정 결과이며 최종 신청 자격을 보장하지 않아요.</p>
            </div>}
          </Section>
        </div>
        <div className={`${styles.stack} ${styles.mainContent}`}>
          <Section title={isPolicy ? '신청기간' : '모집기간'}>
            {data.applicationWindows.length ? <ul className={styles.items}>{data.applicationWindows.map((window, index) => <li key={index}>
              <p className={styles.facts}>{displayDates(applicationWindowText(window, !isPolicy))}</p>
              {/* 검증된 기간은 원문이 매핑 결과와 중복되므로, 확인이 필요한 경우에만 근거로 보여 준다. */}
              {window.validationStatus !== 'VALID' && window.periodText?.trim() && <p className={styles.sourceText}>원문 기간 안내: {window.periodText}</p>}
            </li>)}</ul> : <p>{isPolicy ? '신청기간' : '모집기간'} 정보가 제공되지 않았어요.</p>}
            <p className={styles.muted}>{isPolicy ? '사업기간과 신청기간은 다를 수 있습니다.' : '교육기간과 모집기간은 다를 수 있습니다.'}</p>
          </Section>
          {isPolicy && policySections.map(section => section.fields.some(field => field.value?.trim()) && <Section key={section.title} title={section.title}>
              <dl className={styles.info}>{section.fields.map(field => <Info key={field.label} {...field} />)}</dl>
            </Section>)}
          {data.type === 'VOCATIONAL_TRAINING' && <Section title="직업훈련 교육 정보">
            {trainingFields.length ? <dl className={`${styles.info} ${styles.trainingInfo}`}>{trainingFields.map(field => <Info key={field.label} {...field} />)}</dl>
              : <p className={styles.muted}>교육 상세 정보가 제공되지 않았어요.</p>}
            <p className={styles.muted}>정확한 수강료와 본인 부담금은 교육기관에 확인해 주세요.</p>
          </Section>}
          {!isPolicy && <Section title="지원·참여 조건">{conditionsContent}</Section>}
        </div>
      </div>
    </article>
  )
}

export default OpportunityDetailPage
