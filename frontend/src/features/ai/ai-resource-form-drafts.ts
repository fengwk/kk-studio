import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { newKeyValueDraft, newVariantDraft } from '@/features/ai/ai-resource-draft-primitives'

export function createKeyValueDraft(key = '', value = ''): KeyValueDraft {
  return newKeyValueDraft(key, value)
}

export function blankVariant(name = 'off'): VariantDraft {
  const thinkingLevel = name.trim() || 'off'
  return newVariantDraft({
    name: thinkingLevel,
    thinkingLevel,
    temperature: '',
    maxOutputTokens: '',
  })
}
