// 관리자 API DTO 형태의 테스트 전용 데이터. 실제 운영 데이터가 아니다.
export const job = { jobId: 7, source: 'YOUTH_POLICY', status: 'FAILED', startedAt: '2026-10-31T01:00:00+09:00', completedAt: null, totalCount: null,
  fetchedCount: 10, successCount: 8, failedRecordCount: 1, pendingCount: 0, failedPageCount: 1, newCount: 3, updatedCount: 2, unchangedCount: 3,
  pageFailures: [{ pageNumber: 2, errorCode: 'API_ERROR', errorMessage: 'timeout', retryable: true }], employment24PageFailures: [], incompleteNcsCodes: [],
  recordFailures: [{ recordResultId: 1, externalId: 'R-1', pageNumber: 1, itemIndex: 0, rawRecordId: 55, status: 'FAILED', resultType: null, errorCode: 'PARSE', errorMessage: 'bad', processedAt: null }], failureReason: null }
export const preview = { status: 'READY', policy: { retainedMonths: 4, futureMonths: 12, schedule: '매월 1일 03:00 (Asia/Seoul)', schedulerEnabled: false, schedulerDryRun: true, adminExecuteEnabled: true },
  baseDate: '2026-10-31', keepFrom: '2026-07-01', toDrop: [{ name: 'user_behavior_events_2026_06', from: '2026-06-01T00:00:00', to: '2026-07-01T00:00:00', estimatedRows: 1200, totalBytes: 2048 }],
  toCreate: [], ignored: [], defaultPartitionHasRows: false, planToken: 'plan-1' }
export const rawRecord = { rawRecordId: 55, source: 'EMPLOYMENT24', externalId: 'E-1', payloadHash: 'h', collectedAt: null, lastSeenAt: null, payload: { title: '<script>x</script>' } }
