import type {
  ModelPricingDTO,
  ModelPricingRuleDTO,
  ModelRateSetDTO,
} from '@/shared/api/generated/contracts'

export type PricingRateField = 'input' | 'output' | 'cacheRead' | 'cacheWrite' | 'reasoning'

export interface PricingRatesDraft {
  input: string
  output: string
  cacheRead: string
  cacheWrite: string
  cacheWriteByTtl: Record<string, string>
  /** undefined=unknown/inherit, null=follow output, string=independent price. */
  reasoning: string | null | undefined
}

export interface PricingFactorsDraft {
  factor: string
  inputFactor: string
  outputFactor: string
  cacheReadFactor: string
  cacheWriteFactor: string
  reasoningFactor: string
}

export interface InputRangeDraft {
  draftKey: string
  minInputTokens: string
  maxInputTokensExclusive: string
  rates: Partial<Record<PricingRateField, string | null>>
  cacheWriteTtlMode: 'inherit' | 'custom' | 'unpartitioned'
  cacheWriteByTtl: Record<string, string>
}

export interface ServiceTierDraft {
  draftKey: string
  serviceTier: string
  factors: PricingFactorsDraft
}

export interface TimePeriodDraft {
  draftKey: string
  weekdays: number[]
  start: string
  end: string
  timezone: string
  factors: PricingFactorsDraft
}

export interface PricingDefinitionDraft {
  base: PricingRatesDraft
  inputTokenRanges: InputRangeDraft[]
  serviceTiers: ServiceTierDraft[]
  timePeriods: TimePeriodDraft[]
}

export interface PricingChangeDraft {
  draftKey: string
  from: string
  definition: PricingDefinitionDraft
}

/** Stable editor keys remain local and are removed by normalizePricingDraft. */
export interface ModelPricingDraft {
  definition: PricingDefinitionDraft
  changes: PricingChangeDraft[]
}

type PricingDefinitionDTO = Pick<ModelPricingDTO, 'base' | 'rules'>

let draftKeySequence = 0

export function createPricingDraftKey(): string {
  draftKeySequence += 1
  return `pricing-draft-${draftKeySequence}`
}

function newFactors(rule?: ModelPricingRuleDTO): PricingFactorsDraft {
  return {
    factor: rule?.factor ?? '1',
    inputFactor: rule?.inputFactor ?? '',
    outputFactor: rule?.outputFactor ?? '',
    cacheReadFactor: rule?.cacheReadFactor ?? '',
    cacheWriteFactor: rule?.cacheWriteFactor ?? '',
    reasoningFactor: rule?.reasoningFactor ?? '',
  }
}

export function emptyPricingRates(): PricingRatesDraft {
  return {
    input: '',
    output: '',
    cacheRead: '',
    cacheWrite: '',
    cacheWriteByTtl: {},
    reasoning: undefined,
  }
}

export function emptyPricingDefinition(): PricingDefinitionDraft {
  return { base: emptyPricingRates(), inputTokenRanges: [], serviceTiers: [], timePeriods: [] }
}

export function emptyModelPricingDraft(): ModelPricingDraft {
  return { definition: emptyPricingDefinition(), changes: [] }
}

export function clonePricingDefinitionDraft(definition: PricingDefinitionDraft): PricingDefinitionDraft {
  return {
    base: { ...definition.base, cacheWriteByTtl: { ...definition.base.cacheWriteByTtl } },
    inputTokenRanges: definition.inputTokenRanges.map((range) => ({
      ...range,
      draftKey: createPricingDraftKey(),
      rates: { ...range.rates },
      cacheWriteByTtl: { ...range.cacheWriteByTtl },
    })),
    serviceTiers: definition.serviceTiers.map((tier) => ({
      ...tier, draftKey: createPricingDraftKey(), factors: { ...tier.factors },
    })),
    timePeriods: definition.timePeriods.map((period) => ({
      ...period, draftKey: createPricingDraftKey(), weekdays: [...period.weekdays], factors: { ...period.factors },
    })),
  }
}

function cloneRates(rates: ModelRateSetDTO): PricingRatesDraft {
  return {
    input: rates.input,
    output: rates.output,
    cacheRead: rates.cacheRead,
    cacheWrite: rates.cacheWrite ?? '',
    cacheWriteByTtl: { ...rates.cacheWriteByTtl },
    reasoning: rates.reasoning,
  }
}

