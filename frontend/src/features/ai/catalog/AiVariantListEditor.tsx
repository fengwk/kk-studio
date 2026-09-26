import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { Plus, Trash2 } from 'lucide-react'
import type { VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { blankVariant } from '@/features/ai/catalog/ai-resource-form-drafts'
import { useI18n } from '@/shared/i18n'

export function VariantListEditor({
  label,
  variants,
  defaultVariant,
  reasoning,
  onChange,
}: {
  label: string
  variants: VariantDraft[]
  defaultVariant: string
  reasoning: boolean
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
    onChange([...variants, blankVariant(id)])
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
            <div className={`editor-grid${reasoning ? ' editor-grid-2' : ''}`}>
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
                <label className="form-group">
                  <FieldLabel>{t('ai.catalog.form.reasoningEffort')}</FieldLabel>
                  <input
                    aria-label={`${t('ai.catalog.form.reasoningEffortAria')} ${index + 1}`}
                    value={variant.reasoningEffort}
                    maxLength={64}
                    onChange={(event) =>
                      updateVariant(index, { reasoningEffort: event.target.value })
                    }
                    placeholder={t('ai.catalog.form.reasoningEffortPlaceholder')}
                  />
                </label>
              ) : null}
            </div>

            <label className="form-group">
              <FieldLabel>{t('ai.catalog.form.protocolOptions')}</FieldLabel>
              <textarea
                className="code-textarea"
                aria-label={`${t('ai.catalog.form.protocolOptionsAria')} ${index + 1}`}
                value={variant.protocolOptionsJson}
                onChange={(event) =>
                  updateVariant(index, { protocolOptionsJson: event.target.value })
                }
                placeholder={t('ai.catalog.form.protocolOptionsPlaceholder')}
                rows={3}
                spellCheck={false}
              />
              <span className="inline-hint">{t('ai.catalog.form.protocolOptionsHint')}</span>
            </label>

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
