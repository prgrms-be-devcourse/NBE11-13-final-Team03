// 실제 DTO 구조를 따르는 테스트 전용 데이터다. 서비스 화면에는 주입하지 않는다.
export const condition = (patch = {}) => ({ type: 'AGE', requirement: 'REQUIRED', field: 'age', validationStatus: 'VALID',
  status: 'RANGE', min: 19, max: 34, values: [], bornOnOrAfter: null, bornOnOrBefore: null,
  incomeType: null, period: null, subject: null, minInclusive: null, maxInclusive: null, unitConfirmed: null,
  evidenceText: '만 19세부터 34세까지', ...patch })

export const policy = {
  opportunityId: 42, type: 'YOUTH_POLICY', title: '테스트 정책 상세', category: '일자리', subCategory: '취업지원',
  detailUrl: 'https://example.org/policy', createdAt: '2026-10-25T09:00:00',
  regions: [
    { role: 'RESIDENCE', status: 'NATIONAL', code: null, name: null, validationStatus: 'VALID' },
    { role: 'IMPLEMENTATION', status: 'SPECIFIC', code: '1100000000', name: '서울', validationStatus: 'VALID' },
  ],
  applicationWindows: [
    { periodType: 'FIXED', startDate: '2026-10-01', endDate: '2026-10-31', periodText: '1차 접수', validationStatus: 'VALID' },
    { periodType: 'FIXED', startDate: '2026-11-01', endDate: '2026-11-30', periodText: '2차 접수', validationStatus: 'VALID' },
  ],
  applicationStatus: 'OPEN', conditions: [condition()],
  detail: {
    policyNo: 'TEST-ONLY', description: '정책 소개 첫 문단\n다음 문단', supportContent: '취업 지원 내용', keywords: '취업,청년',
    supervisingInstitutionName: '테스트 주관 기관', operatingInstitutionName: '테스트 운영 기관',
    applicationMethod: '온라인 신청', screeningMethod: '서류 심사', submissionDocuments: '신분증',
    applicationUrl: 'https://example.org/apply', referenceUrl1: 'https://example.org/reference', referenceUrl2: null,
    businessStartDate: '2026-01-01', businessEndDate: '2026-12-31', businessPeriodText: '2026년 연간 사업',
    supportScale: '100명', otherNotes: '기타 안내', firstRegisteredDate: '2026-01-01',
  },
  eligibility: null, saved: false,
}
export const training = {
  ...policy, opportunityId: 43, type: 'VOCATIONAL_TRAINING', title: '테스트 훈련 상세', category: '교육･직업훈련', subCategory: '정보통신',
  applicationStatus: 'UNKNOWN', applicationWindows: [{ periodType: 'UNKNOWN', startDate: null, endDate: null, periodText: null, validationStatus: 'UNKNOWN' }],
  regions: [{ role: 'IMPLEMENTATION', status: 'SPECIFIC', code: '1100000000', name: '서울', validationStatus: 'NEEDS_REVIEW' }],
  detail: { courseId: 'TEST-TRAINING', courseRound: 3, institutionName: '테스트 교육기관', institutionCode: 'INTERNAL-ONLY', institutionCustomerId: 'INTERNAL-CUSTOMER',
    trainingStartDate: '2099-01-01', trainingEndDate: '2099-03-31', actualTrainingCost: 500000, courseFee: 0, capacity: 20,
    ncsCode: '12345678', trainingTargetCode: 'TEST-CODE', trainingTargetName: '국민내일배움카드', weekendTypeCode: '9', address: '서울 교육 장소', telNo: '02-1234-5678' },
}
