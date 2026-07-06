import { StructuredSection } from '@/features/ai/AiResourceFieldEditors'
import type { KeyValueDraft } from '@/features/ai/ai-console-types'
import { readMetadataEntry, writeMetadataEntry } from '@/features/ai/ai-resource-metadata-entries'

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
