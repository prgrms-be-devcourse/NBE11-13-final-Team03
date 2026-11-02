export const profile = {
  birthDate: '1998-05-15', residenceRegionCode: '11680', employmentStatus: 'UNEMPLOYED', educationLevel: 'UNIVERSITY',
  enrollmentStatus: null, schoolRegionCode: null, maritalStatus: null, housingStatus: null, homelessStatus: null,
  personalIncome: null, householdIncome: null, medianIncome: null, studentLoanBracket: null, householdSize: null,
  householderStatus: null, majorField: null, specialTargets: null, militaryService: null, trainingCardStatus: null,
  kdtParticipation: null, otherTrainingParticipation: null, updatedAt: '2026-10-26T09:00:00+09:00',
}
export const user = { id: 11, nickname: '프로필 테스트 회원', email: 'fixture@example.org', role: 'USER' }
// GET /api/interests/options 형식. opportunityTypes는 대분류·중분류가 나오는 공고 유형이다.
export const options = { categories: [
  { category: '일자리', opportunityTypes: ['YOUTH_POLICY'], subCategories: [{ subCategory: '취업지원', detailCategories: [], opportunityTypes: ['YOUTH_POLICY'] }] },
  { category: '교육･직업훈련', opportunityTypes: ['VOCATIONAL_TRAINING'], subCategories: [{ subCategory: '정보기술', detailCategories: ['정보기술개발'], opportunityTypes: ['VOCATIONAL_TRAINING'] }] },
] }
// 실제 데이터의 이름 충돌: 청년정책 원본 대분류 '교육･직업훈련'(옛 분류명)과 직업훈련 대분류 '교육･직업훈련'이 한 노드로 온다.
export const sharedCategoryOptions = { categories: [
  { category: '교육', opportunityTypes: ['YOUTH_POLICY'], subCategories: [{ subCategory: '미래역량강화', detailCategories: [], opportunityTypes: ['YOUTH_POLICY'] }] },
  { category: '교육･직업훈련', opportunityTypes: ['YOUTH_POLICY', 'VOCATIONAL_TRAINING'], subCategories: [
    { subCategory: '미래역량강화', detailCategories: [], opportunityTypes: ['YOUTH_POLICY'] },
    { subCategory: '온·오프라인교육', detailCategories: [], opportunityTypes: ['YOUTH_POLICY'] },
    { subCategory: '정보통신', detailCategories: ['정보기술'], opportunityTypes: ['VOCATIONAL_TRAINING'] },
  ] },
] }
// 지역 API의 예정 행 필드를 사용한 테스트 전용 사전이다. 응답 래퍼는 가정하지 않는다.
export const regionRows = [
  { code: '1100000000', name: '서울특별시', parentCode: null, leaf: false },
  { code: '1168000000', name: '강남구', parentCode: '1100000000', leaf: true },
  { code: '4100000000', name: '경기도', parentCode: null, leaf: false },
  { code: '4136000000', name: '남양주시', parentCode: '4100000000', leaf: true },
  { code: '3611000000', name: '세종특별자치시', parentCode: null, leaf: true },
]
