import { useState, type ReactNode } from 'react'
import {
  type AgentModelConditionDTO,
  type AgentModelFactorSetDTO,
  type AgentModelPricingMultiplierDTO,
  type AgentModelPricingOverrideDTO,
  type AgentModelRatePatchDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { ModelPricingDraft } from '@/features/ai/catalog/ai-console-types'
import { describeCondition } from '@/features/ai/catalog/ai-model-pricing-codec'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/shared/lib/numeric-input'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { useI18n } from '@/shared/i18n'

const BASE_FIELDS = [
  { field: 'input', labelKey: 'ai.catalog.form.inputPrice' },
  { field: 'output', labelKey: 'ai.catalog.form.outputPrice' },
  { field: 'cacheRead', labelKey: 'ai.catalog.form.cacheReadPrice' },
  { field: 'cacheWrite', labelKey: 'ai.catalog.form.cacheWritePrice' },
] as const

const OVERRIDE_FIELDS = [
  { field: 'input', labelKey: 'ai.catalog.form.inputPrice' },
  { field: 'output', labelKey: 'ai.catalog.form.outputPrice' },
  { field: 'cacheRead', labelKey: 'ai.catalog.form.cacheReadPrice' },
  { field: 'cacheWrite', labelKey: 'ai.catalog.form.cacheWritePrice' },
  { field: 'reasoning', labelKey: 'ai.catalog.form.reasoningPrice' },
] as const

const FACTOR_FIELDS = OVERRIDE_FIELDS

let ruleSeed = 0
function nextRuleId(prefix: 'override' | 'multiplier'): string {
  ruleSeed += 1
  return `${prefix}-${ruleSeed}`
}

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
  const [showOverrides, setShowOverrides] = useState(pricing.overrides.length > 0)
  const [showMultipliers, setShowMultipliers] = useState(pricing.multipliers.length > 0)

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

  function move<T>(items: T[], index: number, delta: -1 | 1): T[] {
    const target = index + delta
    if (target < 0 || target >= items.length) {
      return items
    }
    const next = [...items]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item!)
    return next
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

      <div className="pricing-fold">
        <div className="structured-section-head">
          <button
            type="button"
            className="ghost-inline-btn"
            aria-expanded={showOverrides}
            onClick={() => setShowOverrides((current) => !current)}
          >
            {t('ai.catalog.form.priceOverrides')}
          </button>
          <Button
            variant="inline"
            onClick={() => {
              const id = nextRuleId('override')
              updateOverrides([
                ...pricing.overrides,
                { id, when: {}, rates: {} },
              ])
              setExpandedRuleId(id)
              setShowOverrides(true)
            }}
          >
            {t('ai.catalog.form.addPriceOverride')}
          </Button>
        </div>
        {showOverrides ? (
          <div className="pricing-rule-list">
            {pricing.overrides.length === 0 ? (
              <span className="inline-hint">{t('ai.catalog.form.noPriceRules')}</span>
            ) : null}
            {pricing.overrides.map((rule, index) => (
              <PricingRule
                key={rule.id}
                id={rule.id}
                summary={describeCondition(rule.when)}
                expanded={expandedRuleId === rule.id}
                onToggle={() => setExpandedRuleId(expandedRuleId === rule.id ? null : rule.id)}
                onMoveUp={index > 0 ? () => updateOverrides(move(pricing.overrides, index, -1)) : undefined}
                onMoveDown={index < pricing.overrides.length - 1
                  ? () => updateOverrides(move(pricing.overrides, index, 1))
                  : undefined}
                onDelete={() => {
                  updateOverrides(pricing.overrides.filter((item) => item.id !== rule.id))
                  if (expandedRuleId === rule.id) {
                    setExpandedRuleId(null)
                  }
                }}
              >
                <RatePatchEditor
                  rates={rule.rates}
                  onChange={(rates) => updateOverrides(pricing.overrides.map((item) =>
                    item.id === rule.id ? { ...item, rates } : item))}
                />
                <ConditionEditor
                  condition={rule.when}
                  onChange={(when) => updateOverrides(pricing.overrides.map((item) =>
                    item.id === rule.id ? { ...item, when } : item))}
                />
              </PricingRule>
            ))}
          </div>
        ) : null}
      </div>

      <div className="pricing-fold">
        <div className="structured-section-head">
          <button
            type="button"
            className="ghost-inline-btn"
            aria-expanded={showMultipliers}
            onClick={() => setShowMultipliers((current) => !current)}
          >
            {t('ai.catalog.form.priceMultipliers')}
          </button>
          <Button
            variant="inline"
            onClick={() => {
              const id = nextRuleId('multiplier')
              updateMultipliers([...pricing.multipliers, { id, when: {}, factors: {} }])
              setExpandedRuleId(id)
              setShowMultipliers(true)
            }}
          >
            {t('ai.catalog.form.addPriceMultiplier')}
          </Button>
        </div>
        {showMultipliers ? (
          <div className="pricing-rule-list">
            {pricing.multipliers.length === 0 ? (
              <span className="inline-hint">{t('ai.catalog.form.noPriceRules')}</span>
            ) : null}
            {pricing.multipliers.map((rule, index) => (
              <PricingRule
                key={rule.id}
                id={rule.id}
                summary={describeCondition(rule.when)}
                expanded={expandedRuleId === rule.id}
                onToggle={() => setExpandedRuleId(expandedRuleId === rule.id ? null : rule.id)}
                onMoveUp={index > 0
                  ? () => updateMultipliers(move(pricing.multipliers, index, -1))
                  : undefined}
                onMoveDown={index < pricing.multipliers.length - 1
                  ? () => updateMultipliers(move(pricing.multipliers, index, 1))
                  : undefined}
                onDelete={() => {
                  updateMultipliers(pricing.multipliers.filter((item) => item.id !== rule.id))
                  if (expandedRuleId === rule.id) {
                    setExpandedRuleId(null)
                  }
                }}
              >
                <FactorEditor
                  factors={rule.factors}
                  onChange={(factors) => updateMultipliers(pricing.multipliers.map((item) =>
                    item.id === rule.id ? { ...item, factors } : item))}
                />
                <ConditionEditor
                  condition={rule.when}
                  onChange={(when) => updateMultipliers(pricing.multipliers.map((item) =>
                    item.id === rule.id ? { ...item, when } : item))}
                />
              </PricingRule>
            ))}
          </div>
        ) : null}
      </div>
    </section>
  )
}