function cloneDefinition(pricing: PricingDefinitionDTO): PricingDefinitionDraft {
  const definition: PricingDefinitionDraft = {
    base: cloneRates(pricing.base),
    inputTokenRanges: [],
    serviceTiers: [],
    timePeriods: [],
  }
  for (const rule of pricing.rules) {
    if (rule.type === 'input_token_range') {
      if (rule.cacheWriteByTtl === null) {
        throw new Error('cacheWriteByTtl must be an object when specified')
      }
      const ttl = rule.cacheWriteByTtl
      definition.inputTokenRanges.push({
        draftKey: createPricingDraftKey(),
        minInputTokens: String(rule.minInputTokens ?? ''),
        maxInputTokensExclusive: rule.maxInputTokensExclusive == null ? '' : String(rule.maxInputTokensExclusive),
        rates: {
          ...(rule.input !== undefined ? { input: rule.input } : {}),
          ...(rule.output !== undefined ? { output: rule.output } : {}),
          ...(rule.cacheRead !== undefined ? { cacheRead: rule.cacheRead } : {}),
          ...(rule.cacheWrite !== undefined ? { cacheWrite: rule.cacheWrite } : {}),
          ...(rule.reasoning !== undefined ? { reasoning: rule.reasoning } : {}),
        },
        cacheWriteTtlMode: ttl === undefined ? 'inherit' : Object.keys(ttl).length === 0 ? 'unpartitioned' : 'custom',
        cacheWriteByTtl: { ...(ttl ?? {}) },
      })
    } else if (rule.type === 'service_tier_multiplier') {
      definition.serviceTiers.push({
        draftKey: createPricingDraftKey(),
        serviceTier: rule.serviceTier ?? '',
        factors: newFactors(rule),
      })
    } else if (rule.type === 'time_multiplier') {
      definition.timePeriods.push({
        draftKey: createPricingDraftKey(),
        weekdays: [...(rule.weekdays ?? [])],
        start: rule.start ?? '',
        end: rule.end ?? '',
        timezone: rule.timezone ?? 'UTC',
        factors: newFactors(rule),
      })
    } else {
      throw new Error(`Unsupported pricing rule type: ${rule.type}`)
    }
  }
  return definition
}

/** Read a full pricing definition into editor-safe, stable local keys. */
export function clonePricingDraft(pricing?: ModelPricingDTO | null): ModelPricingDraft {
  if (!pricing) return emptyModelPricingDraft()
  return {
    definition: cloneDefinition(pricing),
    changes: pricing.changes.map((change) => ({
      draftKey: createPricingDraftKey(),
      from: change.from,
      definition: cloneDefinition(change),
    })),
  }
}

export type PricingConflict = { group: 'inputTokenRanges' | 'serviceTiers' | 'timePeriods'; keys: string[] }

function overlap(aStart: number, aEnd: number, bStart: number, bEnd: number): boolean {
  return aStart < bEnd && bStart < aEnd
}

function minuteOfDay(value: string): number | null {
  const match = /^(\d{2}):(\d{2})$/.exec(value)
  if (!match) return null
  const hour = Number(match[1])
  const minute = Number(match[2])
  if (hour === 24 && minute === 0) return 1440
  if (hour > 23 || minute > 59) return null
  return hour * 60 + minute
}

