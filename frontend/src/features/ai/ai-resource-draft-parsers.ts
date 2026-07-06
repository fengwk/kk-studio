import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { newKeyValueDraft, newVariantDraft, parseScalar } from '@/features/ai/ai-resource-draft-primitives'

export function parseObjectDrafts(json: string | null): KeyValueDraft[] {
  if (!json) {
    return []
  }
  try {
    const parsed = JSON.parse(json)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return []
    }
    return Object.entries(parsed).map(([key, value]) => newKeyValueDraft(key, parseScalar(value)))
  } catch {
    return []
  }
}

export function parseStringList(json: string | null): string[] {
  if (!json) {
    return []
  }
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed)) {
      return []
    }
    return parsed.map((item) => parseScalar(item).trim()).filter(Boolean)
  } catch {
    return []
  }
}

export function parseVariantDrafts(json: string | null): VariantDraft[] {
  if (!json) {
    return [newVariantDraft({ name: 'default' })]
  }
  try {
    const parsed = JSON.parse(json)
    if (!Array.isArray(parsed) || parsed.length === 0) {
      return [newVariantDraft({ name: 'default' })]
    }
    return parsed.map((item) => {
      const record = item && typeof item === 'object' && !Array.isArray(item) ? (item as Record<string, unknown>) : {}
      const { name, temperature, maxOutputTokens, ...rest } = record
      return newVariantDraft({
        name: parseScalar(name),
        temperature: parseScalar(temperature),
        maxOutputTokens: parseScalar(maxOutputTokens),
        extras: Object.entries(rest).map(([key, value]) => newKeyValueDraft(key, parseScalar(value))),
      })
    })
  } catch {
    return [newVariantDraft({ name: 'default' })]
  }
}