function TtlRateEditor({
  value,
  onChange,
}: {
  value: Record<string, string>
  onChange: (value: Record<string, string>) => void
}) {
  const { t } = useI18n()
  const entries = Object.entries(value)
  const nextSeconds = entries.length === 0
    ? '300'
    : String(Math.max(...entries.map(([seconds]) => Number(seconds) || 0)) + 300)

  return (
    <div className="editor-stack">
      {entries.map(([seconds, rate]) => (
        <div className="editor-row" key={seconds}>
          <TextInput
            value={seconds}
            inputMode="numeric"
            aria-label={t('ai.catalog.form.ttlSeconds')}
            onChange={(event) => {
              const nextKey = sanitizeIntegerInput(event.target.value)
              if (!nextKey || nextKey === seconds) {
                if (!nextKey) {
                  return
                }
                return
              }
              const next: Record<string, string> = {}
              for (const [key, rateValue] of entries) {
                next[key === seconds ? nextKey : key] = rateValue
              }
              onChange(next)
            }}
          />
          <TextInput
            value={rate}
            inputMode="decimal"
            aria-label={t('ai.catalog.form.ttlRate')}
            onChange={(event) =>
              onChange({ ...value, [seconds]: sanitizeDecimalInput(event.target.value) })}
          />
          <Button
            variant="ghost"
            size="compact"
            danger
            aria-label={t('ai.catalog.form.removeTtl')}
            onClick={() => {
              const next = { ...value }
              delete next[seconds]
              onChange(next)
            }}
          >
            ×
          </Button>
        </div>
      ))}
      <Button
        variant="inline"
        onClick={() => onChange({ ...value, [nextSeconds]: '0' })}
      >
        {t('ai.catalog.form.addTtl')}
      </Button>
    </div>
  )
}

