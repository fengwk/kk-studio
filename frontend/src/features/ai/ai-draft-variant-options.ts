import type { VariantDraft } from '@/features/ai/ai-console-types'
import type { AgentModelDTO } from '@/shared/api/contracts'
import { extractVariantNamesFromModel } from '@/features/ai/ai-model-draft-codec'

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
  return options[0] ?? 'default'
}

export function variantOptionsFromDraft(variants: VariantDraft[], fallbackVariant?: string): string[] {
  const options = uniqueNonEmpty(variants.map((variant) => variant.name))
  if (options.length > 0) {
    return options
  }
  const fallback = trimValue(fallbackVariant) || 'default'
  return [fallback]
}

export function variantOptionsFromModel(model?: AgentModelDTO | null): string[] {
  return extractVariantNamesFromModel(model)
}
