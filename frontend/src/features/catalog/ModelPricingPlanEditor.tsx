import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
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
import { sanitizeDecimalInput } from '@/shared/lib/numeric-input'
import { PricingTtlInputs } from './PricingTtlInputs'
import {
  clonePricingDefinitionDraft, createPricingDraftKey, findPricingConflicts, hasConfiguredPricing,
  normalizePricingDraft, splitCrossMidnightPeriod,
  type ModelPricingDraft, type PricingDefinitionDraft, type PricingFactorsDraft,
  type PricingRateField, type TimePeriodDraft,
} from './model-pricing-draft'
import './ai-pricing.css'

const RATE_FIELDS = [
  ['input', 'ai.catalog.form.inputPrice'], ['output', 'ai.catalog.form.outputPrice'],
  ['cacheRead', 'ai.catalog.form.cacheReadPrice'], ['cacheWrite', 'ai.catalog.form.cacheWritePrice'],
  ['reasoning', 'ai.catalog.form.reasoningPrice'],
] as const satisfies ReadonlyArray<[PricingRateField, TranslationKey]>
const FACTOR_FIELDS = ['inputFactor', 'outputFactor', 'cacheReadFactor', 'cacheWriteFactor', 'reasoningFactor'] as const
const DAYS = [1, 2, 3, 4, 5, 6, 7] as const
const BASE = 'base'
const blankFactors = (): PricingFactorsDraft => ({
  factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '',
})

type Translate = ReturnType<typeof useI18n>['t']
const dayLabel = (day: number, t: Translate) => DAYS.some((value) => value === day)
  ? t(`ai.catalog.form.weekday.${day as typeof DAYS[number]}`) : String(day)
const daysLabel = (days: number[], t: Translate) => days.length
  ? days.map((day) => dayLabel(day, t)).join(', ') : t('ai.catalog.form.pricingEveryDay')
const money = (value: string | null | undefined) => value == null ? '—' : `$${value}`

function rateSummary(rates: ModelRateSetDTO, t: Translate) {
  return [
    ...RATE_FIELDS.map(([field, label]) => `${t(label)} ${field === 'reasoning' && rates.reasoning == null
      ? t('ai.catalog.form.reasoningFollowOutput') : money(rates[field])}`),
    ...Object.entries(rates.cacheWriteByTtl).map(([seconds, rate]) => `${t('ai.catalog.form.cacheWritePrice')} (${seconds} s) ${money(rate)}`),
  ].join(' · ')
}

function ruleSummary(rule: ModelPricingRuleDTO, t: Translate) {
  if (rule.type === 'input_token_range') {
    const rates = RATE_FIELDS.flatMap(([field, label]) => rule[field] === undefined ? [] : [
      `${t(label)} ${field === 'reasoning' && rule.reasoning === null
        ? t('ai.catalog.form.reasoningFollowOutput') : money(rule[field])}`,
    ])
    if (rule.cacheWriteByTtl) rates.push(ttlSummary(Object.entries(rule.cacheWriteByTtl), t))
    return [`[${rule.minInputTokens ?? 0}, ${rule.maxInputTokensExclusive ?? '∞'})`, ...rates].join(' · ')
  }
  const extras = FACTOR_FIELDS.flatMap((field, index) => rule[field] == null ? [] : [
    `${t(RATE_FIELDS[index]![1])} ×${rule[field]}`,
  ])
  const condition = rule.type === 'service_tier_multiplier' ? rule.serviceTier
    : `${daysLabel(rule.weekdays ?? [], t)} ${rule.start}–${rule.end} (${rule.timezone})`
  return [condition, `×${rule.factor ?? '1'}`, ...extras].join(' · ')
}

function ttlSummary(rows: [string, string][], t: Translate) {
  return `${t('ai.catalog.form.pricingTtlMode')}: ${rows.length
    ? rows.map(([seconds, rate]) => `${seconds || '—'} s ${money(rate || null)}`).join(', ')
    : t('ai.catalog.form.pricingTtlUnpartitioned')}`
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="form-group"><span>{label}</span>{children}</label>
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return <section className="pricing-plan-section" aria-labelledby={id}><h4 id={id}>{title}</h4>{children}</section>
}

