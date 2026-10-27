// 홈·추천 검증에만 사용하는 모의 데이터다.
export const latestItem = { opportunityId: 101, type: 'YOUTH_POLICY', title: '테스트 공개 공고', category: '일자리', subCategory: '취업지원',
  detailCategory: null, createdAt: '2026-10-25T09:00:00', eligibilityStatus: null, saved: false }
export const latestResponse = { content: [latestItem], page: 0, size: 6, totalElements: 1, totalPages: 1, hasNext: false }
export const policyRecommendation = { ...latestItem, opportunityId: 102, title: '테스트 정책 추천', eligibilityStatus: 'ELIGIBLE',
  regions: [{ role: 'RESIDENCE', status: 'NATIONAL', code: null, name: null, validationStatus: 'VALID' }],
  applicationStatus: 'OPEN', applicationEndDate: '2026-11-01', trainingStartDate: null,
  recommendationReasons: { badges: ['신청 조건 충족', '관심 분야 일치', '마감 D-7'], description: '테스트 서버가 제공한 실제 판정·관심 분야 추천 근거입니다.' } }
export const trainingRecommendation = { ...latestItem, opportunityId: 103, type: 'VOCATIONAL_TRAINING', title: '테스트 훈련 추천',
  category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', eligibilityStatus: 'POSSIBLY_ELIGIBLE', createdAt: null, saved: true,
  regions: [{ role: 'IMPLEMENTATION', status: 'SPECIFIC', code: '1100000000', name: '서울', validationStatus: 'VALID' }],
  applicationStatus: 'UNKNOWN', applicationEndDate: null, trainingStartDate: '2026-11-08',
  recommendationReasons: { badges: ['신청 조건 확인 필요', '교육 시작 D-14'], description: '테스트 서버가 제공한 교육 시작 안내입니다.' } }
