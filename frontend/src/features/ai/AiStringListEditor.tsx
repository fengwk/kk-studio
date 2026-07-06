import { Plus, Trash2 } from 'lucide-react'
import { StructuredSection } from '@/features/ai/AiStructuredSection'

export function StringListEditor({
  label,
  items,
  onChange,
  itemPlaceholder,
}: {
  label: string
  items: string[]
  onChange: (items: string[]) => void
  itemPlaceholder: string
}) {
  function updateItem(index: number, value: string) {
    onChange(items.map((item, itemIndex) => (itemIndex === index ? value : item)))
  }

  function addItem() {
    onChange([...items, ''])
  }

  function removeItem(index: number) {
    onChange(items.filter((_, itemIndex) => itemIndex !== index))
  }

  return (
    <StructuredSection
      label={label}
      actions={
        <button className="ghost-inline-btn" type="button" onClick={addItem}>
          <Plus aria-hidden="true" />
          添加
        </button>
      }
    >
      {items.length === 0 && <p className="inline-hint">当前为空，按需添加条目。</p>}
      <div className="editor-stack">
        {items.map((item, index) => (
          <div className="editor-row single" key={`${label}-${index}`}>
            <input
              className="editor-input"
              value={item}
              onChange={(event) => updateItem(index, event.target.value)}
              placeholder={itemPlaceholder}
              aria-label={`${label} ${index + 1}`}
            />
            <button className="icon-action-btn" type="button" aria-label={`删除 ${label} ${index + 1}`} onClick={() => removeItem(index)}>
              <Trash2 aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </StructuredSection>
  )
}
