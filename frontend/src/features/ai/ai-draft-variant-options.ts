import type { VariantDraft } from '@/features/ai/ai-console-types'
import type { AgentModelDTO } from '@/shared/api/contracts'

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

function parseVariantNames(json: string | null): string[] {
  if (!json) {
    return []
  }
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed)) {
      return []
    }
    return uniqueNonEmpty(
      parsed.map((item) => {
        if (!item || typeof item !== 'object' || Array.isArray(item)) {
          return ''
        }
        return trimValue((item as Record<string, unknown>).name as string | undefined)
      }),
    )
  } catch {
    return []
  }
}

export function variantOptionsFromDraft(variants: VariantDraft[], fallbackVariant?: string): string[] {
  const options = uniqueNonEmpty(variants.map((variant) => variant.name))
  if (options.length > 0) {
    return options
  }
  const fallback = trimValue(fallbackVariant) || 'default'
  return [fallback]
}

export function variantOptionsFromModel(model?: Pick<AgentModelDTO, 'variantsJson' | 'defaultVariant'> | null): string[] {
  const options = parseVariantNames(model?.variantsJson ?? null)
  if (options.length > 0) {
    return options
  }
  const fallback = trimValue(model?.defaultVariant) || 'default'
  return [fallback]
}
