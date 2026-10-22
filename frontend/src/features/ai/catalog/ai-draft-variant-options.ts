import type { AgentModelDTO } from '@/shared/api/contracts/ai-catalog'
import type { VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { extractVariantIdsFromModel } from '@/features/ai/catalog/ai-model-draft-codec'

export function trimValue(value: string | null | undefined): string {
  return typeof value === 'string' ? value.trim() : ''
}

function uniqueNonEmpty(values: string[]): string[] {
  return Array.from(new Set(values.map((value) => value.trim()).filter(Boolean)))
}

export function resolvePreferredVariant(preferred: string, options: string[]): string {
  const normalized = preferred.trim()
  if (normalized && options.includes(normalized)) {
    return normalized
  }
  return options[0] ?? ''
}

export function variantOptionsFromDraft(variants: VariantDraft[]): string[] {
  return uniqueNonEmpty(variants.map((variant) => variant.id))
}

/** 组没有组级 Variant；只有 MODEL 的配置可作为覆盖候选。 */
export function variantOptionsFromModel(model?: AgentModelDTO | null): string[] {
  return model?.kind === 'MODEL' ? extractVariantIdsFromModel(model) : []
}
