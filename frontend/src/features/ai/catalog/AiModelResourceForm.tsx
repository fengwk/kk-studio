import type {
  AgentModelInputModality,
} from '@/shared/api/contracts'
import type { ModelDraft, ModelPricingDraft, VariantDraft } from '@/features/ai/catalog/ai-console-types'
import { variantOptionsFromDraft } from '@/features/ai/catalog/ai-draft-normalizers'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/features/ai/catalog/ai-number-input'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { VariantListEditor } from '@/features/ai/catalog/AiVariantListEditor'
import { FieldLabel } from '@/features/ai/shared/FieldLabel'
import { FormSelect } from '@/features/ai/shared/FormSelect'
import type { AgentProviderDTO } from '@/shared/api/contracts'

const AGENT_MODEL_INPUT_MODALITIES: AgentModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]

/** User-editable per-million-token prices; currency / tier metadata is fixed by the wire contract. */
const PRICING_UNIT_FIELDS: ReadonlyArray<{
  field: keyof ModelPricingDraft
  label: string
}> = [
  { field: 'inputPerMillionTokens', label: 'Input' },
  { field: 'outputPerMillionTokens', label: 'Output' },
  { field: 'cacheReadPerMillionTokens', label: 'Cache Read' },
  { field: 'cacheWritePerMillionTokens', label: 'Cache Write' },
  { field: 'cacheWriteLongPerMillionTokens', label: 'Long Cache Write' },
  { field: 'reasoningPerMillionTokens', label: 'Reasoning' },
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
  const variantOptions = variantOptionsFromDraft(draft.variants)
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

  /** 开启 Reasoning 时，为空的思考强度用 Variant ID 或 medium 预填。 */
  function setReasoning(enabled: boolean) {
    if (!enabled) {
      onChange({ ...draft, reasoning: false })
      return
    }
    onChange({
      ...draft,
      reasoning: true,
      variants: draft.variants.map((variant) => ({
        ...variant,
        reasoningEffort:
          variant.reasoningEffort.trim() ||
          variant.id.trim() ||
          'medium',
      })),
    })
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
      <label className={`form-group${fieldErrors.providerId ? ' is-error' : ''}`}>
        <FieldLabel required>Provider</FieldLabel>
        <FormSelect
          aria-label="Provider"
          value={draft.providerId}
          required
          disabled={mode === 'edit'}
          options={providers.map((provider) => ({ value: String(provider.id), label: provider.name }))}
          onChange={(providerId) => onChange({ ...draft, providerId })}
        />
        {fieldErrors.providerId ? <span className="field-error">{fieldErrors.providerId}</span> : null}
      </label>

      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>Name</FieldLabel>
        <input
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="MiniMax-M2.7"
          required
        />
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>

      <label className="form-group">
        <FieldLabel>Description</FieldLabel>
        <input
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder="模型说明"
        />
      </label>

      <section className="structured-section" aria-labelledby="model-limit-heading">
        <div className="structured-section-head">
          <h3 id="model-limit-heading">Limit</h3>
        </div>
        <div className="form-grid-2">
          <label className={`form-group${fieldErrors.contextWindow ? ' is-error' : ''}`}>
            <FieldLabel required>Context Window</FieldLabel>
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
            <FieldLabel required>Max Output Tokens</FieldLabel>
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
            {fieldErrors.maxOutputTokens ? (
              <span className="field-error">{fieldErrors.maxOutputTokens}</span>
            ) : null}
          </label>
        </div>
      </section>

      <section className="structured-section" aria-labelledby="model-abilities-heading">
        <div className="structured-section-head">
          <h3 id="model-abilities-heading">功能</h3>
        </div>
        <div className="ability-toggle-row">
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={draft.tools}
              onChange={(event) => onChange({ ...draft, tools: event.target.checked })}
            />
            <span>Tools</span>
          </label>
          <label className="checkbox-field">
            <input
              type="checkbox"
              checked={draft.reasoning}
              onChange={(event) => setReasoning(event.target.checked)}
            />
            <span>Reasoning</span>
          </label>
        </div>
      </section>

      <fieldset
        className={`form-group capability-picker${fieldErrors.inputModalities ? ' is-error' : ''}`}
      >
        <legend><FieldLabel required>输入类型</FieldLabel></legend>
        <p className="inline-hint">模型可接受的输入模态；至少保留一种（不能全部取消）。</p>
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
          <h3 id="model-pricing-heading">Pricing</h3>
        </div>
        <p className="inline-hint">单价为 USD / 百万 tokens。币种固定 USD。</p>
        <div className="form-grid-2">
          {PRICING_UNIT_FIELDS.map(({ field, label }) => (
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
                  aria-label={`${label} USD per million tokens`}
                />
                <span className="price-suffix" aria-hidden="true">
                  /1M
                </span>
              </div>
            </label>
          ))}
        </div>
        {fieldErrors.pricing ? <span className="field-error">{fieldErrors.pricing}</span> : null}
      </section>

      <label className={`form-group${fieldErrors.defaultVariant ? ' is-error' : ''}`}>
        <FieldLabel required>Default Variant</FieldLabel>
        <FormSelect
          aria-label="Default Variant"
          value={selectedDefaultVariant}
          options={variantOptions.map((name) => ({ value: name, label: name }))}
          onChange={(defaultVariant) => onChange({ ...draft, defaultVariant })}
        />
        {fieldErrors.defaultVariant ? (
          <span className="field-error">{fieldErrors.defaultVariant}</span>
        ) : null}
      </label>

      <div
        className={
          fieldErrors.variants || fieldErrors.reasoningEffort ? 'is-error' : undefined
        }
      >
        <VariantListEditor
          label="Variants"
          variants={draft.variants}
          defaultVariant={selectedDefaultVariant}
          reasoning={draft.reasoning}
          effortError={Boolean(fieldErrors.reasoningEffort)}
          onChange={commitVariants}
        />
        {/* 只保留一条汇总错误，避免顶部 banner + 字段旁重复堆叠 */}
        {fieldErrors.reasoningEffort || fieldErrors.variants ? (
          <span className="field-error">
            {fieldErrors.reasoningEffort || fieldErrors.variants}
          </span>
        ) : null}
      </div>
    </>
  )
}