/** Live conflict checks only compare complete, parseable rows. */
export function findPricingConflicts(definition: PricingDefinitionDraft): PricingConflict[] {
  const conflicts: PricingConflict[] = []
  for (let left = 0; left < definition.inputTokenRanges.length; left++) {
    const a = definition.inputTokenRanges[left]!
    const aMinText = a.minInputTokens.trim()
    const aMaxText = a.maxInputTokensExclusive.trim()
    const aMin = aMinText ? Number(aMinText) : 0
    const aMax = aMaxText ? Number(aMaxText) : Number.POSITIVE_INFINITY
    if ((!aMinText && !aMaxText) || !Number.isSafeInteger(aMin) || aMin < 0
      || (aMaxText && (!Number.isSafeInteger(aMax) || aMin >= aMax))) continue
    for (let right = left + 1; right < definition.inputTokenRanges.length; right++) {
      const b = definition.inputTokenRanges[right]!
      const bMinText = b.minInputTokens.trim()
      const bMaxText = b.maxInputTokensExclusive.trim()
      const bMin = bMinText ? Number(bMinText) : 0
      const bMax = bMaxText ? Number(bMaxText) : Number.POSITIVE_INFINITY
      if ((!bMinText && !bMaxText) || !Number.isSafeInteger(bMin) || bMin < 0
        || (bMaxText && (!Number.isSafeInteger(bMax) || bMin >= bMax))) continue
      if (overlap(aMin, aMax, bMin, bMax)) {
        conflicts.push({ group: 'inputTokenRanges', keys: [a.draftKey, b.draftKey] })
      }
    }
  }
  for (let left = 0; left < definition.serviceTiers.length; left++) {
    const a = definition.serviceTiers[left]!
    const tier = a.serviceTier.trim()
    if (!tier) continue
    for (let right = left + 1; right < definition.serviceTiers.length; right++) {
      const b = definition.serviceTiers[right]!
      if (tier === b.serviceTier.trim()) conflicts.push({ group: 'serviceTiers', keys: [a.draftKey, b.draftKey] })
    }
  }
  for (let left = 0; left < definition.timePeriods.length; left++) {
    const a = definition.timePeriods[left]!
    const aStart = minuteOfDay(a.start)
    const aEnd = minuteOfDay(a.end)
    if (aStart == null || aEnd == null || aStart >= aEnd) continue
    for (let right = left + 1; right < definition.timePeriods.length; right++) {
      const b = definition.timePeriods[right]!
      if (a.timezone !== b.timezone) continue
      const bStart = minuteOfDay(b.start)
      const bEnd = minuteOfDay(b.end)
      if (bStart == null || bEnd == null || bStart >= bEnd) continue
      const aDays = a.weekdays.length ? a.weekdays : [1, 2, 3, 4, 5, 6, 7]
      const bDays = b.weekdays.length ? b.weekdays : [1, 2, 3, 4, 5, 6, 7]
      if (aDays.some((day) => bDays.includes(day)) && overlap(aStart, aEnd, bStart, bEnd)) {
        conflicts.push({ group: 'timePeriods', keys: [a.draftKey, b.draftKey] })
      }
    }
  }
  return conflicts
}

function validUtcDate(value: string): boolean {
  if (value.length !== 10 || value[4] !== '-' || value[7] !== '-') return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value
}

function normalizeDecimal(value: string, path: string, optional: boolean): string | null {
  const normalized = value.trim()
  if (!normalized && optional) return null
  if (!/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(normalized)) {
    throw new Error(`${path} must be a non-negative decimal`)
  }
  return normalized
}

function normalizeBase(rates: PricingRatesDraft): ModelRateSetDTO {
  const input = normalizeDecimal(rates.input, 'pricing.base.input', false)!
  const output = normalizeDecimal(rates.output, 'pricing.base.output', false)!
  const cacheRead = normalizeDecimal(rates.cacheRead, 'pricing.base.cacheRead', false)!
  const cacheWrite = normalizeDecimal(rates.cacheWrite, 'pricing.base.cacheWrite', true)
  const cacheWriteByTtl: Record<string, string> = {}
  for (const [seconds, raw] of Object.entries(rates.cacheWriteByTtl)) {
    if (!/^[1-9]\d*$/.test(seconds)) throw new Error('pricing.base.cacheWriteByTtl keys must be positive integer seconds')
    cacheWriteByTtl[seconds] = normalizeDecimal(raw, `pricing.base.cacheWriteByTtl.${seconds}`, false)!
  }
  const normalized: ModelRateSetDTO = {
    input, output, cacheRead, cacheWrite, cacheWriteByTtl,
  }
  if (rates.reasoning === null) normalized.reasoning = null
  else if (rates.reasoning != null && rates.reasoning.trim() !== '') {
    normalized.reasoning = normalizeDecimal(rates.reasoning, 'pricing.base.reasoning', false)!
  }
  return normalized
}

function normalizeFactors(factors: PricingFactorsDraft, path: string): Pick<
  ModelPricingRuleDTO,
  'factor' | 'inputFactor' | 'outputFactor' | 'cacheReadFactor' | 'cacheWriteFactor' | 'reasoningFactor'
> {
  const factor = normalizeDecimal(factors.factor, `${path}.factor`, false)!
  const output: ReturnType<typeof normalizeFactors> = { factor }
  for (const field of ['inputFactor', 'outputFactor', 'cacheReadFactor', 'cacheWriteFactor', 'reasoningFactor'] as const) {
    const value = normalizeDecimal(factors[field], `${path}.${field}`, true)
    if (value !== null && value !== factor) output[field] = value
  }
  return output
}

