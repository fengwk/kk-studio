import type { KeyValueDraft, VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { newKeyValueDraft, newVariantDraft } from '@/features/ai/catalog/ai-resource-draft-primitives'

export function createKeyValueDraft(key = '', value = ''): KeyValueDraft {
  return newKeyValueDraft(key, value)
}

export function blankVariant(id = 'medium', reasoningEffort = ''): VariantDraft {
  return newVariantDraft({
    id: id.trim() || 'medium',
    reasoningEffort,
  })
}
