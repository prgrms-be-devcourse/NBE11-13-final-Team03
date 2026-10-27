import type { InterestOptions } from '../opportunities/types.ts'
import { interestKey } from '../../api/profile.ts'
import type { Interest } from '../../api/profile.ts'
export type InterestOption = Interest & { label: string }
export function interestOptions(options: InterestOptions): InterestOption[] {
  const result: InterestOption[] = []
  for (const category of options.categories) {
    result.push({ type: 'CATEGORY', code: category.category, label: `${category.category} · 대분류` })
    for (const sub of category.subCategories) {
      result.push({ type: 'SUB_CATEGORY', code: sub.subCategory, label: `${category.category} › ${sub.subCategory} · 중분류 / NCS 1차` })
      for (const detail of sub.detailCategories) result.push({ type: 'DETAIL_CATEGORY', code: detail, label: `${category.category} › ${sub.subCategory} › ${detail} · NCS 2차` })
    }
  }
  return result
}
export function toggleInterest(current: Interest[], item: Interest): Interest[] {
  const key = interestKey(item)
  if (current.some((value) => interestKey(value) === key)) return current.filter((value) => interestKey(value) !== key)
  if (current.length >= 30) return current
  return [...current, { type: item.type, code: item.code }]
}