function normalizeRuleDefinition(definition: PricingDefinitionDraft): ModelPricingDTO['rules'] {
  const conflicts = findPricingConflicts(definition)
  if (conflicts.length) throw new Error(`pricing rules conflict: ${conflicts[0]!.keys.join(', ')}`)
  const ranges: ModelPricingRuleDTO[] = definition.inputTokenRanges.map((range, index) => {
    const min = range.minInputTokens.trim()
    const max = range.maxInputTokensExclusive.trim()
    if (!min && !max) throw new Error(`pricing.rules input range ${index + 1} requires at least one token bound`)
    if ((min && !/^(?:0|[1-9]\d*)$/.test(min)) || (max && !/^[1-9]\d*$/.test(max))) {
      throw new Error(`pricing.rules input range ${index + 1} requires integer token bounds`)
    }
    const minInputTokens = min ? Number(min) : 0
    const maxInputTokensExclusive = max ? Number(max) : undefined
    if (!Number.isSafeInteger(minInputTokens)
      || (maxInputTokensExclusive !== undefined && (!Number.isSafeInteger(maxInputTokensExclusive) || minInputTokens >= maxInputTokensExclusive))) {
      throw new Error(`pricing.rules input range ${index + 1} has invalid token bounds`)
    }
    const rule: ModelPricingRuleDTO = {
      type: 'input_token_range',
      ...(min ? { minInputTokens } : {}),
      ...(maxInputTokensExclusive === undefined ? {} : { maxInputTokensExclusive }),
    }
    for (const field of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) {
      const value = range.rates[field]
      if (value != null && value.trim() !== '') rule[field] = normalizeDecimal(value, `pricing.rules[${index}].${field}`, false)
    }
    if (range.rates.reasoning === null) rule.reasoning = null
    else if (range.rates.reasoning != null && range.rates.reasoning.trim() !== '') {
      rule.reasoning = normalizeDecimal(range.rates.reasoning, `pricing.rules[${index}].reasoning`, false)
    }
    if (range.cacheWriteTtlMode === 'unpartitioned') rule.cacheWriteByTtl = {}
    if (range.cacheWriteTtlMode === 'custom') {
      rule.cacheWriteByTtl = {}
      for (const [seconds, value] of Object.entries(range.cacheWriteByTtl)) {
        if (!/^[1-9]\d*$/.test(seconds)) throw new Error(`pricing.rules[${index}] TTL must be positive seconds`)
        rule.cacheWriteByTtl[seconds] = normalizeDecimal(value, `pricing.rules[${index}].TTL.${seconds}`, false)!
      }
    }
    return rule
  })
  const serviceTiers = definition.serviceTiers.map((tier, index): ModelPricingRuleDTO => {
    const serviceTier = tier.serviceTier.trim()
    if (!serviceTier) throw new Error(`pricing.rules service tier ${index + 1} is required`)
    return {
      type: 'service_tier_multiplier', serviceTier,
      ...normalizeFactors(tier.factors, `pricing.rules service tier ${index + 1}`),
    }
  })
  const timezones = new Set(definition.timePeriods.map((period) => period.timezone.trim()).filter(Boolean))
  if (timezones.size > 1) throw new Error('pricing.rules time periods must share one timezone')
  const timePeriods = definition.timePeriods.map((period, index): ModelPricingRuleDTO => {
    const start = minuteOfDay(period.start)
    const end = minuteOfDay(period.end)
    if (start == null || end == null || start >= end) throw new Error(`pricing.rules time period ${index + 1} must be a non-crossing time range`)
    if (!period.timezone.trim()) throw new Error(`pricing.rules time period ${index + 1} timezone is required`)
    try { new Intl.DateTimeFormat('en-US', { timeZone: period.timezone }) }
    catch { throw new Error(`pricing.rules time period ${index + 1} timezone must be an IANA time zone`) }
    const weekdays = [...new Set(period.weekdays)].sort((a, b) => a - b)
    if (weekdays.some((day) => !Number.isInteger(day) || day < 1 || day > 7)) throw new Error('pricing.rules weekdays must be ISO 1..7')
    return {
      type: 'time_multiplier',
      ...(weekdays.length ? { weekdays } : {}),
      start: period.start, end: period.end, timezone: period.timezone,
      ...normalizeFactors(period.factors, `pricing.rules time period ${index + 1}`),
    }
  })
  ranges.sort((left, right) => (left.minInputTokens ?? 0) - (right.minInputTokens ?? 0))
  serviceTiers.sort((left, right) => (left.serviceTier ?? '').localeCompare(right.serviceTier ?? ''))
  timePeriods.sort((left, right) => {
    return (left.weekdays?.[0] ?? 0) - (right.weekdays?.[0] ?? 0) || (left.start ?? '').localeCompare(right.start ?? '')
  })
  return [...ranges, ...serviceTiers, ...timePeriods]
}

