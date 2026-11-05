import { useState } from 'react'
import {
  type AgentModelPricingMultiplierDTO,
  type AgentModelPricingOverrideDTO,
} from '@/shared/api/generated/contracts'
import type { ModelPricingDraft } from '@/features/catalog/ai-console-types'
import { ConditionEditor } from '@/features/catalog/AiPricingConditionEditor'
import { PricingRuleList } from '@/features/catalog/AiPricingRuleList'
import { RateFieldsEditor, TtlRateEditor } from '@/features/catalog/AiPricingRateFields'
import { sanitizeDecimalInput } from '@/shared/lib/numeric-input'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'
import './catalog-form-layout.css'
import './ai-pricing.css'

const BASE_FIELDS = [
  { field: 'input', labelKey: 'ai.catalog.form.inputPrice' },
  { field: 'output', labelKey: 'ai.catalog.form.outputPrice' },
  { field: 'cacheRead', labelKey: 'ai.catalog.form.cacheReadPrice' },
  { field: 'cacheWrite', labelKey: 'ai.catalog.form.cacheWritePrice' },
] as const

/** 单 Model 的新价格表单：完整定义原位编辑，规则折叠，无 raw JSON 主入口。 */
export function ModelPricingEditor({
  pricing,
  onChange,
  invalid = false,
}: {
  pricing: ModelPricingDraft
  onChange: (pricing: ModelPricingDraft) => void
  invalid?: boolean
}) {
  const { t } = useI18n()
  const [expandedRuleId, setExpandedRuleId] = useState<string | null>(null)
  const [showTtl, setShowTtl] = useState(Object.keys(pricing.base.cacheWriteByTtl).length > 0)

  function updateBase(field: 'input' | 'output' | 'cacheRead' | 'cacheWrite' | 'reasoning', value: string | null) {
    onChange({
      ...pricing,
      base: { ...pricing.base, [field]: value },
    })
  }

  function updateOverrides(overrides: AgentModelPricingOverrideDTO[]) {
    onChange({ ...pricing, overrides })
  }

  function updateMultipliers(multipliers: AgentModelPricingMultiplierDTO[]) {
    onChange({ ...pricing, multipliers })
  }

  return (
    <section
      className={`structured-section${invalid ? ' is-error' : ''}`}
      aria-labelledby="model-pricing-heading"
    >
      <div className="structured-section-head">
        <h3 id="model-pricing-heading">{t('ai.catalog.form.pricing')}</h3>
        <span className="inline-hint">{t('ai.catalog.form.priceSuffix')}</span>
      </div>
      <p className="inline-hint">{t('ai.catalog.form.priceHint')}</p>

      <div className="form-grid-2">
        {BASE_FIELDS.map(({ field, labelKey }) => (
          <label className="form-group" key={field}>
            <FieldLabel required>{t(labelKey)}</FieldLabel>
            <div className="price-input">
              <span className="price-affix" aria-hidden="true">$</span>
              <TextInput
                inputMode="decimal"
                value={pricing.base[field]}
                onChange={(event) => updateBase(field, sanitizeDecimalInput(event.target.value))}
                placeholder="0"
                required
                aria-invalid={invalid || undefined}
                aria-label={t('ai.catalog.form.priceAriaLabel', { label: t(labelKey) })}
              />
            </div>
          </label>
        ))}
      </div>

      <div className="form-group">
        <Checkbox
          checked={pricing.base.reasoning != null}
          onChange={(checked) => updateBase('reasoning', checked ? '0' : null)}
          label={t('ai.catalog.form.reasoningPricing')}
        />
        <span className="inline-hint">{t('ai.catalog.form.reasoningPricingHint')}</span>
        {pricing.base.reasoning != null ? (
          <div className="price-input">
            <span className="price-affix" aria-hidden="true">$</span>
            <TextInput
              inputMode="decimal"
              value={pricing.base.reasoning}
              onChange={(event) => updateBase('reasoning', sanitizeDecimalInput(event.target.value))}
              placeholder="0"
              aria-label={t('ai.catalog.form.reasoningPrice')}
            />
          </div>
        ) : null}
      </div>

      <div className="pricing-fold">
        <button
          type="button"
          className="ghost-inline-btn"
          aria-expanded={showTtl}
          onClick={() => setShowTtl((current) => !current)}
        >
          {t('ai.catalog.form.cacheWriteTtl')}
        </button>
        {showTtl ? (
          <TtlRateEditor
            value={pricing.base.cacheWriteByTtl}
            onChange={(cacheWriteByTtl) =>
              onChange({ ...pricing, base: { ...pricing.base, cacheWriteByTtl } })}
          />
        ) : null}
      </div>

      <PricingRuleList<AgentModelPricingOverrideDTO>
        idPrefix="override"
        title={t('ai.catalog.form.priceOverrides')}
        addLabel={t('ai.catalog.form.addPriceOverride')}
        rules={pricing.overrides}
        expandedRuleId={expandedRuleId}
        onExpandedRuleIdChange={setExpandedRuleId}
        onChange={updateOverrides}
        createRule={(id) => ({ id, when: {}, rates: {} })}
        renderRule={(rule, update) => (
          <>
            <RateFieldsEditor
              legend={t('ai.catalog.form.overrideRates')}
              value={rule.rates}
              placeholder={t('ai.catalog.form.inheritPlaceholder')}
              fieldAriaLabel={(label) => t('ai.catalog.form.overrideField', { label })}
              onFieldChange={(field, value) => update({ rates: { ...rule.rates, [field]: value } })}
            >
              <div className="pricing-fold">
                <Checkbox
                  checked={rule.rates.cacheWriteByTtl !== undefined}
                  onChange={(checked) => {
                    const next = { ...rule.rates }
                    if (checked) {
                      next.cacheWriteByTtl = {}
                    } else {
                      delete next.cacheWriteByTtl
                    }
                    update({ rates: next })
                  }}
                  label={t('ai.catalog.form.overrideTtl')}
                />
                <span className="inline-hint">{t('ai.catalog.form.overrideTtlHint')}</span>
                {rule.rates.cacheWriteByTtl != null ? (
                  <TtlRateEditor
                    value={rule.rates.cacheWriteByTtl}
                    onChange={(ttl) => update({ rates: { ...rule.rates, cacheWriteByTtl: ttl } })}
                  />
                ) : null}
              </div>
            </RateFieldsEditor>
            <ConditionEditor
              condition={rule.when}
              onChange={(when) => update({ when })}
            />
          </>
        )}
      />

      <PricingRuleList<AgentModelPricingMultiplierDTO>
        idPrefix="multiplier"
        title={t('ai.catalog.form.priceMultipliers')}
        addLabel={t('ai.catalog.form.addPriceMultiplier')}
        rules={pricing.multipliers}
        expandedRuleId={expandedRuleId}
        onExpandedRuleIdChange={setExpandedRuleId}
        onChange={updateMultipliers}
        createRule={(id) => ({ id, when: {}, factors: {} })}
        renderRule={(rule, update) => (
          <>
            <RateFieldsEditor
              legend={t('ai.catalog.form.multiplierFactors')}
              value={rule.factors}
              placeholder="1"
              fieldAriaLabel={(label) => t('ai.catalog.form.multiplierField', { label })}
              onFieldChange={(field, value) => update({ factors: { ...rule.factors, [field]: value } })}
            />
            <ConditionEditor
              condition={rule.when}
              onChange={(when) => update({ when })}
            />
          </>
        )}
      />
    </section>
  )
}