function CatalogNotice({ notice }: { notice: NonNullable<ModelDraft['pricingImportNotice']> }) {
  const { t } = useI18n()
  return (
    <div className={`pricing-import-notice is-${notice.kind}`} role="status">
      <p>{t(notice.kind === 'unsupported' ? 'ai.catalog.form.pricingImportUnsupported' : 'ai.catalog.form.pricingImportExcluded')}</p>
      {notice.supportIssues.length ? <ul>{notice.supportIssues.map((issue) => <li key={issue}>{formatCatalogSupportIssue(issue, t)}</li>)}</ul>
        : notice.kind === 'unsupported' ? <p>{t('ai.catalog.form.pricingImportNoData')}</p> : null}
      <details>
        <summary>{t('ai.catalog.form.viewCatalogPrice')}</summary>
        {notice.pricing ? <>
          <p>{rateSummary(notice.pricing.base, t)}</p>
          <ul>{notice.pricing.rules.map((rule, index) => <li key={index}>{ruleSummary(rule, t)}</li>)}</ul>
        </> : <p>{t('ai.catalog.form.pricingImportNoData')}</p>}
      </details>
    </div>
  )
}

export function ModelPricingPlanEditor({ value, onChange, pricingImportNotice }: {
  value: ModelPricingDraft
  onChange: (value: ModelPricingDraft) => void
  pricingImportNotice?: ModelDraft['pricingImportNotice']
}) {
  const { t } = useI18n()
  const today = new Date().toISOString().slice(0, 10)
  const changes = [...value.changes].sort((a, b) => a.from.localeCompare(b.from))
  const active = changes.filter((change) => change.from && change.from <= today).at(-1)
  const [selection, setSelection] = useState<string>()
  const selected = selection === BASE ? undefined : changes.find((change) => change.draftKey === selection) ?? active
  const planKey = selected?.draftKey ?? BASE
  const [newDate, setNewDate] = useState<string | null>(null)
  const sectionRef = useRef<HTMLElement>(null)
  const duplicateDate = (date: string, except?: string) => changes.some((change) => change.from === date && change.draftKey !== except)
  const conflictPlan = [
    { draftKey: BASE, definition: value.definition }, ...value.changes,
  ].find(({ definition }) => findPricingConflicts(definition).length)?.draftKey

  useEffect(() => {
    const form = sectionRef.current?.closest('form')
    if (!form || !conflictPlan) return
    // The enclosing form still owns validation and saving. Reveal the conflicting plan
    // before its existing first-error scroll runs, including conflicts in another plan.
    const revealConflict = () => setSelection(conflictPlan)
    form.addEventListener('submit', revealConflict, true)
    return () => form.removeEventListener('submit', revealConflict, true)
  }, [conflictPlan])

  function updateDefinition(definition: PricingDefinitionDraft) {
    onChange(selected ? { ...value, changes: value.changes.map((change) => change === selected ? { ...change, definition } : change) }
      : { ...value, definition })
  }

  function addChange() {
    if (!newDate || duplicateDate(newDate)) return
    const previous = changes.filter((change) => change.from && change.from < newDate).at(-1)
    const draftKey = createPricingDraftKey()
    onChange({ ...value, changes: [...value.changes, {
      draftKey, from: newDate, definition: clonePricingDefinitionDraft(previous?.definition ?? value.definition),
    }] })
    setSelection(draftKey)
    setNewDate(null)
  }

  const activeLabel = t('ai.catalog.form.pricingActive')
  return (
    <section className="structured-section model-pricing-editor" aria-labelledby="model-pricing-heading" ref={sectionRef}>
      <div className="structured-section-head">
        <h3 id="model-pricing-heading">{t('ai.catalog.form.pricing')}</h3>
        <span className="inline-hint">USD / {t('ai.catalog.form.pricingMillionTokens')}</span>
      </div>
      {pricingImportNotice && <CatalogNotice notice={pricingImportNotice} />}
      <div className="pricing-plan-toolbar">
        <div className="form-group">
          <span>{t('ai.catalog.form.pricingSelectPlan')}</span>
          <Select aria-label={t('ai.catalog.form.pricingSelectPlan')} value={planKey}
            options={[
              { value: BASE, label: `${t('ai.catalog.form.pricingBasePlan')}${active ? '' : ` · ${activeLabel}`}` },
              ...changes.map((change) => ({ value: change.draftKey, label: `${change.from || '—'}${change === active ? ` · ${activeLabel}` : ''}` })),
            ]} onChange={setSelection} />
        </div>
        <Button variant="ghost" onClick={() => setNewDate(newDate === null ? '' : null)}>{t('ai.catalog.form.addPricingChange')}</Button>
      </div>
      {newDate !== null && <div className="pricing-plan-toolbar">
        <Field label={t('ai.catalog.form.pricingFromDate')}>
          <TextInput type="date" value={newDate} onChange={(event) => setNewDate(event.target.value)} />
        </Field>
        <Button variant="ghost" disabled={!newDate || duplicateDate(newDate)} onClick={addChange}>{t('ai.catalog.form.pricingCreateDraft')}</Button>
        {newDate && duplicateDate(newDate) && <span className="field-error" role="alert">{t('ai.catalog.form.pricingDuplicateDate')}</span>}
      </div>}
      {selected && newDate === null && <div className="pricing-plan-toolbar">
        <Field label={t('ai.catalog.form.pricingFromDate')}>
          <TextInput type="date" value={selected.from} onChange={(event) => {
            const from = event.target.value
            setSelection(selected.draftKey)
            onChange({ ...value, changes: value.changes.map((change) => change === selected ? { ...change, from } : change) })
          }} />
        </Field>
        <Button variant="ghost" onClick={() => {
          onChange({ ...value, changes: value.changes.filter((change) => change !== selected) })
          setSelection(undefined)
        }}>{t('ai.catalog.form.pricingRemoveChange')}</Button>
        {selected.from && duplicateDate(selected.from, selected.draftKey) && <span className="field-error" role="alert">{t('ai.catalog.form.pricingDuplicateDate')}</span>}
      </div>}
      {(!selected || selected.from && selected.from <= today) && hasConfiguredPricing(value)
        ? <p className="inline-hint">{t('ai.catalog.form.pricingHistoryWarning')}</p> : null}
      <DefinitionEditor key={planKey} definition={selected?.definition ?? value.definition}
        configured={hasConfiguredPricing(value)} onChange={updateDefinition} />
      <PricingPreview value={value} />
    </section>
  )
}