function PricingRule({
  id,
  summary,
  expanded,
  onToggle,
  onMoveUp,
  onMoveDown,
  onDelete,
  children,
}: {
  id: string
  summary: string
  expanded: boolean
  onToggle: () => void
  onMoveUp?: () => void
  onMoveDown?: () => void
  onDelete: () => void
  children: ReactNode
}) {
  const { t } = useI18n()
  return (
    <div className="pricing-rule">
      <div className="pricing-rule-head">
        <button
          type="button"
          className="pricing-rule-summary"
          aria-expanded={expanded}
          onClick={onToggle}
        >
          <code>{id}</code>
          <span>{summary}</span>
        </button>
        <div className="pricing-rule-actions">
          <Button variant="ghost" size="compact" disabled={!onMoveUp}
            aria-label={t('ai.catalog.form.moveRuleUp')} onClick={onMoveUp}>↑</Button>
          <Button variant="ghost" size="compact" disabled={!onMoveDown}
            aria-label={t('ai.catalog.form.moveRuleDown')} onClick={onMoveDown}>↓</Button>
          <Button variant="ghost" size="compact" danger
            aria-label={t('ai.catalog.form.removeRule')} onClick={onDelete}>×</Button>
        </div>
      </div>
      {expanded ? <div className="pricing-rule-body">{children}</div> : null}
    </div>
  )
}

function RatePatchEditor({
  rates,
  onChange,
}: {
  rates: AgentModelRatePatchDTO
  onChange: (rates: AgentModelRatePatchDTO) => void
}) {
  const { t } = useI18n()
  const [showTtl, setShowTtl] = useState(Object.keys(rates.cacheWriteByTtl ?? {}).length > 0)
  return (
    <fieldset className="form-group">
      <legend>{t('ai.catalog.form.overrideRates')}</legend>
      <div className="form-grid-2">
        {OVERRIDE_FIELDS.map(({ field, labelKey }) => (
          <label className="form-group" key={field}>
            <FieldLabel>{t(labelKey)}</FieldLabel>
            <TextInput
              inputMode="decimal"
              value={rates[field] ?? ''}
              placeholder={t('ai.catalog.form.inheritPlaceholder')}
              onChange={(event) => {
                const next = sanitizeDecimalInput(event.target.value)
                onChange({ ...rates, [field]: next === '' ? undefined : next })
              }}
              aria-label={t('ai.catalog.form.overrideField', { label: t(labelKey) })}
            />
          </label>
        ))}
      </div>
      <div className="pricing-fold">
        <button type="button" className="ghost-inline-btn" aria-expanded={showTtl}
          onClick={() => setShowTtl((current) => !current)}>
          {t('ai.catalog.form.cacheWriteTtl')}
        </button>
        {showTtl ? (
          <TtlRateEditor
            value={rates.cacheWriteByTtl ?? {}}
            onChange={(ttl) => onChange({
              ...rates,
              cacheWriteByTtl: Object.keys(ttl).length > 0 ? ttl : undefined,
            })}
          />
        ) : null}
      </div>
    </fieldset>
  )
}

function FactorEditor({
  factors,
  onChange,
}: {
  factors: AgentModelFactorSetDTO
  onChange: (factors: AgentModelFactorSetDTO) => void
}) {
  const { t } = useI18n()
  return (
    <fieldset className="form-group">
      <legend>{t('ai.catalog.form.multiplierFactors')}</legend>
      <div className="form-grid-2">
        {FACTOR_FIELDS.map(({ field, labelKey }) => (
          <label className="form-group" key={field}>
            <FieldLabel>{t(labelKey)}</FieldLabel>
            <TextInput
              inputMode="decimal"
              value={factors[field] ?? ''}
              placeholder="1"
              onChange={(event) => {
                const next = sanitizeDecimalInput(event.target.value)
                onChange({ ...factors, [field]: next === '' ? undefined : next })
              }}
              aria-label={t('ai.catalog.form.multiplierField', { label: t(labelKey) })}
            />
          </label>
        ))}
      </div>
    </fieldset>
  )
}

type ConditionKey = 'context' | 'serviceTier' | 'date' | 'time' | 'all' | 'any' | 'not'

