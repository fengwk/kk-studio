import { Plus, Trash2 } from 'lucide-react'
import type { VariantDraft } from '@/features/ai/ai-console-types'
import { blankVariant } from '@/features/ai/ai-resource-form-drafts'
import { KeyValueEditor } from '@/features/ai/AiKeyValueEditor'
import { StructuredSection } from '@/features/ai/AiStructuredSection'

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
