import assert from 'node:assert/strict'
import { test } from 'node:test'
import { formatDate, formatDateTime, formatDatesInText } from '../src/utils/date.ts'

test('date-only and offset-less server times keep their calendar values without timezone shifts', () => {
  assert.equal(formatDate('2026-10-26'), '2026.10.26')
  assert.equal(formatDate('2026-10-26T23:59:59'), '2026.10.26')
  assert.equal(formatDateTime('2026-10-26T09:12:34'), '2026.10.26 09:12')
  assert.equal(formatDateTime('2026-10-26T21:05:00.123456'), '2026.10.26 21:05')
  assert.equal(formatDateTime('2026-10-26'), '2026.10.26')
})

test('zoned instants are shown in Seoul time with a 24-hour clock regardless of browser locale', () => {
  assert.equal(formatDateTime('2026-10-26T15:30:00Z'), '2026.10.27 00:30')
  assert.equal(formatDateTime('2026-10-26T00:05:00+09:00'), '2026.10.26 00:05')
  assert.equal(formatDate('2026-10-26T15:30:00Z'), '2026.10.27')
  assert.doesNotMatch(formatDateTime('2026-10-26T13:00:00Z'), /오전|오후|AM|PM/)
})

test('unparseable values are returned unchanged and dates inside text are converted only for display', () => {
  assert.equal(formatDate('날짜 미확인'), '날짜 미확인')
  assert.equal(formatDateTime('not-a-dateZ'), 'not-a-dateZ')
  assert.equal(formatDatesInText('2026-10-01 ~ 2026-10-31'), '2026.10.01 ~ 2026.10.31')
  assert.equal(formatDatesInText('일정 확인 필요'), '일정 확인 필요')
})