/**
 * 结构化条件编辑器：每个维度原位展开；空条件合法，all/any/not 递归编辑但深度受限。
 */
export function ConditionEditor({
  condition,
  onChange,
  depth = 0,
}: {
  condition: AgentModelConditionDTO
  onChange: (condition: AgentModelConditionDTO) => void
  depth?: number
}) {
  const { t } = useI18n()
  const available = (['context', 'serviceTier', 'date', 'time', 'all', 'any', 'not'] as ConditionKey[])
    .filter((key) => condition[key] === undefined)

  function remove(key: ConditionKey) {
    const next = { ...condition }
    delete next[key]
    onChange(next)
  }

  function add(key: string) {
    if (!key) return
    if (key === 'all' || key === 'any') {
      onChange({ ...condition, [key]: [{}] })
    } else if (key === 'not') {
      onChange({ ...condition, not: {} })
    } else {
      onChange({ ...condition, [key]: {} })
    }
  }

  return (
    <fieldset className="form-group condition-editor">
      <legend>{t('ai.catalog.form.condition')}</legend>
      <div className="condition-dimensions">
        {condition.context ? (
          <ConditionBlock label={t('ai.catalog.form.conditionContext')} onRemove={() => remove('context')}>
            <div className="form-grid-2">
              {(['gt', 'gte', 'lt', 'lte'] as const).map((field) => (
                <label className="form-group" key={field}>
                  <FieldLabel>{t(`ai.catalog.form.context.${field}`)}</FieldLabel>
                  <NumberInput
                    value={condition.context?.[field] == null ? '' : String(condition.context[field])}
                    min={0}
                    onChange={(value) => onChange({
                      ...condition,
                      context: {
                        ...condition.context,
                        [field]: value === '' ? undefined : Number(value),
                      },
                    })}
                    aria-label={t(`ai.catalog.form.context.${field}`)}
                  />
                </label>
              ))}
            </div>
          </ConditionBlock>
        ) : null}

        {condition.serviceTier ? (
          <ConditionBlock label={t('ai.catalog.form.conditionServiceTier')} onRemove={() => remove('serviceTier')}>
            <TextInput
              value={condition.serviceTier.join(', ')}
              placeholder="standard, priority"
              aria-label={t('ai.catalog.form.conditionServiceTier')}
              onChange={(event) => onChange({
                ...condition,
                serviceTier: event.target.value.split(',').map((value) => value.trim()).filter(Boolean),
              })}
            />
          </ConditionBlock>
        ) : null}

        {condition.date ? (
          <ConditionBlock label={t('ai.catalog.form.conditionDate')} onRemove={() => remove('date')}>
            <div className="form-grid-2">
              <label className="form-group">
                <FieldLabel>{t('ai.catalog.form.dateFrom')}</FieldLabel>
                <TextInput
                  type="date"
                  value={condition.date.gte ?? ''}
                  aria-label={t('ai.catalog.form.dateFrom')}
                  onChange={(event) => onChange({
                    ...condition,
                    date: { ...condition.date, gte: event.target.value || undefined },
                  })}
                />
              </label>
              <label className="form-group">
                <FieldLabel>{t('ai.catalog.form.dateBefore')}</FieldLabel>
                <TextInput
                  type="date"
                  value={condition.date.lt ?? ''}
                  aria-label={t('ai.catalog.form.dateBefore')}
                  onChange={(event) => onChange({
                    ...condition,
                    date: { ...condition.date, lt: event.target.value || undefined },
                  })}
                />
              </label>
            </div>
          </ConditionBlock>
        ) : null}

        {condition.time ? (
          <ConditionBlock label={t('ai.catalog.form.conditionTime')} onRemove={() => remove('time')}>
            <div className="form-grid-2">
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.timeStart')}</FieldLabel>
                <TextInput
                  type="time"
                  value={condition.time.start}
                  aria-label={t('ai.catalog.form.timeStart')}
                  onChange={(event) => onChange({
                    ...condition,
                    time: { ...condition.time!, start: event.target.value },
                  })}
                />
              </label>
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.timeEnd')}</FieldLabel>
                <TextInput
                  type="time"
                  value={condition.time.end}
                  aria-label={t('ai.catalog.form.timeEnd')}
                  onChange={(event) => onChange({
                    ...condition,
                    time: { ...condition.time!, end: event.target.value },
                  })}
                />
              </label>
              <label className="form-group">
                <FieldLabel required>{t('ai.catalog.form.timeZone')}</FieldLabel>
                <TextInput
                  value={condition.time.timezone}
                  placeholder="Asia/Shanghai"
                  aria-label={t('ai.catalog.form.timeZone')}
                  onChange={(event) => onChange({
                    ...condition,
                    time: { ...condition.time!, timezone: event.target.value },
                  })}
                />
              </label>
            </div>
            <div className="weekday-row">
              {[1, 2, 3, 4, 5, 6, 7].map((day) => (
                <Checkbox
                  key={day}
                  checked={condition.time!.weekdays?.includes(day) ?? false}
                  onChange={(checked) => {
                    const current = condition.time!.weekdays ?? []
                    const next = checked
                      ? [...current, day].sort((left, right) => left - right)
                      : current.filter((value) => value !== day)
                    onChange({
                      ...condition,
                      time: {
                        ...condition.time!,
                        weekdays: next.length > 0 ? next : undefined,
                      },
                    })
                  }}
                  label={t(`ai.catalog.form.weekday.${day}`)}
                />
              ))}
            </div>
          </ConditionBlock>
        ) : null}

        {(['all', 'any'] as const).map((key) => condition[key] ? (
          <ConditionBlock
            key={key}
            label={t(`ai.catalog.form.condition.${key}`)}
            onRemove={() => remove(key)}
          >
            <NestedConditions
              keyName={key}
              conditions={condition[key]!}
              onChange={(children) => onChange({ ...condition, [key]: children })}
              depth={depth}
            />
          </ConditionBlock>
        ) : null)}

        {condition.not ? (
          <ConditionBlock label={t('ai.catalog.form.condition.not')} onRemove={() => remove('not')}>
            <ConditionEditor
              condition={condition.not}
              depth={depth + 1}
              onChange={(not) => onChange({ ...condition, not })}
            />
          </ConditionBlock>
        ) : null}
      </div>
      {available.length > 0 ? (
        <div className="condition-add">
          <Select
            value=""
            aria-label={t('ai.catalog.form.addCondition')}
            placeholder={t('ai.catalog.form.addCondition')}
            options={available.map((key) => ({
              value: key,
              label: t(`ai.catalog.form.condition.${key}`),
            }))}
            onChange={add}
          />
        </div>
      ) : null}
    </fieldset>
  )
}

