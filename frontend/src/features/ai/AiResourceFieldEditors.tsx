import { Plus, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import type { KeyValueDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { blankVariant, createKeyValueDraft } from '@/features/ai/ai-resource-form-drafts'

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

export function VariantListEditor({
  label,
  variants,
  defaultVariant,
  onChange,
  onResetDefault,
}: {
  label: string
  variants: VariantDraft[]
  defaultVariant: string
  onChange: (variants: VariantDraft[], preferredDefaultVariant?: string) => void
  onResetDefault: () => void
}) {
  function updateVariant(index: number, patch: Partial<VariantDraft>) {
    const currentVariant = variants[index]
    const nextVariants = variants.map((variant, variantIndex) => (variantIndex === index ? { ...variant, ...patch } : variant))
    const preferredDefaultVariant =
      patch.name !== undefined && currentVariant?.name.trim() === defaultVariant.trim() ? patch.name.trim() || undefined : undefined
    onChange(nextVariants, preferredDefaultVariant)
  }

  function addVariant() {
    onChange([...variants, blankVariant()])
  }

  function removeVariant(index: number) {
    onChange(variants.filter((_, variantIndex) => variantIndex !== index))
  }

  return (
    <StructuredSection
      label={label}
      actions={
        <div className="structured-section-actions">
          <button className="ghost-inline-btn" type="button" onClick={addVariant}>
            <Plus aria-hidden="true" />
            添加 Variant
          </button>
          <button className="ghost-inline-btn" type="button" onClick={onResetDefault}>
            重置默认
          </button>
        </div>
      }
    >
      <div className="variant-stack">
        {variants.map((variant, index) => (
          <div className="variant-editor" key={variant.id}>
            <div className="editor-grid">
              <label className="form-group">
                <span>Name</span>
                <input value={variant.name} onChange={(event) => updateVariant(index, { name: event.target.value })} placeholder="default" />
              </label>
              <label className="form-group">
                <span>Temperature</span>
                <input
                  value={variant.temperature}
                  onChange={(event) => updateVariant(index, { temperature: event.target.value })}
                  placeholder="0.1"
                  inputMode="decimal"
                />
              </label>
              <label className="form-group">
                <span>Max Output Tokens</span>
                <input
                  value={variant.maxOutputTokens}
                  onChange={(event) => updateVariant(index, { maxOutputTokens: event.target.value })}
                  placeholder="256"
                  inputMode="numeric"
                />
              </label>
            </div>
            <KeyValueEditor
              label={`Variant Extras ${index + 1}`}
              entries={variant.extras}
              onChange={(extras) => updateVariant(index, { extras })}
              valuePlaceholder="value"
            />
            <button className="ghost-inline-btn danger" type="button" onClick={() => removeVariant(index)}>
              <Trash2 aria-hidden="true" />
              删除 Variant
            </button>
          </div>
        ))}
      </div>
    </StructuredSection>
  )
}

export function StructuredSection({
  label,
  actions,
  children,
}: {
  label: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="structured-section" aria-label={label}>
      <div className="structured-section-head">
        <strong>{label}</strong>
        {actions}
      </div>
      {children}
    </section>
  )
}
