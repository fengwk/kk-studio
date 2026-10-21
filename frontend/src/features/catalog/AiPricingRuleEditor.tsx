import { Plus, Trash2 } from 'lucide-react'
import type { PricingRuleDraft } from '@/features/catalog/ai-console-types'
import { PRICING_UNIT_FIELDS } from '@/features/catalog/ai-pricing-fields'
import { newPricingRuleDraft } from '@/features/catalog/ai-model-draft-codec'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { sanitizeDecimalInput } from '@/shared/lib/numeric-input'
import { useI18n } from '@/shared/i18n'

/**
 * 条件价格列表：每条规则可限定每天的时段与/或最小上下文长度，计费时取第一条满足的规则，
 * 都不满足时用基础价格。
 */
export function PricingRuleEditor({
  rules,
  onChange,
}: {
  rules: PricingRuleDraft[]
  onChange: (rules: PricingRuleDraft[]) => void
}) {
  const { t } = useI18n()

  function update(index: number, patch: Partial<PricingRuleDraft>) {
    onChange(rules.map((rule, ruleIndex) => (ruleIndex === index ? { ...rule, ...patch } : rule)))
  }

  return (
    <div className="pricing-rules">
      <div className="structured-section-head">
        <h4>{t('ai.catalog.form.pricingRules')}</h4>
        <div className="structured-section-actions">
          <Button variant="inline" onClick={() => onChange([...rules, newPricingRuleDraft()])}>
            <Plus aria-hidden="true" />
            {t('ai.catalog.form.addPricingRule')}
          </Button>
        </div>
      </div>
      <p className="inline-hint">{t('ai.catalog.form.pricingRulesHint')}</p>
      <div className="variant-stack">
        {rules.map((rule, index) => {
          const position = index + 1
          return (
            <div className="variant-editor" key={rule.key}>
              <Checkbox
                checked={rule.timeWindowEnabled}
                onChange={(checked) => update(index, { timeWindowEnabled: checked })}
                label={t('ai.catalog.form.pricingTimeWindow')}
              />
              {rule.timeWindowEnabled ? (
                <div className="editor-grid editor-grid-3">
                  <label className="form-group">
                    <FieldLabel required>{t('ai.catalog.form.pricingTimeZone')}</FieldLabel>
                    <TextInput
                      aria-label={`${t('ai.catalog.form.pricingTimeZone')} ${position}`}
                      value={rule.timeZone}
                      onChange={(event) => update(index, { timeZone: event.target.value })}
                      placeholder="Asia/Shanghai"
                    />
                  </label>
                  <label className="form-group">
                    <FieldLabel required>{t('ai.catalog.form.pricingStart')}</FieldLabel>
                    <TextInput
                      type="time"
                      aria-label={`${t('ai.catalog.form.pricingStart')} ${position}`}
                      value={rule.start}
                      onChange={(event) => update(index, { start: event.target.value })}
                    />
                  </label>
                  <label className="form-group">
                    <FieldLabel required>{t('ai.catalog.form.pricingEnd')}</FieldLabel>
                    <TextInput
                      type="time"
                      aria-label={`${t('ai.catalog.form.pricingEnd')} ${position}`}
                      value={rule.end}
                      onChange={(event) => update(index, { end: event.target.value })}
                    />
                  </label>
                </div>
              ) : null}
              <label className="form-group">
                <FieldLabel>{t('ai.catalog.form.pricingMinContext')}</FieldLabel>
                <TextInput
                  inputMode="numeric"
                  aria-label={`${t('ai.catalog.form.pricingMinContext')} ${position}`}
                  value={rule.minContextTokens}
                  onChange={(event) =>
                    update(index, { minContextTokens: event.target.value.replace(/[^0-9]/g, '') })
                  }
                  placeholder="200000"
                />
              </label>
              <div className="form-grid-2">
                {PRICING_UNIT_FIELDS.map(({ field, labelKey }) => {
                  const label = t(labelKey)
                  return (
                    <label className="form-group" key={field}>
                      <FieldLabel required>{label}</FieldLabel>
                      <TextInput
                        type="number"
                        inputMode="decimal"
                        min={0}
                        step="any"
                        aria-label={`${t('ai.catalog.form.priceAriaLabel', { label })} ${position}`}
                        value={rule[field]}
                        onChange={(event) =>
                          update(index, { [field]: sanitizeDecimalInput(event.target.value) })
                        }
                      />
                    </label>
                  )
                })}
              </div>
              <div className="variant-editor-actions">
                <Button
                  variant="inline"
                  danger
                  onClick={() => onChange(rules.filter((_, ruleIndex) => ruleIndex !== index))}
                >
                  <Trash2 aria-hidden="true" />
                  {t('ai.catalog.form.deletePricingRule')}
                </Button>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
