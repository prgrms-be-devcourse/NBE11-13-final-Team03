import type { InterestOptions } from '../opportunities/types.ts'
import { interestKey } from '../../api/profile.ts'
import type { Interest } from '../../api/profile.ts'
import { categoryEntries } from '../opportunities/searchParams.ts'
export type InterestOption = Interest & { label: string }

// 저장 키(type+code)마다 한 선택지를 만든다. 어느 공고 유형의 분류인지는 분류 API의 opportunityTypes로 정하고 이름으로 추측하지 않는다.
// 추천은 대분류 관심을 청년정책에만 쓰고 직업훈련은 중분류(NCS 1차)·세부 분야(NCS 2차)만 매칭하므로(RecommendationInterestMatcher)
// 직업훈련 대분류는 선택지로 만들지 않는다. 청년정책 대분류 '교육･직업훈련'은 청년정책 대분류 선택지다.
// 같은 코드가 여러 유형·대분류에 나오면 한 선택지로 합치고 라벨에 유형을 모두 적는다 (매칭은 코드만 본다).
export function interestOptions(options: InterestOptions): InterestOption[] {
  const result = new Map<string, { item: Interest; kinds: string[] }>()
  const add = (item: Interest, kind: string) => {
    const current = result.get(interestKey(item))
    if (!current) result.set(interestKey(item), { item, kinds: [kind] })
    else if (!current.kinds.includes(kind)) current.kinds.push(kind)
  }
  for (const entry of categoryEntries(options)) {
    const policy = entry.type === 'YOUTH_POLICY'
    if (policy) add({ type: 'CATEGORY', code: entry.category }, '청년정책 대분류')
    for (const sub of entry.subCategories) {
      add({ type: 'SUB_CATEGORY', code: sub.subCategory }, policy ? '청년정책 중분류' : '직업훈련 NCS 1차')
      for (const detail of sub.detailCategories) add({ type: 'DETAIL_CATEGORY', code: detail }, '직업훈련 NCS 2차')
    }
  }
  return [...result.values()].map(({ item, kinds }) => ({ ...item, label: `${item.code} · ${kinds.join(' / ')}` }))
}
export function toggleInterest(current: Interest[], item: Interest): Interest[] {
  const key = interestKey(item)
  if (current.some((value) => interestKey(value) === key)) return current.filter((value) => interestKey(value) !== key)
  if (current.length >= 30) return current
  return [...current, { type: item.type, code: item.code }]
}
