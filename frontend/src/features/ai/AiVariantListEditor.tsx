import { Plus, Trash2 } from 'lucide-react'
import type { VariantDraft } from '@/features/ai/ai-console-types'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/features/ai/ai-number-input'
import { blankVariant } from '@/features/ai/ai-resource-form-drafts'

export function VariantListEditor({
  label,
  variants,
  defaultVariant,
  reasoning,
  effortError = false,
  onChange,
}: {
  label: string
  variants: VariantDraft[]
  defaultVariant: string
  reasoning: boolean
  /** 开启 Reasoning 但未填思考强度时标红 */
  effortError?: boolean
  onChange: (variants: VariantDraft[], preferredDefaultVariant?: string) => void
}) {
  function updateVariant(index: number, patch: Partial<VariantDraft>) {
    const currentVariant = variants[index]
    const nextVariants = variants.map((variant, variantIndex) =>
      variantIndex === index ? { ...variant, ...patch } : variant,
    )
    const preferredDefaultVariant =
      patch.id !== undefined && currentVariant?.id.trim() === defaultVariant.trim()
        ? patch.id.trim() || undefined
        : undefined
    onChange(nextVariants, preferredDefaultVariant)
  }

  function addVariant() {
    const existing = new Set(variants.map((variant) => variant.id.trim()))
    let suffix = variants.length + 1
    let id = `variant-${suffix}`
    while (existing.has(id)) {
      suffix += 1
      id = `variant-${suffix}`
    }
    onChange([...variants, blankVariant(id, reasoning ? 'medium' : '')])
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
            添加 Variant
          </button>
        </div>
      </div>
      <p className="inline-hint">
        Variant 是命名参数预设；id 唯一，Default Variant 必须指向其中一项。
        {reasoning
          ? ' 思考强度为自由字符串（写入 reasoning_effort）。'
          : ' 若需配置思考强度，请先勾选上方 Reasoning。'}
      </p>
      <div className="variant-stack">
        {variants.map((variant, index) => (
          <div className="variant-editor" key={variant.draftId}>
            <div className={`editor-grid${reasoning ? ' editor-grid-3' : ' editor-grid-2'}`}>
              <label className="form-group">
                <span>Variant ID</span>
                <input
                  aria-label={`Variant ID ${index + 1}`}
                  value={variant.id}
                  onChange={(event) => updateVariant(index, { id: event.target.value })}
                  placeholder="medium"
                  required
                />
              </label>
              {reasoning ? (
                <label
                  className={`form-group${
                    effortError && !variant.reasoningEffort.trim() ? ' is-error' : ''
                  }`}
                >
                  <span>思考强度</span>
                  <input
                    aria-label={`Reasoning Effort ${index + 1}`}
                    value={variant.reasoningEffort}
                    onChange={(event) =>
                      updateVariant(index, { reasoningEffort: event.target.value })
                    }
                    placeholder=""
                    autoComplete="off"
                    spellCheck={false}
                  />
                  {effortError && !variant.reasoningEffort.trim() ? (
                    <span className="field-error">请填写思考强度</span>
                  ) : null}
                </label>
              ) : null}
              <label className="form-group">
                <span>Max Output</span>
                <input
                  aria-label={`Variant Max Output Tokens ${index + 1}`}
                  type="number"
                  inputMode="numeric"
                  min={1}
                  step={1}
                  value={variant.maxOutputTokens}
                  onChange={(event) =>
                    updateVariant(index, {
                      maxOutputTokens: sanitizeIntegerInput(event.target.value),
                    })
                  }
                  placeholder="空=模型上限"
                />
              </label>
            </div>

            <details className="variant-advanced-options">
              <summary>高级选项</summary>
              <p className="inline-hint">一般无需配置，留空=厂商默认。</p>
              <div className="editor-grid editor-grid-3">
                <label className="form-group">
                  <span>Temperature</span>
                  <input
                    aria-label={`Temperature ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    min={0}
                    step="any"
                    value={variant.temperature}
                    onChange={(event) =>
                      updateVariant(index, {
                        temperature: sanitizeDecimalInput(event.target.value),
                      })
                    }
                    placeholder="空"
                  />
                </label>
                <label className="form-group">
                  <span>Top P</span>
                  <input
                    aria-label={`Top P ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    min={0}
                    max={1}
                    step="any"
                    value={variant.topP}
                    onChange={(event) =>
                      updateVariant(index, { topP: sanitizeDecimalInput(event.target.value) })
                    }
                    placeholder="空"
                  />
                </label>
                <label className="form-group">
                  <span>Top K</span>
                  <input
                    aria-label={`Top K ${index + 1}`}
                    type="number"
                    inputMode="numeric"
                    min={1}
                    step={1}
                    value={variant.topK}
                    onChange={(event) =>
                      updateVariant(index, { topK: sanitizeIntegerInput(event.target.value) })
                    }
                    placeholder="空"
                  />
                </label>
                <label className="form-group">
                  <span>Freq Penalty</span>
                  <input
                    aria-label={`Frequency Penalty ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={variant.frequencyPenalty}
                    onChange={(event) =>
                      updateVariant(index, {
                        frequencyPenalty: sanitizeDecimalInput(event.target.value),
                      })
                    }
                    placeholder="空"
                  />
                </label>
                <label className="form-group">
                  <span>Pres Penalty</span>
                  <input
                    aria-label={`Presence Penalty ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={variant.presencePenalty}
                    onChange={(event) =>
                      updateVariant(index, {
                        presencePenalty: sanitizeDecimalInput(event.target.value),
                      })
                    }
                    placeholder="空"
                  />
                </label>
                <label className="form-group">
                  <span>Stop</span>
                  <input
                    aria-label={`Stop Sequences ${index + 1}`}
                    value={variant.stopSequences}
                    onChange={(event) => updateVariant(index, { stopSequences: event.target.value })}
                    placeholder="END,STOP"
                  />
                </label>
              </div>
            </details>

            <div className="variant-editor-actions">
              <button
                className="ghost-inline-btn danger"
                type="button"
                onClick={() => removeVariant(index)}
                disabled={variants.length <= 1}
              >
                <Trash2 aria-hidden="true" />
                删除 Variant
              </button>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
