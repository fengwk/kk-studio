import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { Plus, Trash2 } from 'lucide-react'
import type { VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/shared/lib/numeric-input'
import { blankVariant } from '@/features/ai/catalog/ai-resource-form-drafts'
import { useI18n } from '@/shared/i18n'

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
  const { t } = useI18n()

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
            {t('ai.catalog.form.addVariant')}
          </button>
        </div>
      </div>
      <p className="inline-hint">
        {t('ai.catalog.form.variantHint')}
        {reasoning
          ? t('ai.catalog.form.reasoningHint')
          : t('ai.catalog.form.reasoningDisabledHint')}
      </p>
      <div className="variant-stack">
        {variants.map((variant, index) => (
          <div className="variant-editor" key={variant.draftId}>
            <div className={`editor-grid${reasoning ? ' editor-grid-3' : ' editor-grid-2'}`}>
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.variantId')}</FieldLabel>
                <input
                  aria-label={`${t('ai.catalog.form.variantId')} ${index + 1}`}
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
                  <FieldLabel required>{t('ai.catalog.form.reasoningEffort')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.reasoningEffortAria')} ${index + 1}`}
                    value={variant.reasoningEffort}
                    onChange={(event) =>
                      updateVariant(index, { reasoningEffort: event.target.value })
                    }
                    placeholder=""
                    autoComplete="off"
                    spellCheck={false}
                  />
                  {effortError && !variant.reasoningEffort.trim() ? (
                    <span className="field-error">{t('ai.catalog.form.reasoningEffortError')}</span>
                  ) : null}
                </label>
              ) : null}
              <label className="form-group">
                <FieldLabel>{t('ai.catalog.form.maxOutput')}</FieldLabel>
                <input
                  aria-label={`${t('ai.catalog.form.maxOutputAria')} ${index + 1}`}
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
                  placeholder={t('ai.catalog.form.maxOutputPlaceholder')}
                />
              </label>
            </div>

            <details className="variant-advanced-options">
              <summary>{t('ai.catalog.form.advancedOptions')}</summary>
              <p className="inline-hint">{t('ai.catalog.form.advancedHint')}</p>
              <div className="editor-grid editor-grid-3">
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.temperature')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.temperature')} ${index + 1}`}
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
                    placeholder={t('ai.catalog.form.emptyPlaceholder')}
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.topP')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.topP')} ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    min={0}
                    max={1}
                    step="any"
                    value={variant.topP}
                    onChange={(event) =>
                      updateVariant(index, { topP: sanitizeDecimalInput(event.target.value) })
                    }
                    placeholder={t('ai.catalog.form.emptyPlaceholder')}
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.topK')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.topK')} ${index + 1}`}
                    type="number"
                    inputMode="numeric"
                    min={1}
                    step={1}
                    value={variant.topK}
                    onChange={(event) =>
                      updateVariant(index, { topK: sanitizeIntegerInput(event.target.value) })
                    }
                    placeholder={t('ai.catalog.form.emptyPlaceholder')}
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.frequencyPenalty')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.frequencyPenalty')} ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={variant.frequencyPenalty}
                    onChange={(event) =>
                      updateVariant(index, {
                        frequencyPenalty: sanitizeDecimalInput(event.target.value),
                      })
                    }
                    placeholder={t('ai.catalog.form.emptyPlaceholder')}
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.presencePenalty')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.presencePenalty')} ${index + 1}`}
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={variant.presencePenalty}
                    onChange={(event) =>
                      updateVariant(index, {
                        presencePenalty: sanitizeDecimalInput(event.target.value),
                      })
                    }
                    placeholder={t('ai.catalog.form.emptyPlaceholder')}
                  />
                </label>
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.stop')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.stopAria')} ${index + 1}`}
                    value={variant.stopSequences}
                    onChange={(event) => updateVariant(index, { stopSequences: event.target.value })}
                    placeholder={t('ai.catalog.form.stopPlaceholder')}
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
                {t('ai.catalog.form.deleteVariant')}
              </button>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
