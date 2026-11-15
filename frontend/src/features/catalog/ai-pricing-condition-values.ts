import type { ModelPricingConditionDTO } from '@/shared/api/generated/contracts'
import {
  isRawJsonNumber,
  type PricingScalarKind,
} from '@/features/catalog/pricing-condition-scalar'
import type { TranslationKey } from '@/shared/i18n'

/** ISO 星期序号是后端 wire 契约，显式表保证每个序号只映射到已定义的译名。 */
export const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const
export type Weekday = (typeof WEEKDAYS)[number]

export const WEEKDAY_LABELS: Record<Weekday, TranslationKey> = {
  1: 'ai.catalog.form.weekday.1',
  2: 'ai.catalog.form.weekday.2',
  3: 'ai.catalog.form.weekday.3',
  4: 'ai.catalog.form.weekday.4',
  5: 'ai.catalog.form.weekday.5',
  6: 'ai.catalog.form.weekday.6',
  7: 'ai.catalog.form.weekday.7',
}

export const SCALAR_KINDS: PricingScalarKind[] = ['string', 'number', 'boolean', 'null']

/** 时间条件的最小合法默认值：全天使用 wire 规定的 00:00-24:00。 */
const ALL_DAY_TIME = { start: '00:00', end: '24:00', timezone: 'UTC' } as const

type ConditionKey = keyof ModelPricingConditionDTO

/** 新增某个维度时的初始值；每种值都是该维度可提交的最小合法形态。 */
export function initialConditionValue(
  key: ConditionKey,
): ModelPricingConditionDTO[ConditionKey] {
  switch (key) {
    case 'all':
    case 'any':
      return [{}]
    case 'not':
      return {}
    // 显式空列表表示恒假；不能落成对象或折叠为缺省。
    case 'serviceTier':
      return []
    case 'time':
      return { ...ALL_DAY_TIME }
    case 'requestBody':
      return { path: '/', equals: '' }
    default:
      return {}
  }
}

/** 切换类型时的最小合法默认值；number 以原文对象保存，提交时才变成 JSON number。 */
export function defaultScalar(kind: PricingScalarKind): unknown {
  switch (kind) {
    case 'number':
      return { rawJson: '0' }
    case 'boolean':
      return true
    case 'null':
      return null
    case 'string':
    default:
      return ''
  }
}

export function scalarInputValue(value: unknown): string {
  if (isRawJsonNumber(value)) return 'rawJson' in value ? value.rawJson : value.rawJSON
  if (typeof value === 'string') return value
  if (typeof value === 'boolean') return String(value)
  if (typeof value === 'number') return String(value)
  return ''
}

export function scalarInputScalar(kind: PricingScalarKind, text: string): unknown {
  if (kind === 'number') return { rawJson: text }
  if (kind === 'boolean') return text === 'true'
  if (kind === 'null') return null
  return text
}
