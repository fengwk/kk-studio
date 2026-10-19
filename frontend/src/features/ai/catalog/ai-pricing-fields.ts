import type { ModelPricingDraft, PricingRuleDraft } from '@/features/ai/catalog/ai-console-types'

/** 每百万 token 的分项单价字段；基础价格与条件价格共用。 */
export type PriceField = Extract<
  keyof ModelPricingDraft & keyof PricingRuleDraft,
  | 'inputPerMillionTokens'
  | 'outputPerMillionTokens'
  | 'cacheReadPerMillionTokens'
  | 'cacheWritePerMillionTokens'
  | 'cacheWriteLongPerMillionTokens'
  | 'reasoningPerMillionTokens'
>

export const PRICING_UNIT_FIELDS: ReadonlyArray<{ field: PriceField; labelKey: string }> = [
  { field: 'inputPerMillionTokens', labelKey: 'ai.catalog.form.inputPrice' },
  { field: 'outputPerMillionTokens', labelKey: 'ai.catalog.form.outputPrice' },
  { field: 'cacheReadPerMillionTokens', labelKey: 'ai.catalog.form.cacheReadPrice' },
  { field: 'cacheWritePerMillionTokens', labelKey: 'ai.catalog.form.cacheWritePrice' },
  { field: 'cacheWriteLongPerMillionTokens', labelKey: 'ai.catalog.form.longCacheWritePrice' },
  { field: 'reasoningPerMillionTokens', labelKey: 'ai.catalog.form.reasoningPrice' },
]
