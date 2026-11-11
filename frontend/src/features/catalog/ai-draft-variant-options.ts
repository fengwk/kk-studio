import type { VariantDraft } from '@/features/catalog/ai-console-types'
import { variantOptionsFromModel } from '@/shared/lib/model-variant-options'

export { variantOptionsFromModel }

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