function DefinitionEditor({ definition, configured, onChange }: {
  definition: PricingDefinitionDraft
  configured: boolean
  onChange: (definition: PricingDefinitionDraft) => void
}) {
  const { t } = useI18n()
  const { base, inputTokenRanges, serviceTiers, timePeriods } = definition
  const conflicts = findPricingConflicts(definition)
  const [newRule, setNewRule] = useState('')
  const inherit = t('ai.catalog.form.pricingTtlInherit')
  const required = t('ai.catalog.form.pricingRateRequired')
  const unpriced = t('ai.catalog.form.pricingUnpriced')
  const reasoningOptions = [
    { value: 'follow', label: t('ai.catalog.form.reasoningFollowOutput') },
    { value: 'separate', label: t('ai.catalog.form.reasoningSeparate') },
  ]

  function updateRows<K extends 'inputTokenRanges' | 'serviceTiers' | 'timePeriods'>(group: K, rows: PricingDefinitionDraft[K]) {
    onChange({ ...definition, [group]: rows })
  }

  function rule(group: 'inputTokenRanges' | 'serviceTiers' | 'timePeriods', key: string, index: number, summary: string, children: ReactNode, remove: () => void) {
    const peerRows = conflicts.filter((conflict) => conflict.group === group && conflict.keys.includes(key))
      .flatMap((conflict) => conflict.keys.filter((other) => other !== key))
      .map((other) => definition[group].findIndex((row) => row.draftKey === other) + 1)
    const reason = group === 'inputTokenRanges' ? 'pricingOverlapTokens' : group === 'serviceTiers' ? 'pricingDuplicateTier' : 'pricingOverlapTime'
    return (
      <div key={key} className="pricing-plan-row" data-conflict={peerRows.length > 0 || undefined}>
        <details className="pricing-rule-details" open={newRule === key || undefined}>
          <summary>{index + 1}. {summary}</summary>
          <div className="pricing-rule-fields">
            {children}
            <button type="button" className="ghost-inline-btn" onClick={remove}>{t('ai.catalog.form.removePricingRule')}</button>
          </div>
        </details>
        {!!peerRows.length && <span className="field-error" role="alert">{t(`ai.catalog.form.${reason}`, { rows: peerRows.join(', ') })}</span>}
      </div>
    )
  }

  return <>
    <Section title={t('ai.catalog.form.pricingBase')}>
      {!configured && <p className="inline-hint">{t('ai.catalog.form.pricingNotConfigured')}</p>}
      <div className="form-grid-2">
        {RATE_FIELDS.slice(0, 4).map(([field, label]) => (
          <Field key={field} label={t(label)}>
            <TextInput inputMode="decimal" value={base[field] ?? ''}
              placeholder={field === 'cacheWrite' ? t('ai.catalog.form.pricingNotSet') : configured ? required : unpriced}
              invalid={configured && field !== 'cacheWrite' && !base[field]}
              onChange={(event) => onChange({ ...definition, base: { ...base, [field]: sanitizeDecimalInput(event.target.value) } })} />
          </Field>
        ))}
      </div>
      <PricingTtlInputs rows={base.cacheWriteByTtl} onChange={(cacheWriteByTtl) => onChange({ ...definition, base: { ...base, cacheWriteByTtl } })} />
      <div className="form-grid-2">
        <div className="form-group"><span>{t('ai.catalog.form.reasoningPrice')}</span>
          <Select aria-label={t('ai.catalog.form.reasoningPricing')} value={base.reasoning == null ? 'follow' : 'separate'}
            options={reasoningOptions} onChange={(mode) => onChange({ ...definition, base: { ...base, reasoning: mode === 'follow' ? null : '' } })} />
        </div>
        {base.reasoning != null && <Field label={t('ai.catalog.form.pricingReasoningRate')}>
          <TextInput inputMode="decimal" value={base.reasoning} placeholder={required} invalid={!base.reasoning}
            onChange={(event) => onChange({ ...definition, base: { ...base, reasoning: sanitizeDecimalInput(event.target.value) } })} />
        </Field>}
      </div>
    </Section>
    <Section title={t('ai.catalog.form.pricingRanges')}>
      {inputTokenRanges.map((range, index) => {
        const update = (patch: Partial<typeof range>) => updateRows('inputTokenRanges', inputTokenRanges.map((row) => row === range ? { ...row, ...patch } : row))
        const rates = RATE_FIELDS.flatMap(([field, label]) => range.rates[field] === null && field === 'reasoning'
          ? [`${t(label)}: ${t('ai.catalog.form.reasoningFollowOutput')}`]
          : range.rates[field] ? [`${t(label)} ${money(range.rates[field])}`] : [])
        if (range.cacheWriteTtlMode === 'unpartitioned') rates.push(ttlSummary([], t))
        if (range.cacheWriteTtlMode === 'custom') rates.push(range.cacheWriteByTtl.length
          ? ttlSummary(range.cacheWriteByTtl.map((row) => [row.seconds, row.rate]), t)
          : t('ai.catalog.form.pricingTtlCustom'))
        return rule('inputTokenRanges', range.draftKey, index,
          `[${range.minInputTokens || '0'}, ${range.maxInputTokensExclusive || '∞'}) · ${rates.join(' · ') || inherit}`, <>
            <div className="form-grid-2">
              <Field label={t('ai.catalog.form.pricingMinTokens')}><TextInput inputMode="numeric" value={range.minInputTokens}
                onChange={(event) => update({ minInputTokens: event.target.value.replace(/\D/g, '') })} /></Field>
              <Field label={t('ai.catalog.form.pricingMaxTokens')}><TextInput inputMode="numeric" value={range.maxInputTokensExclusive}
                onChange={(event) => update({ maxInputTokensExclusive: event.target.value.replace(/\D/g, '') })} /></Field>
              {RATE_FIELDS.slice(0, 4).map(([field, label]) => <Field key={field} label={t(label)}>
                <TextInput inputMode="decimal" placeholder={inherit} value={range.rates[field] ?? ''}
                  onChange={(event) => update({ rates: { ...range.rates, [field]: sanitizeDecimalInput(event.target.value) } })} />
              </Field>)}
              <div className="form-group"><span>{t('ai.catalog.form.reasoningPrice')}</span>
                <Select aria-label={t('ai.catalog.form.reasoningPricing')}
                  value={range.rates.reasoning === null ? 'follow' : range.rates.reasoning === undefined ? 'inherit' : 'separate'}
                  options={[{ value: 'inherit', label: inherit }, ...reasoningOptions]}
                  onChange={(mode) => {
                    const rates = { ...range.rates }
                    if (mode === 'inherit') delete rates.reasoning
                    else rates.reasoning = mode === 'follow' ? null : ''
                    update({ rates })
                  }} />
              </div>
              {range.rates.reasoning != null && <Field label={t('ai.catalog.form.pricingReasoningRate')}>
                <TextInput inputMode="decimal" placeholder={inherit} value={range.rates.reasoning}
                  onChange={(event) => update({ rates: { ...range.rates, reasoning: sanitizeDecimalInput(event.target.value) } })} />
              </Field>}
            </div>
            <div className="form-group"><span>{t('ai.catalog.form.pricingTtlMode')}</span>
              <Select aria-label={t('ai.catalog.form.pricingTtlMode')} value={range.cacheWriteTtlMode} options={[
                { value: 'inherit', label: inherit }, { value: 'custom', label: t('ai.catalog.form.pricingTtlCustom') },
                { value: 'unpartitioned', label: t('ai.catalog.form.pricingTtlUnpartitioned') },
              ]} onChange={(mode) => {
                if (mode === 'inherit' || mode === 'unpartitioned' || mode === 'custom') update({ cacheWriteTtlMode: mode })
              }} />
            </div>
            {range.cacheWriteTtlMode === 'custom' && <PricingTtlInputs rows={range.cacheWriteByTtl}
              onChange={(cacheWriteByTtl) => update({ cacheWriteByTtl })} />}
          </>, () => updateRows('inputTokenRanges', inputTokenRanges.filter((row) => row !== range)))
      })}
      <button type="button" className="ghost-inline-btn" onClick={() => {
        const draftKey = createPricingDraftKey()
        setNewRule(draftKey)
        updateRows('inputTokenRanges', [...inputTokenRanges, {
          draftKey, minInputTokens: '', maxInputTokensExclusive: '', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [],
        }])
      }}>{t('ai.catalog.form.addPricingRange')}</button>
    </Section>
    <Section title={t('ai.catalog.form.pricingTiers')}>
      {serviceTiers.map((tier, index) => {
        const update = (patch: Partial<typeof tier>) => updateRows('serviceTiers', serviceTiers.map((row) => row === tier ? { ...row, ...patch } : row))
        return rule('serviceTiers', tier.draftKey, index, `${tier.serviceTier || '—'} · ${factorSummary(tier.factors, t)}`, <>
          <Field label={t('ai.catalog.form.pricingTierName')}><TextInput value={tier.serviceTier}
            onChange={(event) => update({ serviceTier: event.target.value })} /></Field>
          <FactorInputs factors={tier.factors} onChange={(factors) => update({ factors })} />
        </>, () => updateRows('serviceTiers', serviceTiers.filter((row) => row !== tier)))
      })}
      <button type="button" className="ghost-inline-btn" onClick={() => {
        const draftKey = createPricingDraftKey()
        setNewRule(draftKey)
        updateRows('serviceTiers', [...serviceTiers, { draftKey, serviceTier: '', factors: blankFactors() }])
      }}>{t('ai.catalog.form.addPricingTier')}</button>
    </Section>
    <Section title={t('ai.catalog.form.pricingTimes')}>
      {!!timePeriods.length && <Field label={t('ai.catalog.form.pricingTimezone')}>
        <TextInput value={timePeriods[0]!.timezone} onChange={(event) => updateRows('timePeriods',
          timePeriods.map((row) => ({ ...row, timezone: event.target.value })))} />
      </Field>}
      {timePeriods.map((period, index) => {
        const update = (patch: Partial<TimePeriodDraft>) => updateRows('timePeriods', timePeriods.map((row) => row === period ? { ...row, ...patch } : row))
        const label = (row: TimePeriodDraft) => `${daysLabel(row.weekdays, t)} · ${row.start || '—'}–${row.end || '—'}`
        const split = overnightIntervals(period)
        return rule('timePeriods', period.draftKey, index, `${label(period)} · ${factorSummary(period.factors, t)}`, <>
          <div className="weekday-picker" role="group" aria-label={t('ai.catalog.form.pricingWeekdays')}>
            {DAYS.map((day) => <Checkbox key={day} checked={period.weekdays.includes(day)} label={dayLabel(day, t)}
              onChange={(checked) => update({ weekdays: checked ? [...period.weekdays, day].sort() : period.weekdays.filter((item) => item !== day) })} />)}
          </div>
          <div className="form-grid-2">
            <Field label={t('ai.catalog.form.pricingStart')}><TextInput placeholder="HH:mm" value={period.start}
              onChange={(event) => update({ start: event.target.value })} /></Field>
            <Field label={t('ai.catalog.form.pricingEnd')}><TextInput placeholder="HH:mm" value={period.end}
              onChange={(event) => update({ end: event.target.value })} /></Field>
          </div>
          {split.length > 0 && <div>
            <p className="inline-hint">{t('ai.catalog.form.pricingOvernight', { intervals: split.map(label).join(' / ') })}</p>
            <Button variant="ghost" onClick={() => updateRows('timePeriods', timePeriods.flatMap((row) => row === period
              ? splitCrossMidnightPeriod(period) : [row]))}>{t('ai.catalog.form.pricingConfirmSplit')}</Button>
          </div>}
          <FactorInputs factors={period.factors} onChange={(factors) => update({ factors })} />
        </>, () => updateRows('timePeriods', timePeriods.filter((row) => row !== period)))
      })}
      <button type="button" className="ghost-inline-btn" onClick={() => {
        const draftKey = createPricingDraftKey()
        setNewRule(draftKey)
        updateRows('timePeriods', [...timePeriods, {
          draftKey, weekdays: [], start: '00:00', end: '24:00', timezone: timePeriods[0]?.timezone ?? 'UTC', factors: blankFactors(),
        }])
      }}>{t('ai.catalog.form.addPricingTime')}</button>
    </Section>
  </>
}

