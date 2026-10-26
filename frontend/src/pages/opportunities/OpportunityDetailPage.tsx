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
  return <div><dt>{label}</dt><dd>{value?.trim() ? value : '정보 미제공'}</dd></div>
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
  const training = data.type === 'VOCATIONAL_TRAINING' ? data.detail : null
  const links = [data.detailUrl, policy?.applicationUrl, policy?.referenceUrl1, policy?.referenceUrl2]
  const hasLinks = links.some((url) => safeExternalUrl(url))

  return (
    <article className={styles.page} aria-labelledby="detail-title" aria-busy={query.isFetching}>
      {backLink}
      <Card className={styles.hero}>
        <div className={styles.meta}><span className={styles.type}>{typeLabels[data.type]}</span><PreferenceActions opportunityId={data.opportunityId} title={data.title} saved={data.saved} source="OTHER" disabled={query.isFetching} /></div>
        <h1 id="detail-title">{data.title || '제목 미확인'}</h1>
        <p className={styles.muted}>{[data.category, data.subCategory].filter(Boolean).join(' · ') || '분류 정보 미제공'}</p>
        <div className={styles.statuses}>
          <span className={styles.applicationStatus}>모집 상태: {statusLabels[data.applicationStatus]}</span>
          {data.eligibility && <span className={styles.eligibilityBadge}>신청 가능성 <StatusBadge status={data.eligibility.status} /></span>}
        </div>
      </Card>
      {query.isFetching && <p role="status" className={styles.muted}>최신 상세 정보를 확인하고 있어요.</p>}
      {!data.detail && <p role="status" className={styles.notice}>유형별 상세 정보가 일부 제공되지 않았어요. 확인 가능한 공통 정보만 표시해요.</p>}
      <div className={styles.columns}>
        <div className={styles.stack}>
          <Section title="신청기간">
            {data.applicationWindows.length ? <ul className={styles.items}>{data.applicationWindows.map((window, index) => <li key={index}>
              <p>{applicationWindowText(window)}</p>
              {window.periodText?.trim() && <p className={styles.sourceText}>원문 기간 안내: {window.periodText}</p>}
            </li>)}</ul> : <p>신청기간 정보가 제공되지 않았어요.</p>}
            <p className={styles.muted}>사업기간·교육기간과 다른 정보예요. 불확실한 일정은 공식 공고에서 확인해 주세요.</p>
          </Section>
          {data.type === 'YOUTH_POLICY' && <>
            <Section title="정책 소개와 지원 내용"><dl className={styles.info}>
              <Info label="정책 소개" value={policy?.description} /><Info label="지원 내용" value={policy?.supportContent} />
              <Info label="지원 규모" value={policy?.supportScale} />
            </dl></Section>
            <Section title="신청 안내"><dl className={styles.info}>
              <Info label="신청 방법" value={policy?.applicationMethod} /><Info label="선정 방법" value={policy?.screeningMethod} />
              <Info label="제출 서류" value={policy?.submissionDocuments} /><Info label="기타 안내" value={policy?.otherNotes} />
            </dl></Section>
            <Section title="운영 기관과 사업기간"><dl className={styles.info}>
              <Info label="주관 기관" value={policy?.supervisingInstitutionName} /><Info label="운영 기관" value={policy?.operatingInstitutionName} />
              <Info label="사업기간 (신청기간과 별개)" value={dateRange(policy?.businessStartDate, policy?.businessEndDate)} />
              {policy?.businessPeriodText?.trim() && <Info label="사업기간 원문 안내" value={policy.businessPeriodText} />}
            </dl></Section>
          </>}
          {data.type === 'VOCATIONAL_TRAINING' && <Section title="직업훈련 교육 정보"><dl className={styles.info}>
            <Info label="교육기관" value={training?.institutionName} />
            <Info label="교육기간 (신청기간과 별개)" value={dateRange(training?.trainingStartDate, training?.trainingEndDate)} />
            <Info label="교육 장소" value={training?.address} /><Info label="교육 대상" value={training?.trainingTargetName} />
            <Info label="실제 훈련비 (원본 제공값)" value={training?.actualTrainingCost != null ? training.actualTrainingCost.toLocaleString('ko-KR') : null} />
            <Info label="수강비 (원본 제공값)" value={training?.courseFee != null ? training.courseFee.toLocaleString('ko-KR') : null} />
            <Info label="모집 정원" value={training?.capacity != null ? `${training.capacity.toLocaleString('ko-KR')}명` : null} />
            <Info label="기관 연락처" value={training?.telNo} />
          </dl><p className={styles.muted}>교육 일정은 실제 모집기간을 뜻하지 않아요. 비용의 단위·지원 범위·최종 본인 부담액은 공식 공고나 교육기관에서 확인해 주세요. 0으로 표시되어도 무료로 확정하지 않아요.</p></Section>}
          <Section title="지원·참여 조건">
            {data.conditions.length ? <ul className={styles.items}>{data.conditions.map((condition, index) => {
              const display = conditionPresentation(condition)
              return <li key={index}>
                <h3>{conditionLabels[condition.type]} <span className={styles.muted}>· {requirementLabels[condition.requirement]}</span></h3>
                <p>{display.text}</p>
                <p className={styles.muted}>{validationLabel(condition.validationStatus)}{!display.confirmed && ' · 확정된 신청 조건으로 해석하지 마세요.'}</p>
                {condition.evidenceText?.trim() && <details><summary>{display.confirmed ? '원문 근거 보기' : '원문 확인 필요'}</summary><p className={styles.sourceText}>{condition.evidenceText}</p></details>}
              </li>
            })}</ul> : <p>조건 정보가 제공되지 않았어요. 신청 제한이 없다는 뜻은 아니므로 공식 공고를 확인해 주세요.</p>}
          </Section>
        </div>
        <aside className={styles.stack} aria-label="지역과 개인별 판정 및 공식 링크">
          <Section title="지역 정보">
            {data.regions.length ? <ul className={styles.items}>{data.regions.map((region, index) => <li key={index}>{regionText(region, data.type === 'VOCATIONAL_TRAINING')}</li>)}</ul> : <p>지역 정보 미제공 · 전국 대상 여부는 확인이 필요해요.</p>}
            <p className={styles.muted}>시행·교육 지역은 거주지 자격 조건과 다를 수 있어요.</p>
          </Section>
          <Section title="신청 가능성 판정">
            {data.eligibility ? <>
              <StatusBadge status={data.eligibility.status} />
              <p className={styles.sourceText}>{data.eligibility.reason?.trim() || '판정 사유 미제공'}</p>
              <ul className={styles.items}>{data.eligibility.conditions.map((condition, index) => <li key={index}>
                <h3>{Object.hasOwn(conditionLabels, condition.type) ? conditionLabels[condition.type as ConditionType] : '추가 조건'} · {Object.hasOwn(requirementLabels, condition.requirement) ? requirementLabels[condition.requirement as RequirementType] : '성격 확인 필요'}</h3>
                <p>{Object.hasOwn(conditionResultLabels, condition.result) ? conditionResultLabels[condition.result] : '결과 확인 필요'}</p>
                {condition.reason?.trim() && <p className={styles.sourceText}>{condition.reason}</p>}
              </li>)}</ul>
              <p className={styles.muted}>저장된 판정 결과예요. 최종 신청 자격과 모집 상태는 공식 공고에서 함께 확인해 주세요.</p>
            </> : <p>현재 제공된 신청 가능성 판정이 없어요. 신청 불가를 의미하지 않아요.</p>}
          </Section>
          <Section title="공식 출처와 신청 경로">
            {hasLinks ? <div className={styles.links}>
              <ExternalLink label="원문 공고 확인" url={data.detailUrl} />
              <ExternalLink label="정책 신청 페이지" url={policy?.applicationUrl} />
              <ExternalLink label="참고 자료 1" url={policy?.referenceUrl1} /><ExternalLink label="참고 자료 2" url={policy?.referenceUrl2} />
            </div> : <p>현재 연결할 수 있는 공식 링크가 제공되지 않았어요.</p>}
          </Section>
        </aside>
      </div>
    </article>
  )
}

export default OpportunityDetailPage
