import type { EligibilityStatus } from '../../components/ui/StatusBadge'

export type OpportunityType = 'YOUTH_POLICY' | 'VOCATIONAL_TRAINING'
export type ApplicationStatus = 'UPCOMING' | 'OPEN' | 'ALWAYS_OPEN' | 'CLOSED' | 'UNKNOWN'
export type SearchSort = 'RELEVANCE' | 'LATEST' | 'OLDEST' | 'DEADLINE_PRIORITY' | 'START_DATE_ASC'

export type SearchParams = {
  keyword: string
  type: OpportunityType | ''
  category: string
  // 유형 전체 검색에서 고른 분류가 속한 공고 유형. 청년정책·직업훈련이 같은 대분류명(교육･직업훈련)을 쓰므로 함께 보낸다.
  // type이 있으면 비워 둔다 (type이 분류 유형을 정한다).
  categoryType: OpportunityType | ''
  subCategory: string
  detailCategory: string
  region: string
  eligibilityStatuses: EligibilityStatus[]
  applicationStatus: ApplicationStatus | ''
  deadlineFrom: string
  deadlineTo: string
  startDateFrom: string
  startDateTo: string
  sort: SearchSort
  page: number
  size: number
}

export type OpportunitySearchItem = {
  opportunityId: number
  type: OpportunityType
  title: string
  category: string | null
  subCategory: string | null
  detailCategory: string | null
  createdAt: string
  eligibilityStatus: EligibilityStatus | null
  saved: boolean
}

export type OpportunitySearchResponse = {
  content: OpportunitySearchItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

// GET /api/interests/options. 같은 이름의 대분류는 한 노드이며, opportunityTypes로 어느 공고 유형의 분류인지 구분한다.
export type InterestOptions = {
  categories: {
    category: string
    subCategories: { subCategory: string; detailCategories: string[]; opportunityTypes: OpportunityType[] }[]
    opportunityTypes: OpportunityType[]
  }[]
}
