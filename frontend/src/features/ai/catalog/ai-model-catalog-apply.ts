import type { ModelDraft } from '@/features/ai/catalog/ai-console-types'
import type { ModelDefaultCandidateDTO } from '@/shared/api/contracts/ai-catalog'
import { clonePricing } from '@/features/ai/catalog/ai-model-pricing-codec'

/**
 * 目录候选只写入可确认的参数：
 * - 明确 false 的能力是有效事实，不会被缺省值覆盖；
 * - 价格只在完整受支持时整体替换，避免“填一半价格”；
 * - Variant 只在存在明确默认且全部 id 可映射时整体替换。
 * Provider、名称、Model ID 与描述保持不变。
 */
export function applyCatalogCandidate(
  draft: ModelDraft,
  candidate: ModelDefaultCandidateDTO,
): ModelDraft {
  const next: ModelDraft = { ...draft }

  if (candidate.limit?.context != null) {
    next.contextWindow = String(candidate.limit.context)
  }
  if (candidate.limit?.output != null) {
    next.maxOutputTokens = String(candidate.limit.output)
  }
  if (candidate.abilities?.tools != null) {
    next.tools = candidate.abilities.tools
  }
  if (candidate.abilities?.reasoning != null) {
    next.reasoning = candidate.abilities.reasoning
  }
  if (candidate.abilities?.inputModalities?.length) {
    next.inputModalities = [...candidate.abilities.inputModalities]
  }
  if (candidate.pricingSupported && candidate.pricing) {
    next.pricing = clonePricing(candidate.pricing)
  }

  const variants = candidate.variants ?? []
  const defaultVariant = candidate.defaultVariant?.trim() ?? ''
  const variantsMappable = variants.length > 0
    && variants.every((variant) => variant.id.trim().length > 0)
    && variants.some((variant) => variant.id.trim() === defaultVariant)
  if (variantsMappable) {
    next.variants = variants.map((variant) => ({
      draftId: `catalog-${candidate.id}-${variant.id}`,
      id: variant.id,
      reasoningEffort: variant.reasoningEffort == null ? '' : String(variant.reasoningEffort),
      protocolOptionsJson: variant.protocolOptionsJson ?? '',
    }))
    next.defaultVariant = defaultVariant
  }
  return next
}