function overnightIntervals(period: TimePeriodDraft) {
  const clock = /^(?:[01]\d|2[0-3]):[0-5]\d$/
  return clock.test(period.start) && clock.test(period.end) && period.start > period.end
    ? splitCrossMidnightPeriod(period, () => period.draftKey) : []
}

function factorSummary(factors: PricingFactorsDraft, t: Translate) {
  return [`×${factors.factor || '—'}`, ...FACTOR_FIELDS.flatMap((field, index) => factors[field]
    ? [`${t(RATE_FIELDS[index]![1])} ×${factors[field]}`] : [])].join(' · ')
}

function FactorInputs({ factors, onChange }: { factors: PricingFactorsDraft; onChange: (factors: PricingFactorsDraft) => void }) {
  const { t } = useI18n()
  return <>
    <Field label={t('ai.catalog.form.pricingFactor')}><TextInput inputMode="decimal" value={factors.factor}
      onChange={(event) => onChange({ ...factors, factor: sanitizeDecimalInput(event.target.value) })} /></Field>
    <details className="pricing-factor-details">
      <summary>{t('ai.catalog.form.pricingFactorOverrides')}</summary>
      <div className="form-grid-2 pricing-rule-fields">
        {FACTOR_FIELDS.map((field, index) => <Field key={field} label={t(RATE_FIELDS[index]![1])}>
          <TextInput inputMode="decimal" placeholder={factors.factor} value={factors[field]}
            onChange={(event) => onChange({ ...factors, [field]: sanitizeDecimalInput(event.target.value) })} />
        </Field>)}
      </div>
    </details>
  </>
}

