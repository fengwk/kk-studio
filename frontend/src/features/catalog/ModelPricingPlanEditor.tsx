import { useEffect, useMemo, useRef, useState } from 'react'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import { formatCatalogSupportIssue } from '@/features/catalog/catalog-utils'
import { previewModelPricing } from '@/features/catalog/ai-model-pricing-preview'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import type { ModelPricingRuleDTO, ModelRateSetDTO } from '@/shared/api/generated/contracts'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Select } from '@/shared/ui/controls/Select'
import {
  clonePricingDefinitionDraft,
  createPricingDraftKey,
  findPricingConflicts,
  type ModelPricingDraft,
  type InputRangeDraft,
  type PricingDefinitionDraft,
  type PricingFactorsDraft,
  type PricingRateField,
  splitCrossMidnightPeriod,
  normalizePricingDraft,
} from '@/features/catalog/model-pricing-draft'
import { sanitizeDecimalInput } from '@/shared/lib/numeric-input'
import './ai-pricing.css'

type BaseRateField = Exclude<PricingRateField, 'reasoning'>
const RATE_FIELDS: ReadonlyArray<[BaseRateField, TranslationKey]> = [
  ['input', 'ai.catalog.form.inputPrice'],
  ['output', 'ai.catalog.form.outputPrice'],
  ['cacheRead', 'ai.catalog.form.cacheReadPrice'],
  ['cacheWrite', 'ai.catalog.form.cacheWritePrice'],
]
const RANGE_RATE_FIELDS: ReadonlyArray<[PricingRateField, TranslationKey]> = [
  ...RATE_FIELDS,
  ['reasoning', 'ai.catalog.form.reasoningPrice'],
]
const FACTOR_FIELDS: ReadonlyArray<[keyof Omit<PricingFactorsDraft, 'factor'>, TranslationKey]> = [
  ['inputFactor', 'ai.catalog.form.inputPrice'],
  ['outputFactor', 'ai.catalog.form.outputPrice'],
  ['cacheReadFactor', 'ai.catalog.form.cacheReadPrice'],
  ['cacheWriteFactor', 'ai.catalog.form.cacheWritePrice'],
  ['reasoningFactor', 'ai.catalog.form.reasoningPrice'],
]
const WEEKDAYS: ReadonlyArray<[number, TranslationKey]> = [
  [1, 'ai.catalog.form.weekday.1'], [2, 'ai.catalog.form.weekday.2'],
  [3, 'ai.catalog.form.weekday.3'], [4, 'ai.catalog.form.weekday.4'],
  [5, 'ai.catalog.form.weekday.5'], [6, 'ai.catalog.form.weekday.6'],
  [7, 'ai.catalog.form.weekday.7'],
]
function parseTtlMode(value: string): InputRangeDraft['cacheWriteTtlMode'] {
  if (value === 'inherit' || value === 'custom' || value === 'unpartitioned') return value
  throw new Error(`Unsupported TTL pricing mode: ${value}`)
}

function catalogRate(value: string | null | undefined): string {
  return value == null ? '—' : `$${value}`
}

function catalogRateSummary(rates: ModelRateSetDTO, t: ReturnType<typeof useI18n>['t']): string {
  return t('ai.catalog.form.pricingCatalogRates', {
    input: catalogRate(rates.input),
    output: catalogRate(rates.output),
    cacheRead: catalogRate(rates.cacheRead),
    cacheWrite: catalogRate(rates.cacheWrite),
  })
}

