import { StructuredSection } from '@/features/ai/AiResourceFieldEditors'
import type { KeyValueDraft } from '@/features/ai/ai-console-types'
import { readMetadataEntry, writeMetadataEntry } from '@/features/ai/ai-resource-metadata-entries'

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
