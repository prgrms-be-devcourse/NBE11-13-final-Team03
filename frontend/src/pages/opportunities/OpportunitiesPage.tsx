import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Search, SlidersHorizontal, X } from 'lucide-react'
import Button from '../../components/ui/Button'
import TextField from '../../components/ui/TextField'
import Card from '../../components/ui/Card'
import AsyncState from '../../components/ui/AsyncState'
import StatusBadge from '../../components/ui/StatusBadge'
import { searchErrorMessage } from './searchError'
import { getInterestOptions, searchOpportunities } from '../../api/opportunities'
import SearchFilters from './SearchFilters'
import { applicationStatusLabel, availableSorts, changeSearchConditions, defaultSearchParams, paginationPages, parseSearchParams, serializeSearchParams, sortLabels, typeLabels } from './searchParams'
import type { SearchParams, SearchSort } from './types'
import styles from './OpportunitiesPage.module.css'

function readNotices(state: unknown): string[] {
  if (!state || typeof state !== 'object' || !('searchNotices' in state) || !Array.isArray(state.searchNotices)) return []
  return state.searchNotices.filter((item: unknown): item is string => typeof item === 'string')
}

function OpportunitiesPage() {
  const [url, setUrl] = useSearchParams()
  const location = useLocation()
  const parsed = useMemo(() => parseSearchParams(url), [url])
  const { params } = parsed
  const canonical = serializeSearchParams(params).toString()
  const notices = parsed.notices.length ? parsed.notices : readNotices(location.state)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const dialogRef = useRef<HTMLDialogElement>(null)
  const filterButtonRef = useRef<HTMLButtonElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const searchFormRef = useRef<HTMLFormElement>(null)

  useEffect(() => {
    if (url.toString() !== canonical) setUrl(canonical, { replace: true, state: { searchNotices: parsed.notices } })
  }, [canonical, parsed.notices, setUrl, url])

  useEffect(() => {
    const dialog = dialogRef.current
    if (!filtersOpen || !dialog) return
    dialog.showModal()
    const previous = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previous
      dialog.close()
    }
  }, [filtersOpen])

  const results = useQuery({
    queryKey: ['opportunities', canonical],
    queryFn: ({ signal }) => searchOpportunities(params, signal),
    placeholderData: keepPreviousData,
  })
  const options = useQuery({
    queryKey: ['interest-options'], queryFn: ({ signal }) => getInterestOptions(signal), staleTime: 5 * 60_000,
  })

  function apply(next: SearchParams) {
    setUrl(serializeSearchParams(changeSearchConditions(params, next)))
    setFiltersOpen(false)
  }
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const keyword = String(new FormData(event.currentTarget).get('keyword') ?? '')
    apply({ ...params, keyword })
  }
  function goToPage(page: number) {
    setUrl(serializeSearchParams({ ...params, page }))
    headingRef.current?.focus()
    headingRef.current?.scrollIntoView({ block: 'start' })
  }

  const filterProps = {
    applied: params, options: options.data, optionsLoading: options.isPending, optionsError: options.isError ? options.error : undefined,
    onRetryOptions: () => { void options.refetch() }, onApply: apply,
  }
  const data = results.data
  const loading = results.isPending || results.isPlaceholderData
  const pages = data ? paginationPages(data.page, data.totalPages) : []
  const appliedDates = [
    params.deadlineFrom && `마감일 ${params.deadlineFrom}부터`, params.deadlineTo && `마감일 ${params.deadlineTo}까지`,
    params.startDateFrom && `교육 시작일 ${params.startDateFrom}부터`, params.startDateTo && `교육 시작일 ${params.startDateTo}까지`,
  ].filter(Boolean)

  return (
    <section className={styles.page} aria-labelledby="search-title">
      <div className={styles.intro}>
        <span className={styles.eyebrow}>나에게 필요한 기회 찾기</span>
        <h1 id="search-title">통합 기회 탐색</h1>
        <p>청년정책부터 직업훈련까지, 필요한 조건으로 차근차근 찾아보세요.</p>
      </div>
      <form ref={searchFormRef} className={styles.searchBar} onSubmit={search} role="search" aria-label="공고 제목 검색">
        <TextField key={params.keyword} label="공고 제목" name="keyword" defaultValue={params.keyword}
          placeholder="어떤 기회를 찾고 있나요?" autoComplete="off" type="search" />
        <Button type="submit"><Search size={18} aria-hidden="true" />검색</Button>
      </form>
      {notices.length > 0 && <div className={styles.notices} role="status">{notices.map((notice) => <p key={notice}>{notice}</p>)}</div>}
      <div className={styles.mobileControls}>
        <Button ref={filterButtonRef} variant="secondary" aria-haspopup="dialog" aria-controls="search-filter-dialog"
          aria-expanded={filtersOpen} onClick={() => setFiltersOpen(true)}><SlidersHorizontal size={18} aria-hidden="true" />검색 필터</Button>
      </div>
      <div className={styles.columns}>
        <aside className={styles.sidebar} aria-labelledby="filters-heading">
          <h2 id="filters-heading">검색 필터</h2>
          <SearchFilters key={canonical} {...filterProps} />
        </aside>
        <div className={styles.results} aria-busy={results.isFetching}>
          <div className={styles.resultToolbar}>
            <h2 ref={headingRef} tabIndex={-1}>검색 결과{data && !loading && !results.isError ? <span> {data.totalElements.toLocaleString('ko-KR')}건</span> : null}</h2>
            <label className={styles.sort}>정렬
              <select value={params.sort} onChange={(event) => apply({ ...params, sort: event.target.value as SearchSort })}>
                {availableSorts(params.type).map((sort) => <option key={sort} value={sort}>{sortLabels[sort]}</option>)}
              </select>
            </label>
          </div>
          <div className={styles.applied}>
            <p>{params.keyword ? `‘${params.keyword}’ · ` : ''}{params.type ? typeLabels[params.type] : '전체 유형'}{[params.category, params.subCategory, params.detailCategory, params.applicationStatus && applicationStatusLabel(params.applicationStatus, params.type), ...appliedDates].filter(Boolean).map((value) => ` · ${value}`)}</p>
            <Button variant="ghost" onClick={() => { searchFormRef.current?.reset(); apply(defaultSearchParams()) }}>전체 초기화</Button>
          </div>
          {params.applicationStatus === 'OPEN' && params.type !== 'YOUTH_POLICY' && <p className={styles.help}>직업훈련은 실제 모집기간과 별개로, 교육 시작일 기준의 개강 예정 검색을 포함해요.</p>}
          {loading ? <AsyncState status="loading" title={results.isPlaceholderData ? '변경한 조건으로 검색하고 있어요' : '검색 결과를 불러오는 중이에요'} />
            : results.isError ? <AsyncState status="error" {...searchErrorMessage(results.error)} onRetry={() => { void results.refetch() }} />
              : data && data.content.length === 0 ? <>
                <AsyncState status="empty" title={data.totalElements > 0 ? '이 페이지에는 결과가 없어요' : '조건에 맞는 기회를 찾지 못했어요'}
                  description="검색어를 바꾸거나 필터를 줄여 다시 찾아보세요." />
                {data.page > 0 && <Button variant="secondary" onClick={() => goToPage(0)}>첫 페이지로 이동</Button>}
              </> : data && <>
                {results.isFetching && <p className={styles.help} role="status">검색 결과를 새로 확인하고 있어요.</p>}
                <ul className={styles.resultList}>
                  {data.content.map((item) => <li key={item.opportunityId}>
                    <Card className={styles.resultCard}>
                      <div className={styles.cardMeta}><span className={styles.type}>{typeLabels[item.type]}</span><span>{item.saved ? '저장됨' : '저장 안 함'}</span></div>
                      <h3><Link to={`/opportunities/${item.opportunityId}`}>{item.title || '제목 미확인'}</Link></h3>
                      <p className={styles.category}>{[item.category, item.subCategory, item.detailCategory].filter(Boolean).join(' · ') || '분류 미확인'}</p>
                      <div className={styles.cardFooter}>
                        {/^\d{4}-\d{2}-\d{2}T/.test(item.createdAt) && <span>최초 등록일 <time dateTime={item.createdAt}>{item.createdAt.slice(0, 10)}</time></span>}
                        {item.eligibilityStatus !== null && <StatusBadge status={item.eligibilityStatus} />}
                      </div>
                    </Card>
                  </li>)}
                </ul>
              </>}
          {data && !loading && !results.isError && data.totalPages > 0 && <nav className={styles.pagination} aria-label="검색 결과 페이지">
            <Button variant="secondary" disabled={data.page === 0 || results.isFetching} onClick={() => goToPage(data.page - 1)}>이전</Button>
            {pages.map((page, index) => <span className={styles.pageNumber} key={page}>
              {index > 0 && page - pages[index - 1] > 1 && <span aria-hidden="true">…</span>}
              <Button variant={page === data.page ? 'primary' : 'ghost'} aria-current={page === data.page ? 'page' : undefined}
                aria-label={`${page + 1}페이지`} disabled={results.isFetching} onClick={() => goToPage(page)}>{page + 1}</Button>
            </span>)}
            <Button variant="secondary" disabled={!data.hasNext || results.isFetching} onClick={() => goToPage(data.page + 1)}>다음</Button>
          </nav>}
        </div>
      </div>
      <dialog id="search-filter-dialog" ref={dialogRef} className={styles.dialog} aria-labelledby="mobile-filter-title"
        onClose={() => { setFiltersOpen(false); filterButtonRef.current?.focus() }}>
        <div className={styles.dialogHeader}><h2 id="mobile-filter-title">검색 필터</h2><Button variant="ghost" onClick={() => setFiltersOpen(false)} aria-label="검색 필터 닫기"><X size={20} aria-hidden="true" /></Button></div>
        {filtersOpen && <SearchFilters key={canonical} {...filterProps} />}
      </dialog>
    </section>
  )
}

export default OpportunitiesPage
