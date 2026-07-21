import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { newKeyValueDraft, newVariantDraft } from '@/features/ai/ai-resource-draft-primitives'

export function createKeyValueDraft(key = '', value = ''): KeyValueDraft {
  return newKeyValueDraft(key, value)
}

export function blankVariant(id = 'medium', reasoningEffort = ''): VariantDraft {
  return newVariantDraft({
    id: id.trim() || 'medium',
    reasoningEffort,
  })
}