function catalogRuleSummary(rule: ModelPricingRuleDTO, t: ReturnType<typeof useI18n>['t']): string {
  if (rule.type === 'input_token_range') {
    return t('ai.catalog.form.pricingCatalogRange', {
      min: rule.minInputTokens ?? 0,
      max: rule.maxInputTokensExclusive ?? '∞',
      input: catalogRate(rule.input),
      output: catalogRate(rule.output),
      cacheRead: catalogRate(rule.cacheRead),
      cacheWrite: catalogRate(rule.cacheWrite),
    })
  }
  const factorChanges = [
    ['inputFactor', 'ai.catalog.form.inputPrice'],
    ['outputFactor', 'ai.catalog.form.outputPrice'],
    ['cacheReadFactor', 'ai.catalog.form.cacheReadPrice'],
    ['cacheWriteFactor', 'ai.catalog.form.cacheWritePrice'],
    ['reasoningFactor', 'ai.catalog.form.reasoningPrice'],
  ] as const
  const extras = factorChanges
    .filter(([field]) => rule[field] != null && rule[field] !== rule.factor)
    .map(([field, label]) => `${t(label)} ×${rule[field]}`)
    .join(' · ')
  if (rule.type === 'service_tier_multiplier') {
    return `${t('ai.catalog.form.pricingCatalogTier', {
      tier: rule.serviceTier ?? '', factor: rule.factor ?? '1',
    })}${extras ? ` · ${extras}` : ''}`
  }
  if (rule.type === 'time_multiplier') {
    const days = rule.weekdays?.length
      ? rule.weekdays.map((day) => weekdayLabel(day, t)).join(', ')
      : t('ai.catalog.form.pricingEveryDay')
    return `${t('ai.catalog.form.pricingCatalogTime', {
      days, start: rule.start ?? '', end: rule.end ?? '', timezone: rule.timezone ?? '', factor: rule.factor ?? '1',
    })}${extras ? ` · ${extras}` : ''}`
  }
  return t('ai.catalog.form.pricingUnpriced')
}

function weekdayLabel(day: number, t: ReturnType<typeof useI18n>['t']): string {
  const key = WEEKDAYS.find(([value]) => value === day)?.[1]
  return key ? t(key) : String(day)
}

