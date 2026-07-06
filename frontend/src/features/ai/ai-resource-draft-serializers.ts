import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { coerceScalar, numberToNull, splitCommaSeparatedValues } from '@/features/ai/ai-resource-draft-primitives'

export function serializeCapabilitiesDrafts(entries: KeyValueDraft[]): string | null {
  const result = entries.reduce<Record<string, unknown>>((acc, entry) => {
    const key = entry.key.trim()
    if (!key) {
      return acc
    }

    const value = entry.value.trim()
    if (key === 'tools') {
      acc[key] = value === 'true'
      return acc
    }

    if (key === 'input' || key === 'output') {
      const list = splitCommaSeparatedValues(value)
      if (list.length > 0) {
        acc[key] = list
      }
      return acc
    }

    if (!value) {
      return acc
    }
    acc[key] = coerceScalar(value)
    return acc
  }, {})
  return Object.keys(result).length > 0 ? JSON.stringify(result) : null
}

export function serializeNumericMetadataDrafts(entries: KeyValueDraft[]): string | null {
  const result = entries.reduce<Record<string, string | number | boolean | null>>((acc, entry) => {
    const key = entry.key.trim()
    if (!key) {
      return acc
    }

    const numeric = numberToNull(entry.value)
    if (numeric !== null) {
      acc[key] = numeric
      return acc
    }

    const value = entry.value.trim()
    if (!value) {
      return acc
    }
    acc[key] = coerceScalar(value)
    return acc
  }, {})
  return Object.keys(result).length > 0 ? JSON.stringify(result) : null
}

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
