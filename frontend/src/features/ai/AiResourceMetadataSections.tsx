import type { KeyValueDraft } from '@/features/ai/ai-console-types'
import { StructuredSection } from '@/features/ai/AiResourceFieldEditors'
import { createKeyValueDraft } from '@/features/ai/ai-resource-form-drafts'

export function CapabilitiesSection({
  entries,
  onChange,
}: {
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
}) {
  return (
    <StructuredSection label="Capabilities">
      <div className="metadata-grid metadata-grid-two">
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={readBooleanMetadataEntry(entries, 'tools')}
            onChange={(event) => onChange(writeBooleanMetadataEntry(entries, 'tools', event.target.checked))}
          />
          <span>Tools</span>
        </label>
        <label className="form-group">
          <span>Input Modalities</span>
          <input
            value={readMetadataEntry(entries, 'input')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'input', event.target.value))}
            placeholder="text, image"
          />
        </label>
        <label className="form-group">
          <span>Output Modalities</span>
          <input
            value={readMetadataEntry(entries, 'output')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'output', event.target.value))}
            placeholder="text"
          />
        </label>
      </div>
    </StructuredSection>
  )
}

export function LimitsSection({
  entries,
  onChange,
}: {
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
}) {
  return (
    <StructuredSection label="Limits">
      <div className="metadata-grid metadata-grid-three">
        <label className="form-group">
          <span>Context Window</span>
          <input
            value={readMetadataEntry(entries, 'context')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'context', event.target.value))}
            placeholder="1000000"
            inputMode="numeric"
          />
        </label>
        <label className="form-group">
          <span>Max Input</span>
          <input
            value={readMetadataEntry(entries, 'input')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'input', event.target.value))}
            placeholder="1000000"
            inputMode="numeric"
          />
        </label>
        <label className="form-group">
          <span>Max Output</span>
          <input
            value={readMetadataEntry(entries, 'output')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'output', event.target.value))}
            placeholder="1000000"
            inputMode="numeric"
          />
        </label>
      </div>
    </StructuredSection>
  )
}

export function PricingSection({
  entries,
  onChange,
}: {
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
}) {
  return (
    <StructuredSection label="Pricing">
      <div className="metadata-grid metadata-grid-two">
        <label className="form-group">
          <span>Input</span>
          <input
            value={readMetadataEntry(entries, 'input')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'input', event.target.value))}
            placeholder="0.0012"
            inputMode="decimal"
          />
        </label>
        <label className="form-group">
          <span>Output</span>
          <input
            value={readMetadataEntry(entries, 'output')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'output', event.target.value))}
            placeholder="0.0012"
            inputMode="decimal"
          />
        </label>
        <label className="form-group">
          <span>Cache Read</span>
          <input
            value={readMetadataEntry(entries, 'cacheRead')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'cacheRead', event.target.value))}
            placeholder="0.0003"
            inputMode="decimal"
          />
        </label>
        <label className="form-group">
          <span>Cache Write</span>
          <input
            value={readMetadataEntry(entries, 'cacheWrite')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'cacheWrite', event.target.value))}
            placeholder="0.0016"
            inputMode="decimal"
          />
        </label>
      </div>
    </StructuredSection>
  )
}

function findMetadataEntryIndex(entries: KeyValueDraft[], key: string): number {
  return entries.findIndex((entry) => entry.key.trim() === key)
}

function readMetadataEntry(entries: KeyValueDraft[], key: string): string {
  const entry = entries.find((candidate) => candidate.key.trim() === key)
  return entry?.value ?? ''
}

function readBooleanMetadataEntry(entries: KeyValueDraft[], key: string): boolean {
  return readMetadataEntry(entries, key).trim() === 'true'
}

function writeMetadataEntry(entries: KeyValueDraft[], key: string, value: string): KeyValueDraft[] {
  const index = findMetadataEntryIndex(entries, key)
  if (!value.trim()) {
    return index < 0 ? entries : entries.filter((_, entryIndex) => entryIndex !== index)
  }

  if (index < 0) {
    return [...entries, createKeyValueDraft(key, value)]
  }

  return entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, key, value } : entry))
}

function writeBooleanMetadataEntry(entries: KeyValueDraft[], key: string, checked: boolean): KeyValueDraft[] {
  const value = checked ? 'true' : 'false'
  const index = findMetadataEntryIndex(entries, key)
  if (index < 0) {
    return [...entries, createKeyValueDraft(key, value)]
  }
  return entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, key, value } : entry))
}
