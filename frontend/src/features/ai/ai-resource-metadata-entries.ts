import type { KeyValueDraft } from '@/features/ai/ai-console-types'
import { createKeyValueDraft } from '@/features/ai/ai-resource-form-drafts'

export function readMetadataEntry(entries: KeyValueDraft[], key: string): string {
  const entry = entries.find((candidate) => candidate.key.trim() === key)
  return entry?.value ?? ''
}

export function readBooleanMetadataEntry(entries: KeyValueDraft[], key: string): boolean {
  return readMetadataEntry(entries, key).trim() === 'true'
}

export function writeMetadataEntry(entries: KeyValueDraft[], key: string, value: string): KeyValueDraft[] {
  const index = findMetadataEntryIndex(entries, key)
  if (!value.trim()) {
    return index < 0 ? entries : entries.filter((_, entryIndex) => entryIndex !== index)
  }

  if (index < 0) {
    return [...entries, createKeyValueDraft(key, value)]
  }

  return entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, key, value } : entry))
}

export function writeBooleanMetadataEntry(entries: KeyValueDraft[], key: string, checked: boolean): KeyValueDraft[] {
  const value = checked ? 'true' : 'false'
  const index = findMetadataEntryIndex(entries, key)
  if (index < 0) {
    return [...entries, createKeyValueDraft(key, value)]
  }
  return entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, key, value } : entry))
}

function findMetadataEntryIndex(entries: KeyValueDraft[], key: string): number {
  return entries.findIndex((entry) => entry.key.trim() === key)
}