function normalizeDefinition(definition: PricingDefinitionDraft): PricingDefinitionDTO {
  return { base: normalizeBase(definition.base), rules: normalizeRuleDefinition(definition) }
}

function hasConfiguredPricing(draft: ModelPricingDraft): boolean {
  const rates = draft.definition.base
  const hasBaseRate = [rates.input, rates.output, rates.cacheRead, rates.cacheWrite].some((value) => value.trim() !== '')
    || (rates.reasoning != null && rates.reasoning.trim() !== '')
    || Object.keys(rates.cacheWriteByTtl).length > 0
  return hasBaseRate || draft.definition.inputTokenRanges.length > 0 || draft.definition.serviceTiers.length > 0
    || draft.definition.timePeriods.length > 0 || draft.changes.length > 0
}

/** Validate a full local draft, flatten fixed groups, and strip all editor-only keys. */
export function normalizePricingDraft(draft: ModelPricingDraft): ModelPricingDTO | undefined {
  if (!hasConfiguredPricing(draft)) return undefined
  const definition = normalizeDefinition(draft.definition)
  const dates = new Set<string>()
  const changes = draft.changes.map((change, index) => {
    if (!validUtcDate(change.from)) throw new Error(`pricing.changes[${index}].from must be a UTC date`)
    if (dates.has(change.from)) throw new Error(`duplicate pricing change date: ${change.from}`)
    dates.add(change.from)
    return { from: change.from, ...normalizeDefinition(change.definition) }
  }).sort((left, right) => left.from.localeCompare(right.from))
  return { ...definition, changes }
}

export function pricingDefinitionAt(
  plan: ModelPricingDTO,
  requestedAt?: string | null,
): PricingDefinitionDTO | null {
  const base = { base: plan.base, rules: plan.rules }
  if (!plan.changes.length) return base
  if (!requestedAt) return null
  const date = requestedAt.slice(0, 10)
  const change = plan.changes.filter((item) => item.from <= date).at(-1)
  return change ? { base: change.base, rules: change.rules } : base
}

/** Cross-midnight time rules are split so each DTO rule has one weekday/time interval. */
export function splitCrossMidnightPeriod(
  period: Omit<TimePeriodDraft, 'draftKey'>,
  createKey: () => string = createPricingDraftKey,
): TimePeriodDraft[] {
  const start = minuteOfDay(period.start)
  const end = minuteOfDay(period.end)
  if (start == null || end == null || start >= 1440 || start === end) throw new Error('time period must use valid distinct times')
  if (start < end) return [{ ...period, weekdays: [...period.weekdays], draftKey: createKey() }]
  if (end === 0) return [{ ...period, end: '24:00', weekdays: [...period.weekdays], draftKey: createKey() }]
  return [
    { ...period, end: '24:00', weekdays: [...period.weekdays], draftKey: createKey() },
    {
      ...period,
      start: '00:00',
      weekdays: period.weekdays.map((day) => day % 7 + 1).sort((left, right) => left - right),
      draftKey: createKey(),
    },
  ]
}

/** Use BigInt decimal arithmetic for pure validation/example math; previews use the backend. */
export function multiplyPriceFactors(price: string, factors: readonly string[]): string {
  const values = [price, ...factors]
  if (values.some((value) => !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value))) {
    throw new Error('price and factors must be non-negative decimals')
  }
  let numerator = 1n
  let scale = 0
  for (const value of values) {
    const [whole, fraction = ''] = value.split('.')
    numerator *= BigInt(`${whole}${fraction}`)
    scale += fraction.length
  }
  let result = numerator.toString().padStart(scale + 1, '0')
  if (scale > 0) result = `${result.slice(0, -scale)}.${result.slice(-scale)}`
  result = result.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
  return result || '0'
}
