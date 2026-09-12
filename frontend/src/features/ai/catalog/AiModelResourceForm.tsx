import type {
  AgentModelInputModality,
} from '@/shared/api/contracts/ai-catalog'
import type { ModelDraft, ModelPricingDraft, VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { variantOptionsFromDraft } from '@/features/ai/catalog/ai-draft-normalizers'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/shared/lib/numeric-input'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { VariantListEditor } from '@/features/ai/catalog/AiVariantListEditor'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { Select } from '@/shared/ui/console/Select'
import type { AgentProviderDTO } from '@/shared/api/contracts/ai-catalog'
import { useI18n } from '@/shared/i18n'

const AGENT_MODEL_INPUT_MODALITIES: AgentModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]

/** 用户可编辑的每百万 token 价格；币种 / 档位元数据由 wire 契约固定。 */
const PRICING_UNIT_FIELDS: ReadonlyArray<{
  field: keyof ModelPricingDraft
  labelKey: string
}> = [
  { field: 'inputPerMillionTokens', labelKey: 'ai.catalog.form.inputPrice' },
  { field: 'outputPerMillionTokens', labelKey: 'ai.catalog.form.outputPrice' },
  { field: 'cacheReadPerMillionTokens', labelKey: 'ai.catalog.form.cacheReadPrice' },
  { field: 'cacheWritePerMillionTokens', labelKey: 'ai.catalog.form.cacheWritePrice' },
  { field: 'cacheWriteLongPerMillionTokens', labelKey: 'ai.catalog.form.longCacheWritePrice' },
  { field: 'reasoningPerMillionTokens', labelKey: 'ai.catalog.form.reasoningPrice' },
]