function PricingPreview({ value }: { value: ModelPricingDraft }) {
  const { t } = useI18n()
  const timeId = useId()
  const tierId = useId()
  const [inputTokens, setInputTokens] = useState('1000')
  const [requestedAt, setRequestedAt] = useState('')
  const [serviceTier, setServiceTier] = useState('')
  const signature = JSON.stringify([value, inputTokens, requestedAt, serviceTier])
  const [current, setCurrent] = useState(() => ({ signature }))
  const [result, setResult] = useState<Awaited<ReturnType<typeof previewModelPricing>> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const controllerRef = useRef<AbortController | null>(null)
  if (signature !== current.signature) {
    // Reset rather than cache by signature: reverting inputs cannot revive an old result.
    setCurrent({ signature })
    setResult(null)
    setError(null)
    setBusy(false)
  }
  useEffect(() => () => controllerRef.current?.abort(), [current])

  async function run() {
    setResult(null)
    setError(null)
    const tokens = Number(inputTokens)
    if (!/^(?:0|[1-9]\d*)$/.test(inputTokens) || !Number.isSafeInteger(tokens)) {
      setError(t('ai.catalog.form.pricingPreviewInvalidTokens'))
      return
    }
    const time = requestedAt ? Date.parse(`${requestedAt}Z`) : undefined
    if (time !== undefined && !Number.isSafeInteger(time)) {
      setError(t('ai.catalog.form.pricingPreviewInvalidTime'))
      return
    }
    let pricing
    try { pricing = normalizePricingDraft(value) }
    catch (cause) { setError(toUserFacingErrorMessage(cause)); return }
    controllerRef.current?.abort()
    const controller = new AbortController()
    controllerRef.current = controller
    setBusy(true)
    try {
      const response = await previewModelPricing({ pricing: pricing ?? null, inputTokens: tokens,
        ...(time === undefined ? {} : { requestedAt: time }), ...(serviceTier.trim() ? { serviceTier: serviceTier.trim() } : {}),
      }, controller.signal)
      if (!controller.signal.aborted) setResult(response)
    } catch (cause) {
      if (!controller.signal.aborted) setError(toUserFacingErrorMessage(cause))
    } finally {
      if (!controller.signal.aborted) setBusy(false)
    }
  }

  return <Section title={t('ai.catalog.form.pricingPreview')}>
    <p className="inline-hint">{t('ai.catalog.form.pricingPreviewScope')}</p>
    <div className="form-grid-2">
      <Field label={t('ai.catalog.form.pricingInputTokens')}><TextInput inputMode="numeric" value={inputTokens}
        onChange={(event) => setInputTokens(event.target.value.replace(/\D/g, ''))} /></Field>
      <Field label={t('ai.catalog.form.pricingPreviewTime')}><TextInput id={timeId} type="datetime-local" value={requestedAt}
        onChange={(event) => setRequestedAt(event.target.value)} /></Field>
      <Field label={t('ai.catalog.form.pricingServiceTier')}><TextInput id={tierId} value={serviceTier}
        onChange={(event) => setServiceTier(event.target.value)} /></Field>
    </div>
    <Button variant="ghost" loading={busy} onClick={() => void run()}>{t('ai.catalog.form.pricingPreviewRun')}</Button>
    {error && <p className="field-error" role="alert">{error}</p>}
    {result && <div className="pricing-preview-result" role="status">
      {result.rates ? <>
        <strong>{t('ai.catalog.form.pricingPreviewResult')}</strong>
        <p>{rateSummary(result.rates, t)}</p>
        <p>{result.activeFrom ? t('ai.catalog.form.pricingPreviewActiveFrom', { date: result.activeFrom }) : t('ai.catalog.form.pricingBasePlan')}</p>
        {result.matchedRules.length ? <>
          <p>{t('ai.catalog.form.pricingPreviewMatched')}</p>
          <ul>{result.matchedRules.map((rule, index) => <li key={index}>{ruleSummary(rule, t)}</li>)}</ul>
        </> : <p>{t('ai.catalog.form.pricingPreviewNoRules')}</p>}
      </> : <>
        <p>{t(hasConfiguredPricing(value) ? 'ai.catalog.form.pricingPreviewUnknown' : 'ai.catalog.form.pricingNotConfigured')}</p>
        {hasConfiguredPricing(value) && <div className="pricing-plan-toolbar">
          {!requestedAt && <Button variant="ghost" onClick={() => document.getElementById(timeId)?.focus()}>{t('ai.catalog.form.pricingPreviewAddTime')}</Button>}
          {!serviceTier.trim() && <Button variant="ghost" onClick={() => document.getElementById(tierId)?.focus()}>{t('ai.catalog.form.pricingPreviewAddTier')}</Button>}
        </div>}
      </>}
    </div>}
  </Section>
}
