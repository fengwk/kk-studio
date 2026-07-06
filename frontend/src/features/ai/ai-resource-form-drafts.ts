import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'

let editorIdSeed = 0

function nextEditorId(prefix: string): string {
  editorIdSeed += 1
  return `${prefix}-${editorIdSeed}`
}

export function createKeyValueDraft(key = '', value = ''): KeyValueDraft {
  return {
    id: nextEditorId('kv-row'),
    key,
    value,
  }
}

export function blankVariant(name = ''): VariantDraft {
  return {
    id: nextEditorId('variant-row'),
    name,
    temperature: '',
    maxOutputTokens: '',
    extras: [],
  }
}
