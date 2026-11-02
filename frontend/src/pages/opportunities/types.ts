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

// 청년정책 현재 버전의 정규화된 분류 행 (대·중분류 짝). 원본 개수가 맞지 않으면 한쪽만 있는 행이 있다.
export type OpportunityCategory = { largeCategory?: string | null; mediumCategory?: string | null }

export type OpportunitySearchItem = {
  opportunityId: number
  type: OpportunityType
  title: string
  // 공고 원문. 청년정책은 여러 분류를 쉼표로 이은 값일 수 있어 표시에는 categories를 쓴다.
  category: string | null
  subCategory: string | null
  detailCategory: string | null
  // 직업훈련과 분류 행이 없는 청년정책은 빈 배열이다. 필드가 없는 응답도 빈 배열로 본다.
  categories?: OpportunityCategory[] | null
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