function NestedConditions({
  keyName,
  conditions,
  onChange,
  depth,
}: {
  keyName: 'all' | 'any'
  conditions: AgentModelConditionDTO[]
  onChange: (conditions: AgentModelConditionDTO[]) => void
  depth: number
}) {
  const { t } = useI18n()
  return (
    <div className="pricing-nested">
      {conditions.map((child, index) => (
        <div key={index} className="pricing-nested-item">
          <Button
            variant="ghost"
            size="compact"
            danger
            aria-label={t('ai.catalog.form.removeNestedCondition')}
            onClick={() => onChange(conditions.filter((_, itemIndex) => itemIndex !== index))}
          >
            ×
          </Button>
          <ConditionEditor
            condition={child}
            depth={depth + 1}
            onChange={(next) =>
              onChange(conditions.map((item, itemIndex) => itemIndex === index ? next : item))}
          />
        </div>
      ))}
      <Button variant="inline" onClick={() => onChange([...conditions, {}])}>
        {t(`ai.catalog.form.add.${keyName}`)}
      </Button>
    </div>
  )
}

function ConditionBlock({
  label,
  onRemove,
  children,
}: {
  label: string
  onRemove: () => void
  children: ReactNode
}) {
  const { t } = useI18n()
  return (
    <div className="condition-block">
      <div className="condition-block-head">
        <strong>{label}</strong>
        <Button
          variant="ghost"
          size="compact"
          danger
          aria-label={t('ai.catalog.form.removeCondition', { label })}
          onClick={onRemove}
        >
          ×
        </Button>
      </div>
      {children}
    </div>
  )
}
