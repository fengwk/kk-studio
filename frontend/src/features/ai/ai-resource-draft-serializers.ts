import type { VariantDraft } from '@/features/ai/ai-console-types'
import { coerceScalar, numberToNull } from '@/features/ai/ai-resource-draft-primitives'

export function serializeStringList(items: string[]): string | null {
  const trimmed = items.map((item) => item.trim()).filter(Boolean)
  return trimmed.length > 0 ? JSON.stringify(trimmed) : null
}

export function serializeVariantDrafts(variants: VariantDraft[], fallbackVariant?: string): string | null {
  const normalized = variants
    .map((variant) => {
      const name = variant.name.trim()
      if (!name) {
        return null
      }
      const payload: Record<string, string | number | boolean | null> = { name }
      const temperature = numberToNull(variant.temperature)
      if (temperature !== null) {
        payload.temperature = temperature
      }
      const maxOutputTokens = numberToNull(variant.maxOutputTokens)
      if (maxOutputTokens !== null) {
        payload.maxOutputTokens = maxOutputTokens
      }
      for (const extra of variant.extras) {
        const key = extra.key.trim()
        if (key) {
          payload[key] = coerceScalar(extra.value)
        }
      }
      return payload
    })
    .filter((variant): variant is Record<string, string | number | boolean | null> => Boolean(variant))

  if (normalized.length > 0) {
    return JSON.stringify(normalized)
  }

  const fallback = fallbackVariant?.trim()
  return fallback ? JSON.stringify([{ name: fallback }]) : null
}
