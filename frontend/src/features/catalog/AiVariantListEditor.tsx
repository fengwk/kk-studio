import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { Select } from '@/shared/ui/controls/Select'
import type { SelectOption } from '@/shared/ui/controls/Select'
import { Plus, Trash2 } from 'lucide-react'
import type { VariantDraft } from '@/features/catalog/ai-console-types'
import { newVariantDraft } from '@/features/catalog/ai-resource-draft-primitives'
import type { ModelCapabilitiesDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import './catalog-form-layout.css'

const cacheStateMessage = {
  PROVIDER_MANAGED: 'ai.catalog.form.promptCacheProviderManaged',
  UNMANAGED: 'ai.catalog.form.promptCacheUnmanaged',
  UNSUPPORTED: 'ai.catalog.form.promptCacheUnsupported',
} as const

export function VariantListEditor({
  label,
  variants,
  defaultVariant,
  reasoning,
  capabilities,
  capabilitiesLoading = false,
  capabilitiesError,
  onRetryCapabilities,
  onChange,
}: {
  label: string
  variants: VariantDraft[]
  defaultVariant: string
  reasoning?: boolean
  capabilities?: ModelCapabilitiesDTO | null
  capabilitiesLoading?: boolean
  capabilitiesError?: string
  onRetryCapabilities?: () => void
  onChange: (variants: VariantDraft[], preferredDefaultVariant?: string) => void
}) {
  const { t } = useI18n()

  const resolvedCapabilities = capabilitiesLoading || capabilitiesError ? null : capabilities
  const cacheState = resolvedCapabilities?.promptCacheState
  const cacheStatus = capabilitiesLoading
    ? t('ai.catalog.form.modelCapabilitiesLoading')
    : capabilitiesError
      ? t('ai.catalog.form.modelCapabilitiesUnavailable')
      : !cacheState
        ? t('ai.catalog.form.promptCachePending')
        : cacheState === 'CONFIGURABLE' ? null : t(cacheStateMessage[cacheState])
  const defaultRetention = resolvedCapabilities?.defaultPromptCacheRetention
  const capabilityOptions = resolvedCapabilities?.promptCacheOptions ?? []
  const retentionOptions: SelectOption[] = capabilityOptions.map((option) => ({
    value: option.retention,
    label: [
      retentionLabel(option.retention),
      option.ttlSeconds == null ? '' : t('ai.catalog.form.promptCacheOptionTtl', { seconds: option.ttlSeconds }),
      option.writeMultiplier == null ? '' : t('ai.catalog.form.promptCacheOptionWriteMultiplier', { multiplier: option.writeMultiplier }),
    ].filter(Boolean).join(' · '),
  }))

  function retentionLabel(retention: string) {
    if (retention === 'NONE') return t('ai.catalog.form.promptCacheRetentionNone')
    if (retention === 'SHORT') return t('ai.catalog.form.promptCacheRetentionShort')
    if (retention === 'LONG') return t('ai.catalog.form.promptCacheRetentionLong')
    return retention
  }

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
    onChange([...variants, newVariantDraft({
      id,
      promptCacheRetention: defaultRetention ?? '',
    })])
  }

  function removeVariant(index: number) {
    onChange(variants.filter((_, variantIndex) => variantIndex !== index))
  }

  return (
    <section className="structured-section">
      <div className="structured-section-head">
        <h3>{label}</h3>
        <div className="structured-section-actions">
          <Button variant="secondary" onClick={addVariant}>
            <Plus aria-hidden="true" />
            {t('ai.catalog.form.addVariant')}
          </Button>
        </div>
      </div>
      {capabilitiesError ? (
        <div>
          <p className="field-error" role="alert">{capabilitiesError}</p>
          {onRetryCapabilities ? (
            <Button variant="secondary" onClick={onRetryCapabilities}>
              {t('ai.catalog.form.retryModelCapabilities')}
            </Button>
          ) : null}
        </div>
      ) : null}
      <div className="variant-stack">
        {variants.map((variant, index) => {
          const currentRetention = variant.promptCacheRetention
          const retentionSupported = retentionOptions.some((option) => option.value === currentRetention)
          const options: SelectOption[] = currentRetention && !retentionSupported
            ? [
                { value: currentRetention, label: `${retentionLabel(currentRetention)} · ${t('ai.catalog.form.unavailable')}`, disabled: true },
                ...retentionOptions,
              ]
            : retentionOptions

          return (
            <div className="variant-editor" key={variant.draftId}>
              <div className="editor-grid">
                <label className="form-group">
                  <FieldLabel required>{t('ai.catalog.form.variantId')}</FieldLabel>
                  <TextInput
                    aria-label={`${t('ai.catalog.form.variantId')} ${index + 1}`}
                    value={variant.id}
                    onChange={(event) => updateVariant(index, { id: event.target.value })}
                    placeholder="medium"
                    required
                  />
                </label>
                {cacheState === 'CONFIGURABLE' ? (
                  <label className="form-group">
                    <FieldLabel required>{t('ai.catalog.form.promptCacheRetention')}</FieldLabel>
                    <Select
                      aria-label={`${t('ai.catalog.form.promptCacheRetentionAria')} ${index + 1}`}
                      value={currentRetention}
                      options={options}
                      placeholder={t('ai.catalog.form.promptCacheRetentionSelect')}
                      disabled={capabilityOptions.length === 0}
                      onChange={(value) => {
                        const selected = capabilityOptions.find((option) => option.retention === value)
                        if (selected) updateVariant(index, { promptCacheRetention: selected.retention })
                      }}
                    />
                  </label>
                ) : (
                  <div className="form-group">
                    <FieldLabel>{t('ai.catalog.form.promptCacheRetention')}</FieldLabel>
                    <span className="inline-hint">{cacheStatus}</span>
                    {cacheState && currentRetention === 'LONG' && !retentionSupported && defaultRetention != null ? (
                      <>
                        <span className="field-error">{t('ai.catalog.form.promptCacheLongUnavailable')}</span>
                        <div>
                          <Button variant="secondary" onClick={() => updateVariant(index, { promptCacheRetention: defaultRetention })}>
                            {t('ai.catalog.form.usePromptCacheDefault', { retention: retentionLabel(defaultRetention) })}
                          </Button>
                        </div>
                      </>
                    ) : null}
                  </div>
                )}
                {reasoning && <div className="form-group">
                  <FieldLabel htmlFor={`reasoning-effort-${variant.draftId}`} help={t('ai.catalog.form.reasoningEffortHelp')}>{t('ai.catalog.form.reasoningEffort')}</FieldLabel>
                  <TextInput
                    id={`reasoning-effort-${variant.draftId}`}
                    aria-label={`${t('ai.catalog.form.reasoningEffort')} ${index + 1}`}
                    value={variant.reasoningEffort}
                    maxLength={64}
                    onChange={(event) =>
                      updateVariant(index, { reasoningEffort: event.target.value })
                    }
                    placeholder={t('ai.catalog.form.reasoningEffortPlaceholder')}
                  />
                </div>}
              </div>

              <div className="form-group">
                <FieldLabel htmlFor={`protocol-options-${variant.draftId}`} help={t('ai.catalog.form.protocolOptionsHint')}>
                  {t('ai.catalog.form.protocolOptions')}
                </FieldLabel>
                <TextArea
                  id={`protocol-options-${variant.draftId}`}
                  className="code-textarea"
                  aria-label={`${t('ai.catalog.form.protocolOptions')} ${index + 1}`}
                  value={variant.protocolOptionsJson}
                  onChange={(event) => updateVariant(index, { protocolOptionsJson: event.target.value })}
                  placeholder={t('ai.catalog.form.protocolOptionsPlaceholder')}
                  rows={3}
                  spellCheck={false}
                />
              </div>

              <div>
                <Button
                  variant="secondary"
                  danger
                  onClick={() => removeVariant(index)}
                  disabled={variants.length <= 1}
                >
                  <Trash2 aria-hidden="true" />
                  {t('ai.catalog.form.deleteVariant')}
                </Button>
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}
