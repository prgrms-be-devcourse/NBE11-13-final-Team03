import type { EligibilityStatus } from '../../components/ui/StatusBadge'

export type OpportunityType = 'YOUTH_POLICY' | 'VOCATIONAL_TRAINING'
export type ApplicationStatus = 'UPCOMING' | 'OPEN' | 'ALWAYS_OPEN' | 'CLOSED' | 'UNKNOWN'
export type SearchSort = 'LATEST' | 'OLDEST' | 'DEADLINE_PRIORITY' | 'START_DATE_ASC'

export type SearchParams = {
  keyword: string
  type: OpportunityType | ''
  category: string
  subCategory: string
  detailCategory: string
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

export type InterestOptions = {
  categories: {
    category: string
    subCategories: { subCategory: string; detailCategories: string[] }[]
  }[]
}