export function ModelForm({
  draft,
  mode,
  providers,
  fieldErrors = {},
  onChange,
}: {
  draft: ModelDraft
  mode: 'create' | 'edit'
  providers: AgentProviderDTO[]
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ModelDraft) => void
}) {
  const { t } = useI18n()
  const variantOptions = variantOptionsFromDraft(draft.variants)
  const providerUnavailable =
    mode === 'edit' &&
    Boolean(draft.providerName) &&
    !providers.some((provider) => provider.name === draft.providerName)
  const providerOptions = providerUnavailable
    ? [
        {
          value: draft.providerName,
          label: `${draft.providerName} (${t('ai.catalog.form.unavailable')})`,
          disabled: true,
        },
        ...providers.map((provider) => ({ value: provider.name, label: provider.name })),
      ]
    : providers.map((provider) => ({ value: provider.name, label: provider.name }))
  const selectedDefaultVariant = variantOptions.includes(draft.defaultVariant.trim())
    ? draft.defaultVariant.trim()
    : (variantOptions[0] ?? '')

  function commitVariants(nextVariants: VariantDraft[], preferredDefaultVariant?: string) {
    const nextOptions = variantOptionsFromDraft(nextVariants)
    const preferred = preferredDefaultVariant?.trim() || draft.defaultVariant.trim()
    onChange({
      ...draft,
      variants: nextVariants,
      defaultVariant:
        preferred && nextOptions.includes(preferred) ? preferred : (nextOptions[0] ?? ''),
    })
  }

  function toggleInputModality(value: AgentModelInputModality) {
    const has = draft.inputModalities.includes(value)
    const next = has
      ? draft.inputModalities.filter((item) => item !== value)
      : [...draft.inputModalities, value]
    // 至少保留一种；禁止全部取消勾选。
    onChange({ ...draft, inputModalities: next.length > 0 ? next : ['TEXT'] })
  }

  /** Reasoning 只切换能力开关；空思考强度表示不覆盖协议默认，不自动补全。 */
  function setReasoning(enabled: boolean) {
    onChange({ ...draft, reasoning: enabled })
  }

  function updatePricing(field: keyof ModelPricingDraft, value: string) {
    onChange({
      ...draft,
      pricing: {
        ...draft.pricing,
        [field]: value,
      },
    })
  }

  return (
    <>
      <label className={`form-group${fieldErrors.providerName ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.provider')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.provider')}
          aria-describedby={providerUnavailable ? 'model-provider-identity-status' : undefined}
          value={draft.providerName}
          required
          disabled={mode === 'edit'}
          options={providerOptions}
          onChange={(providerName) => onChange({ ...draft, providerName })}
        />
        {providerUnavailable ? (
          <span id="model-provider-identity-status" className="inline-hint" role="status">
            {t('ai.catalog.form.unavailableIdentityHint')}
          </span>
        ) : null}
        {fieldErrors.providerName ? <span className="field-error">{fieldErrors.providerName}</span> : null}
      </label>

      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <input
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="MiniMax-M2.7"
          readOnly={mode === 'edit'}
          required
        />
        <span className="inline-hint">{t('ai.catalog.form.nameHint')}</span>
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>

      <label className={`form-group${fieldErrors.modelId ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.modelId')}</FieldLabel>
        <input
          aria-label={t('ai.catalog.form.modelId')}
          value={draft.modelId}
          onChange={(event) => onChange({ ...draft, modelId: event.target.value })}
          placeholder="claude-fable-5-dd-3M-xaMiniM"
          required
        />
        <span className="inline-hint">{t('ai.catalog.form.modelIdHint')}</span>
        {fieldErrors.modelId ? <span className="field-error">{fieldErrors.modelId}</span> : null}
      </label>

      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.description')}</FieldLabel>
        <input
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.modelDescriptionPlaceholder')}
        />
      </label>

      <section className="structured-section" aria-labelledby="model-limit-heading">
        <div className="structured-section-head">
          <h3 id="model-limit-heading">{t('ai.catalog.form.limit')}</h3>
        </div>
        <div className="form-grid-2">
          <label className={`form-group${fieldErrors.contextWindow ? ' is-error' : ''}`}>
            <FieldLabel required>{t('ai.catalog.form.contextWindow')}</FieldLabel>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              value={draft.contextWindow}
              onChange={(event) =>
                onChange({ ...draft, contextWindow: sanitizeIntegerInput(event.target.value) })
              }
              placeholder="128000"
              required
            />
            {fieldErrors.contextWindow ? (
              <span className="field-error">{fieldErrors.contextWindow}</span>
            ) : null}
          </label>
          <label className={`form-group${fieldErrors.maxOutputTokens ? ' is-error' : ''}`}>
            <FieldLabel required>{t('ai.catalog.form.maxOutputTokens')}</FieldLabel>
            <input
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              value={draft.maxOutputTokens}
              onChange={(event) =>
                onChange({ ...draft, maxOutputTokens: sanitizeIntegerInput(event.target.value) })
              }
              placeholder="8192"
              required
            />
            <span className="inline-hint">{t('ai.catalog.form.maxOutputLimitHint')}</span>
            {fieldErrors.maxOutputTokens ? (
              <span className="field-error">{fieldErrors.maxOutputTokens}</span>
            ) : null}
          </label>
        </div>
      </section>

      <section className="structured-section" aria-labelledby="model-abilities-heading">
        <div className="structured-section-head">
          <h3 id="model-abilities-heading">{t('ai.catalog.form.abilities')}</h3>
        </div>
        <div className="ability-toggle-row">
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={draft.tools}
              onChange={(event) => onChange({ ...draft, tools: event.target.checked })}
            />
            <span>{t('ai.catalog.form.tools')}</span>
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={draft.reasoning}
              onChange={(event) => setReasoning(event.target.checked)}
            />
            <span>{t('ai.catalog.form.reasoning')}</span>
          </label>
        </div>
      </section>

      <fieldset
        className={`form-group capability-picker${fieldErrors.inputModalities ? ' is-error' : ''}`}
      >
        <legend><FieldLabel required>{t('ai.catalog.form.inputTypes')}</FieldLabel></legend>
        <p className="inline-hint">{t('ai.catalog.form.inputModalityHint')}</p>
        <div className="capability-options">
          {AGENT_MODEL_INPUT_MODALITIES.map((item) => {
            const checked = draft.inputModalities.includes(item)
            return (
              <label key={item} className={`capability-option${checked ? ' is-selected' : ''}`}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleInputModality(item)}
                />
                <span>{item}</span>
              </label>
            )
          })}
        </div>
        {fieldErrors.inputModalities ? (
          <span className="field-error">{fieldErrors.inputModalities}</span>
        ) : null}
      </fieldset>

      <section
        className={`structured-section${fieldErrors.pricing ? ' is-error' : ''}`}
        aria-labelledby="model-pricing-heading"
      >
        <div className="structured-section-head">
          <h3 id="model-pricing-heading">{t('ai.catalog.form.pricing')}</h3>
        </div>
        <p className="inline-hint">{t('ai.catalog.form.priceHint')}</p>
        <div className="form-grid-2">
          {PRICING_UNIT_FIELDS.map(({ field, labelKey }) => {
            const label = t(labelKey)
            return (
            <label className="form-group" key={field}>
              <FieldLabel required>{label}</FieldLabel>
              <div className="price-input">
                <span className="price-affix" aria-hidden="true">
                  $
                </span>
                <input
                  type="number"
                  inputMode="decimal"
                  min={0}
                  step="any"
                  value={draft.pricing[field]}
                  onChange={(event) =>
                    updatePricing(field, sanitizeDecimalInput(event.target.value))
                  }
                  placeholder="0"
                  required
                  aria-label={t('ai.catalog.form.priceAriaLabel', { label })}
                />
                <span className="price-suffix" aria-hidden="true">
                  {t('ai.catalog.form.priceSuffix')}
                </span>
              </div>
            </label>
            )
          })}
        </div>
        {fieldErrors.pricing ? <span className="field-error">{fieldErrors.pricing}</span> : null}
      </section>

      <label className={`form-group${fieldErrors.defaultVariant ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.defaultVariant')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.defaultVariant')}
          value={selectedDefaultVariant}
          options={variantOptions.map((name) => ({ value: name, label: name }))}
          onChange={(defaultVariant) => onChange({ ...draft, defaultVariant })}
        />
        {fieldErrors.defaultVariant ? (
          <span className="field-error">{fieldErrors.defaultVariant}</span>
        ) : null}
      </label>

      <div className={fieldErrors.variants ? 'is-error' : undefined}>
        <VariantListEditor
          label={t('ai.catalog.form.variants')}
          variants={draft.variants}
          defaultVariant={selectedDefaultVariant}
          reasoning={draft.reasoning}
          onChange={commitVariants}
        />
        {/* 只保留一条汇总错误，避免顶部 banner + 字段旁重复堆叠 */}
        {fieldErrors.variants ? <span className="field-error">{fieldErrors.variants}</span> : null}
      </div>
    </>
  )
}
