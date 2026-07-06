import { StructuredSection } from '@/features/ai/AiResourceFieldEditors'
import type { KeyValueDraft } from '@/features/ai/ai-console-types'
import { readBooleanMetadataEntry, readMetadataEntry, writeBooleanMetadataEntry, writeMetadataEntry } from '@/features/ai/ai-resource-metadata-entries'

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