function timeMinutes(value: string): number | null {
  const match = /^(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const hour = Number(match[1])
  const minute = Number(match[2])
  if (minute > 59 || hour > 24 || (hour === 24 && minute !== 0)) return null
  return hour * 60 + minute
}

const blankFactors = (): PricingFactorsDraft => ({
  factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '',
})

/** 固定三组定价编辑器；本地 draftKey 永不作为定价契约字段发送。 */
export function ModelPricingPlanEditor({
  value,
  onChange,
  pricingImportNotice = null,
  includeChanges = true,
  includePreview = true,
  headingId = 'model-pricing-heading',
}: {
  value: ModelPricingDraft
  onChange: (value: ModelPricingDraft) => void
  pricingImportNotice?: ModelDraft['pricingImportNotice']
  includeChanges?: boolean
  includePreview?: boolean
  headingId?: string
}) {
  const { t } = useI18n()
  const conflicts = useMemo(() => findPricingConflicts(value.definition), [value.definition])
  const conflictKeys = new Set(conflicts.flatMap((conflict) => conflict.keys))
  const [inputTokens, setInputTokens] = useState('1000')
  const [requestedAt, setRequestedAt] = useState('')
  const [serviceTier, setServiceTier] = useState('')
  const [previewBusy, setPreviewBusy] = useState(false)
  const [previewResult, setPreviewResult] = useState<{
    signature: string
    value: Awaited<ReturnType<typeof previewModelPricing>>
  } | null>(null)
  const [previewError, setPreviewError] = useState<{ signature: string; message: string } | null>(null)
  const previewController = useRef<AbortController | null>(null)
  const previewSignature = JSON.stringify([value, inputTokens, requestedAt, serviceTier])
  const visiblePreview = previewResult?.signature === previewSignature ? previewResult.value : null
  const visiblePreviewError = previewError?.signature === previewSignature ? previewError.message : null

  useEffect(() => () => previewController.current?.abort(), [])

  async function runPricingPreview() {
    const normalizedTokens = inputTokens.trim()
    if (!/^(?:0|[1-9]\d*)$/.test(normalizedTokens) || !Number.isSafeInteger(Number(normalizedTokens))) {
      setPreviewError({ signature: previewSignature, message: t('ai.catalog.form.pricingPreviewInvalidTokens') })
      return
    }
    const requestedAtMillis = requestedAt ? Date.parse(`${requestedAt}Z`) : undefined
    if (requestedAt && !Number.isSafeInteger(requestedAtMillis)) {
      setPreviewError({ signature: previewSignature, message: t('ai.catalog.form.pricingPreviewInvalidTime') })
      return
    }
    let pricing
    try {
      pricing = normalizePricingDraft(value)
    } catch (error) {
      setPreviewError({ signature: previewSignature, message: toUserFacingErrorMessage(error) })
      return
    }
    previewController.current?.abort()
    const controller = new AbortController()
    previewController.current = controller
    setPreviewBusy(true)
    setPreviewError(null)
    try {
      const response = await previewModelPricing({
        pricing: pricing ?? null,
        inputTokens: Number(normalizedTokens),
        ...(requestedAtMillis === undefined ? {} : { requestedAt: requestedAtMillis }),
        ...(serviceTier.trim() ? { serviceTier: serviceTier.trim() } : {}),
      }, controller.signal)
      if (!controller.signal.aborted) setPreviewResult({ signature: previewSignature, value: response })
    } catch (error) {
      if (!controller.signal.aborted) {
        setPreviewError({ signature: previewSignature, message: toUserFacingErrorMessage(error) })
      }
    } finally {
      if (!controller.signal.aborted) setPreviewBusy(false)
    }
  }

  function updateRangeReasoning(rangeKey: string, mode: string) {
    if (mode !== 'inherit' && mode !== 'follow' && mode !== 'separate') return
    updateDefinition((definition) => ({
      ...definition,
      inputTokenRanges: definition.inputTokenRanges.map((range) => {
        if (range.draftKey !== rangeKey) return range
        const rates = { ...range.rates }
        if (mode === 'inherit') delete rates.reasoning
        else rates.reasoning = mode === 'follow' ? null : ''
        return { ...range, rates }
      }),
    }))
  }

  function updateDefinition(update: (definition: PricingDefinitionDraft) => PricingDefinitionDraft) {
    onChange({ ...value, definition: update(value.definition) })
  }

  function setBase(field: PricingRateField, next: string | null | undefined) {
    updateDefinition((definition) => ({
      ...definition,
      base: { ...definition.base, [field]: next },
    }))
  }

  const duplicateChangeDates = new Set(value.changes
    .filter((change, index) => change.from && value.changes.some((other, otherIndex) => otherIndex !== index && other.from === change.from))
    .map((change) => change.draftKey))

  function splitTimeRow(period: PricingDefinitionDraft['timePeriods'][number]) {
    const startMinutes = timeMinutes(period.start)
    const endMinutes = timeMinutes(period.end)
    if (startMinutes == null || endMinutes == null || startMinutes <= endMinutes) return
    const { draftKey, ...source } = period
    const split = splitCrossMidnightPeriod(source)
    updateDefinition((d) => ({
      ...d,
      timePeriods: d.timePeriods.flatMap((item) => item.draftKey === draftKey ? split : [item]),
    }))
  }

  function changeFactor(
    group: 'serviceTiers' | 'timePeriods', key: string, field: keyof PricingFactorsDraft, next: string,
  ) {
    updateDefinition((definition) => ({
      ...definition,
      [group]: definition[group].map((row) => row.draftKey === key
        ? { ...row, factors: { ...row.factors, [field]: sanitizeDecimalInput(next) } }
        : row),
    }))
  }

  function section(title: string, children: React.ReactNode) {
    return <section className="pricing-plan-section"><h4>{title}</h4>{children}</section>
  }

  return (
    <section className="structured-section" aria-labelledby={headingId}>
      <div className="structured-section-head">
        <h3 id={headingId}>{t('ai.catalog.form.pricing')}</h3>
        <span className="inline-hint">{t('ai.catalog.form.priceSuffix')}</span>
      </div>
      <p className="inline-hint">{t('ai.catalog.form.priceHint')}</p>
      {pricingImportNotice ? (
        <div className={`pricing-import-notice is-${pricingImportNotice.kind}`} role="status">
          <p>{t(pricingImportNotice.kind === 'unsupported'
            ? 'ai.catalog.form.pricingImportUnsupported'
            : 'ai.catalog.form.pricingImportExcluded')}</p>
          {pricingImportNotice.supportIssues.length > 0 ? (
            <ul>
              {pricingImportNotice.supportIssues.map((issue) => (
                <li key={issue}>{formatCatalogSupportIssue(issue, t)}</li>
              ))}
            </ul>
          ) : pricingImportNotice.kind === 'unsupported' ? (
            <p>{t('ai.catalog.form.pricingImportNoData')}</p>
          ) : null}
          <details>
            <summary>{t('ai.catalog.form.viewCatalogPrice')}</summary>
            {pricingImportNotice.pricing ? (
              <>
                <p>{catalogRateSummary(pricingImportNotice.pricing.base, t)}</p>
                {pricingImportNotice.pricing.base.reasoning != null ? (
                  <p>{t('ai.catalog.form.reasoningPrice')}: {pricingImportNotice.pricing.base.reasoning ?? t('ai.catalog.form.reasoningFollowOutput')}</p>
                ) : null}
                {Object.entries(pricingImportNotice.pricing.base.cacheWriteByTtl).map(([seconds, rate]) => (
                  <p key={seconds}>{seconds} s: {catalogRate(rate)}</p>
                ))}
                <ul>
                  {pricingImportNotice.pricing.rules.map((rule, index) => (
                    <li key={`${rule.type}-${index}`}>{catalogRuleSummary(rule, t)}</li>
                  ))}
                </ul>
              </>
            ) : <p>{t('ai.catalog.form.pricingImportNoData')}</p>}
          </details>
        </div>
      ) : null}

      {section(t('ai.catalog.form.pricingBase'), (
        <>
          <div className="form-grid-2">
            {RATE_FIELDS.map(([field, labelKey]) => (
              <label className="form-group" key={field}>
                <span>{t(labelKey)}</span>
                <div className="price-input">
                  <span className="price-affix" aria-hidden="true">$</span>
                  <TextInput inputMode="decimal" value={value.definition.base[field]}
                    placeholder={t('ai.catalog.form.pricingUnpriced')}
                    aria-label={t('ai.catalog.form.priceAriaLabel', { label: t(labelKey) })}
                    onChange={(event) => setBase(field, sanitizeDecimalInput(event.target.value))} />
                </div>
              </label>
            ))}
          </div>
          <Select aria-label={t('ai.catalog.form.reasoningPricing')}
            value={value.definition.base.reasoning === null ? 'follow' : value.definition.base.reasoning === undefined ? 'unknown' : 'separate'}
            options={[
              { value: 'unknown', label: t('ai.catalog.form.reasoningUnknown') },
              { value: 'follow', label: t('ai.catalog.form.reasoningFollowOutput') },
              { value: 'separate', label: t('ai.catalog.form.reasoningSeparate') },
            ]}
            onChange={(mode) => setBase('reasoning', mode === 'follow' ? null : mode === 'unknown' ? undefined : '')} />
          {value.definition.base.reasoning !== null && value.definition.base.reasoning !== undefined ? (
            <label className="form-group">
              <span>{t('ai.catalog.form.reasoningPrice')}</span>
              <TextInput inputMode="decimal" aria-label={t('ai.catalog.form.reasoningPrice')}
                value={value.definition.base.reasoning}
                placeholder={t('ai.catalog.form.pricingUnpriced')}
                onChange={(event) => setBase('reasoning', sanitizeDecimalInput(event.target.value))} />
            </label>
          ) : null}
          {Object.entries(value.definition.base.cacheWriteByTtl).map(([seconds, rate]) => (
            <div className="pricing-plan-row" key={seconds}>
              <TextInput inputMode="numeric" aria-label={t('ai.catalog.form.pricingTtlSeconds')} value={seconds}
                onChange={(event) => updateDefinition((d) => {
                  const ttl = { ...d.base.cacheWriteByTtl }
                  delete ttl[seconds]
                  const nextSeconds = event.target.value.replace(/\D/g, '') || '0'
                  if (Object.hasOwn(ttl, nextSeconds)) return d
                  ttl[nextSeconds] = rate
                  return { ...d, base: { ...d.base, cacheWriteByTtl: ttl } }
                })} />
              <TextInput inputMode="decimal" aria-label={t('ai.catalog.form.cacheWritePrice')} value={rate}
                placeholder={t('ai.catalog.form.pricingUnpriced')}
                onChange={(event) => updateDefinition((d) => ({ ...d, base: { ...d.base, cacheWriteByTtl: { ...d.base.cacheWriteByTtl, [seconds]: sanitizeDecimalInput(event.target.value) } } }))} />
              <button type="button" className="ghost-inline-btn" aria-label={t('ai.catalog.form.removePricingRule')}
                onClick={() => updateDefinition((d) => { const ttl = { ...d.base.cacheWriteByTtl }; delete ttl[seconds]; return { ...d, base: { ...d.base, cacheWriteByTtl: ttl } } })}>×</button>
            </div>
          ))}
          <button type="button" className="ghost-inline-btn" onClick={() => updateDefinition((d) => {
            let seconds = 300
            while (Object.hasOwn(d.base.cacheWriteByTtl, String(seconds))) seconds += 1
            return { ...d, base: { ...d.base, cacheWriteByTtl: { ...d.base.cacheWriteByTtl, [seconds]: '' } } }
          })}>{t('ai.catalog.form.addPricingTtl')}</button>
        </>
      ))}

      {section(t('ai.catalog.form.pricingRanges'), (
        <>
          {value.definition.inputTokenRanges.map((range) => (
            <div className="pricing-plan-row" key={range.draftKey} data-conflict={conflictKeys.has(range.draftKey) || undefined}>
              <TextInput aria-label={t('ai.catalog.form.pricingMinTokens')} inputMode="numeric" value={range.minInputTokens}
                onChange={(event) => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => r.draftKey === range.draftKey ? { ...r, minInputTokens: event.target.value.replace(/\D/g, '') } : r) }))} />
              <TextInput aria-label={t('ai.catalog.form.pricingMaxTokens')} inputMode="numeric" value={range.maxInputTokensExclusive}
                onChange={(event) => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => r.draftKey === range.draftKey ? { ...r, maxInputTokensExclusive: event.target.value.replace(/\D/g, '') } : r) }))} />
              <Select aria-label={t('ai.catalog.form.pricingTtlMode')} value={range.cacheWriteTtlMode}
                options={[
                  { value: 'inherit', label: t('ai.catalog.form.pricingTtlInherit') },
                  { value: 'custom', label: t('ai.catalog.form.pricingTtlCustom') },
                  { value: 'unpartitioned', label: t('ai.catalog.form.pricingTtlUnpartitioned') },
                ]}
                onChange={(mode) => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => r.draftKey === range.draftKey
                  ? { ...r, cacheWriteTtlMode: parseTtlMode(mode) } : r) }))} />
              {range.cacheWriteTtlMode === 'custom' ? Object.entries(range.cacheWriteByTtl).map(([seconds, rate]) => (
                <div className="pricing-plan-row" key={seconds}>
                  <TextInput inputMode="numeric" aria-label={t('ai.catalog.form.pricingTtlSeconds')} value={seconds}
                    onChange={(event) => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => {
                      if (r.draftKey !== range.draftKey) return r
                      const ttl = { ...r.cacheWriteByTtl }; delete ttl[seconds]
                      const nextSeconds = event.target.value.replace(/\D/g, '') || '0'
                      if (Object.hasOwn(ttl, nextSeconds)) return r
                      ttl[nextSeconds] = rate
                      return { ...r, cacheWriteByTtl: ttl }
                    }) }))} />
                  <TextInput inputMode="decimal" aria-label={t('ai.catalog.form.cacheWritePrice')} value={rate}
                    placeholder={t('ai.catalog.form.pricingUnpriced')}
                    onChange={(event) => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => r.draftKey === range.draftKey
                      ? { ...r, cacheWriteByTtl: { ...r.cacheWriteByTtl, [seconds]: sanitizeDecimalInput(event.target.value) } } : r) }))} />
                  <button type="button" className="ghost-inline-btn" aria-label={t('ai.catalog.form.removePricingRule')}
                    onClick={() => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => {
                      if (r.draftKey !== range.draftKey) return r
                      const ttl = { ...r.cacheWriteByTtl }; delete ttl[seconds]
                      return { ...r, cacheWriteByTtl: ttl }
                    }) }))}>×</button>
                </div>
              )) : null}
              {range.cacheWriteTtlMode === 'custom' ? <button type="button" className="ghost-inline-btn"
                onClick={() => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => {
                  if (r.draftKey !== range.draftKey) return r
                  let seconds = 300
                  while (Object.hasOwn(r.cacheWriteByTtl, String(seconds))) seconds += 1
                  return { ...r, cacheWriteByTtl: { ...r.cacheWriteByTtl, [seconds]: '' } }
                }) }))}>{t('ai.catalog.form.addPricingTtl')}</button> : null}
              {RANGE_RATE_FIELDS.map(([field, label]) => field === 'reasoning' ? (
                <div key={field} className="pricing-reasoning-patch">
                  <Select aria-label={t(label)} value={range.rates.reasoning === null ? 'follow' : range.rates.reasoning === undefined ? 'inherit' : 'separate'}
                    options={[
                      { value: 'inherit', label: t('ai.catalog.form.pricingTtlInherit') },
                      { value: 'follow', label: t('ai.catalog.form.reasoningFollowOutput') },
                      { value: 'separate', label: t('ai.catalog.form.reasoningSeparate') },
                    ]}
                    onChange={(mode) => updateRangeReasoning(range.draftKey, mode)} />
                  {range.rates.reasoning != null ? <TextInput inputMode="decimal" aria-label={t(label)}
                    placeholder={t('ai.catalog.form.pricingUnpriced')} value={range.rates.reasoning}
                    onChange={(event) => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.map((r) => r.draftKey === range.draftKey
                      ? { ...r, rates: { ...r.rates, reasoning: sanitizeDecimalInput(event.target.value) } } : r) }))} /> : null}
                </div>
              ) : (
                <TextInput key={field} inputMode="decimal" aria-label={t(label)} placeholder={t('ai.catalog.form.pricingUnpriced')}
                  value={range.rates[field] ?? ''} onChange={(event) => updateDefinition((d) => ({
                    ...d, inputTokenRanges: d.inputTokenRanges.map((r) => r.draftKey === range.draftKey
                      ? { ...r, rates: { ...r.rates, [field]: sanitizeDecimalInput(event.target.value) } } : r),
                  }))} />
              ))}
              <button type="button" className="ghost-inline-btn" aria-label={t('ai.catalog.form.removePricingRule')}
                onClick={() => updateDefinition((d) => ({ ...d, inputTokenRanges: d.inputTokenRanges.filter((r) => r.draftKey !== range.draftKey) }))}>×</button>
              {conflictKeys.has(range.draftKey) ? <span className="field-error" role="alert">{t('ai.catalog.form.pricingConflict')}</span> : null}
            </div>
          ))}
          <button type="button" className="ghost-inline-btn" onClick={() => updateDefinition((d) => ({
            ...d, inputTokenRanges: [...d.inputTokenRanges, {
              draftKey: createPricingDraftKey(), minInputTokens: '', maxInputTokensExclusive: '', rates: {},
              cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {},
            }],
          }))}>{t('ai.catalog.form.addPricingRange')}</button>
        </>
      ))}

      {section(t('ai.catalog.form.pricingTiers'), (
        <>
          {value.definition.serviceTiers.map((tier) => (
            <div className="pricing-plan-row" key={tier.draftKey} data-conflict={conflictKeys.has(tier.draftKey) || undefined}>
              <TextInput aria-label={t('ai.catalog.form.pricingTierName')} value={tier.serviceTier}
                onChange={(event) => updateDefinition((d) => ({ ...d, serviceTiers: d.serviceTiers.map((r) => r.draftKey === tier.draftKey ? { ...r, serviceTier: event.target.value } : r) }))} />
              <FactorInputs factors={tier.factors} onChange={(field, next) => changeFactor('serviceTiers', tier.draftKey, field, next)} />
              <button type="button" className="ghost-inline-btn" aria-label={t('ai.catalog.form.removePricingRule')}
                onClick={() => updateDefinition((d) => ({ ...d, serviceTiers: d.serviceTiers.filter((r) => r.draftKey !== tier.draftKey) }))}>×</button>
              {conflictKeys.has(tier.draftKey) ? <span className="field-error" role="alert">{t('ai.catalog.form.pricingConflict')}</span> : null}
            </div>
          ))}
          <button type="button" className="ghost-inline-btn" onClick={() => updateDefinition((d) => ({
            ...d, serviceTiers: [...d.serviceTiers, { draftKey: createPricingDraftKey(), serviceTier: '', factors: blankFactors() }],
          }))}>{t('ai.catalog.form.addPricingTier')}</button>
        </>
      ))}

      {section(t('ai.catalog.form.pricingTimes'), (
        <>
          {value.definition.timePeriods.map((period) => (
            <div className="pricing-plan-row" key={period.draftKey} data-conflict={conflictKeys.has(period.draftKey) || undefined}>
              <div className="weekday-picker" aria-label={t('ai.catalog.form.pricingWeekdays')}>
                {WEEKDAYS.map(([day, label]) => <Checkbox key={day} checked={period.weekdays.includes(day)} label={t(label)}
                  onChange={(checked) => updateDefinition((d) => ({ ...d, timePeriods: d.timePeriods.map((r) => r.draftKey === period.draftKey
                    ? { ...r, weekdays: checked ? [...r.weekdays, day].sort() : r.weekdays.filter((item) => item !== day) } : r) }))} />)}
              </div>
              <TextInput aria-label={t('ai.catalog.form.pricingStart')} value={period.start}
                onChange={(event) => updateDefinition((d) => ({ ...d, timePeriods: d.timePeriods.map((r) => r.draftKey === period.draftKey ? { ...r, start: event.target.value } : r) }))} />
              <TextInput aria-label={t('ai.catalog.form.pricingEnd')} value={period.end}
                onChange={(event) => updateDefinition((d) => ({ ...d, timePeriods: d.timePeriods.map((r) => r.draftKey === period.draftKey ? { ...r, end: event.target.value } : r) }))}
                onBlur={() => splitTimeRow(period)} />
              <TextInput aria-label={t('ai.catalog.form.pricingTimezone')} value={period.timezone}
                onChange={(event) => updateDefinition((d) => ({ ...d, timePeriods: d.timePeriods.map((r) => ({ ...r, timezone: event.target.value })) }))} />
              <FactorInputs factors={period.factors} onChange={(field, next) => changeFactor('timePeriods', period.draftKey, field, next)} />
              <button type="button" className="ghost-inline-btn" aria-label={t('ai.catalog.form.removePricingRule')}
                onClick={() => updateDefinition((d) => ({ ...d, timePeriods: d.timePeriods.filter((r) => r.draftKey !== period.draftKey) }))}>×</button>
              {conflictKeys.has(period.draftKey) ? <span className="field-error" role="alert">{t('ai.catalog.form.pricingConflict')}</span> : null}
            </div>
          ))}
          <button type="button" className="ghost-inline-btn" onClick={() => updateDefinition((d) => ({
            ...d, timePeriods: [...d.timePeriods, {
              draftKey: createPricingDraftKey(), weekdays: [], start: '00:00', end: '24:00', timezone: 'UTC', factors: blankFactors(),
            }],
          }))}>{t('ai.catalog.form.addPricingTime')}</button>
        </>
      ))}

      {includeChanges ? section(t('ai.catalog.form.pricingChanges'), (
        <>
          {value.changes.map((change) => (
            <div className="pricing-plan-row" key={change.draftKey} data-conflict={duplicateChangeDates.has(change.draftKey) || undefined}>
              <TextInput type="date" aria-label={t('ai.catalog.form.pricingFromDate')} value={change.from}
                onChange={(event) => onChange({ ...value, changes: value.changes.map((c) => c.draftKey === change.draftKey ? { ...c, from: event.target.value } : c) })} />
              {RATE_FIELDS.map(([field, label]) => (
                <TextInput key={field} inputMode="decimal" aria-label={`${change.from} ${t(label)}`}
                  placeholder={t('ai.catalog.form.pricingUnpriced')} value={change.definition.base[field] ?? ''}
                  onChange={(event) => onChange({
                    ...value,
                    changes: value.changes.map((c) => c.draftKey === change.draftKey ? {
                      ...c,
                      definition: { ...c.definition, base: { ...c.definition.base, [field]: sanitizeDecimalInput(event.target.value) } },
                    } : c),
                  })} />
              ))}
              <button type="button" className="ghost-inline-btn" aria-label={t('ai.catalog.form.removePricingRule')}
                onClick={() => onChange({ ...value, changes: value.changes.filter((c) => c.draftKey !== change.draftKey) })}>×</button>
              {duplicateChangeDates.has(change.draftKey) ? <span className="field-error" role="alert">{t('ai.catalog.form.pricingConflict')}</span> : null}
              <details className="pricing-change-editor">
                <summary>{t('ai.catalog.form.editPricingChange')}</summary>
                <ModelPricingPlanEditor
                  value={{ definition: change.definition, changes: [] }}
                  includeChanges={false}
                  headingId={`pricing-change-${change.draftKey}`}
                  onChange={(next) => onChange({
                    ...value,
                    changes: value.changes.map((item) => item.draftKey === change.draftKey
                      ? { ...item, definition: next.definition } : item),
                  })}
                />
              </details>
            </div>
          ))}
          <button type="button" className="ghost-inline-btn" onClick={() => onChange({
            ...value, changes: [...value.changes, {
              draftKey: createPricingDraftKey(), from: '', definition: clonePricingDefinitionDraft(value.definition),
            }],
          })}>{t('ai.catalog.form.addPricingChange')}</button>
        </>
      )) : null}
      {includePreview ? (
        <section className="pricing-plan-section" aria-labelledby={`${headingId}-preview`}>
          <h4 id={`${headingId}-preview`}>{t('ai.catalog.form.pricingPreview')}</h4>
          <p className="inline-hint">{t('ai.catalog.form.pricingPreviewTimeUtcHint')}</p>
          <div className="form-grid-2">
            <label className="form-group">
              <span>{t('ai.catalog.form.pricingInputTokens')}</span>
              <TextInput inputMode="numeric" aria-label={t('ai.catalog.form.pricingInputTokens')} value={inputTokens}
                onChange={(event) => setInputTokens(event.target.value.replace(/\D/g, ''))} />
            </label>
            <label className="form-group">
              <span>{t('ai.catalog.form.pricingPreviewTime')}</span>
              <TextInput type="datetime-local" aria-label={t('ai.catalog.form.pricingPreviewTime')} value={requestedAt}
                onChange={(event) => setRequestedAt(event.target.value)} />
            </label>
            <label className="form-group">
              <span>{t('ai.catalog.form.pricingServiceTier')}</span>
              <TextInput aria-label={t('ai.catalog.form.pricingServiceTier')} value={serviceTier}
                onChange={(event) => setServiceTier(event.target.value)} />
            </label>
          </div>
          <Button variant="ghost" loading={previewBusy} onClick={() => void runPricingPreview()}>
            {t('ai.catalog.form.pricingPreviewRun')}
          </Button>
          {visiblePreviewError ? <p className="field-error" role="alert">{visiblePreviewError}</p> : null}
          {visiblePreview ? (
            <div className="pricing-preview-result" role="status">
              {visiblePreview.rates ? (
                <>
                  <strong>{t('ai.catalog.form.pricingPreviewResult')}</strong>
                  <p>{catalogRateSummary(visiblePreview.rates, t)}</p>
                  {visiblePreview.activeFrom ? <p>{t('ai.catalog.form.pricingPreviewActiveFrom', { date: visiblePreview.activeFrom })}</p> : null}
                  <p>{t('ai.catalog.form.pricingPreviewMatched')}</p>
                  {visiblePreview.matchedRules.length ? (
                    <ul>{visiblePreview.matchedRules.map((rule, index) => (
                      <li key={`${rule.type}-${index}`}>{catalogRuleSummary(rule, t)}</li>
                    ))}</ul>
                  ) : <p>{t('ai.catalog.form.pricingPreviewNoRules')}</p>}
                </>
              ) : <p>{t('ai.catalog.form.pricingPreviewUnknown')}</p>}
            </div>
          ) : null}
        </section>
      ) : null}
    </section>
  )
}

function FactorInputs({
  factors,
  onChange,
}: {
  factors: PricingFactorsDraft
  onChange: (field: keyof PricingFactorsDraft, value: string) => void
}) {
  const { t } = useI18n()
  return (
    <details className="pricing-factor-details">
      <summary>{t('ai.catalog.form.pricingFactor')}: ×{factors.factor}</summary>
      <TextInput inputMode="decimal" aria-label={t('ai.catalog.form.pricingFactor')} value={factors.factor}
        onChange={(event) => onChange('factor', event.target.value)} />
      {FACTOR_FIELDS.map(([field, label]) => (
        <TextInput key={field} inputMode="decimal" aria-label={t(label)} placeholder={factors.factor}
          value={factors[field]} onChange={(event) => onChange(field, event.target.value)} />
      ))}
    </details>
  )
}
