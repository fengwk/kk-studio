import { Plus, Trash2 } from 'lucide-react'
import type { VariantDraft } from '@/features/ai/ai-console-types'
import { FormSelect } from '@/features/ai/FormSelect'
import { THINKING_LEVELS } from '@/features/ai/known-model-catalog'
import { blankVariant } from '@/features/ai/ai-resource-form-drafts'

export function VariantListEditor({
  label,
  variants,
  defaultVariant,
  onChange,
}: {
  label: string
  variants: VariantDraft[]
  defaultVariant: string
  onChange: (variants: VariantDraft[], preferredDefaultVariant?: string) => void
}) {
  function updateVariant(index: number, patch: Partial<VariantDraft>) {
    const currentVariant = variants[index]
    const nextVariants = variants.map((variant, variantIndex) => (variantIndex === index ? { ...variant, ...patch } : variant))
    const preferredDefaultVariant =
      patch.name !== undefined && currentVariant?.name.trim() === defaultVariant.trim()
        ? patch.name.trim() || undefined
        : undefined
    onChange(nextVariants, preferredDefaultVariant)
  }

  function addVariant() {
    onChange([...variants, blankVariant('medium')])
  }

  function removeVariant(index: number) {
    onChange(variants.filter((_, variantIndex) => variantIndex !== index))
  }

  return (
    <section className="structured-section">
      <div className="structured-section-head">
        <h3>{label}</h3>
        <div className="structured-section-actions">
          <button className="ghost-inline-btn" type="button" onClick={addVariant}>
            <Plus aria-hidden="true" />
            添加 Profile
          </button>
        </div>
      </div>
      <p className="inline-hint">
        Profile 对应运行时 thinking level（参考 pi）。Model 级 context / max output 在上方配置。
      </p>
      <div className="variant-stack">
        {variants.map((variant, index) => (
          <div className="variant-editor" key={variant.id}>
            <div className="editor-grid">
              <label className="form-group">
                <span>Name</span>
                <input
                  value={variant.name}
                  onChange={(event) => updateVariant(index, { name: event.target.value })}
                  placeholder="medium"
                />
              </label>
              <label className="form-group">
                <span>Thinking Level</span>
                <FormSelect
                  aria-label={`Thinking Level ${index + 1}`}
                  value={variant.thinkingLevel || 'off'}
                  options={THINKING_LEVELS.map((level) => ({ value: level, label: level }))}
                  onChange={(thinkingLevel) =>
                    updateVariant(index, {
                      thinkingLevel,
                      name: variant.name.trim() ? variant.name : thinkingLevel,
                    })
                  }
                />
              </label>
              <label className="form-group">
                <span>Temperature（可选）</span>
                <input
                  value={variant.temperature}
                  onChange={(event) => updateVariant(index, { temperature: event.target.value })}
                  placeholder="留空则不覆盖"
                  inputMode="decimal"
                />
              </label>
              <label className="form-group">
                <span>Max Output Override（可选）</span>
                <input
                  value={variant.maxOutputTokens}
                  onChange={(event) => updateVariant(index, { maxOutputTokens: event.target.value })}
                  placeholder="≤ model max"
                  inputMode="numeric"
                />
              </label>
            </div>
            <div className="variant-editor-actions">
              <button
                className="ghost-inline-btn danger"
                type="button"
                onClick={() => removeVariant(index)}
                disabled={variants.length <= 1}
              >
                <Trash2 aria-hidden="true" />
                删除
              </button>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
