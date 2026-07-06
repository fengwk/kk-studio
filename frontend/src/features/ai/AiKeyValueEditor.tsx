import { Plus, Trash2 } from 'lucide-react'
import type { KeyValueDraft } from '@/features/ai/ai-console-types'
import { createKeyValueDraft } from '@/features/ai/ai-resource-form-drafts'
import { StructuredSection } from '@/features/ai/AiStructuredSection'

export function KeyValueEditor({
  label,
  entries,
  onChange,
  valuePlaceholder,
}: {
  label: string
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
  valuePlaceholder?: string
}) {
  function updateEntry(index: number, patch: Partial<KeyValueDraft>) {
    onChange(entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, ...patch } : entry)))
  }

  function addEntry() {
    onChange([...entries, createKeyValueDraft()])
  }

  function removeEntry(index: number) {
    onChange(entries.filter((_, entryIndex) => entryIndex !== index))
  }

  return (
    <StructuredSection
      label={label}
      actions={
        <button className="ghost-inline-btn" type="button" onClick={addEntry}>
          <Plus aria-hidden="true" />
          添加
        </button>
      }
    >
      {entries.length === 0 && <p className="inline-hint">当前没有字段，按需添加 key / value。</p>}
      <div className="editor-stack">
        {entries.map((entry, index) => (
          <div className="editor-row" key={entry.id}>
            <input
              className="editor-input"
              value={entry.key}
              onChange={(event) => updateEntry(index, { key: event.target.value })}
              placeholder="key"
              aria-label={`${label} key ${index + 1}`}
            />
            <input
              className="editor-input"
              value={entry.value}
              onChange={(event) => updateEntry(index, { value: event.target.value })}
              placeholder={valuePlaceholder || 'value'}
              aria-label={`${label} value ${index + 1}`}
            />
            <button className="icon-action-btn" type="button" aria-label={`删除 ${label} 字段 ${index + 1}`} onClick={() => removeEntry(index)}>
              <Trash2 aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </StructuredSection>
  )
}
