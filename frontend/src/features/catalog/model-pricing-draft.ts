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

/** 草稿 key 只用于 React 编辑定位；normalizePricingDraft 会显式移除这些字段。 */
export interface ModelPricingDraft {
  definition: PricingDefinitionDraft
  changes: PricingChangeDraft[]
}

export interface PricingRateSet {
  input: string | null
  output: string | null
  cacheRead: string | null
  cacheWrite: string | null
  cacheWriteByTtl: Record<string, string>
  reasoning: string | null | undefined
}

export interface PricingFactors {
  factor: string
  inputFactor?: string
  outputFactor?: string
  cacheReadFactor?: string
  cacheWriteFactor?: string
  reasoningFactor?: string
}

export type PricingRule =
  | {
      type: 'input_token_range'
      minInputTokens: number
      maxInputTokensExclusive: number
      input?: string
      output?: string
      cacheRead?: string
      cacheWrite?: string
      cacheWriteByTtl?: Record<string, string> | null
      reasoning?: string | null
    }
  | ({ type: 'service_tier_multiplier'; serviceTier: string } & PricingFactors)
  | ({ type: 'time_multiplier'; weekdays?: number[]; start: string; end: string; timezone: string } & PricingFactors)

export interface PricingDefinition {
  base: PricingRateSet
  rules: PricingRule[]
}

export interface ModelPricingPlan extends PricingDefinition {
  changes: Array<{ from: string } & PricingDefinition>
}

let draftKeySequence = 0

export function createPricingDraftKey(): string {
  draftKeySequence += 1
  return `pricing-draft-${draftKeySequence}`
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
    const aMin = Number(a.minInputTokens)
    const aMax = Number(a.maxInputTokensExclusive)
    if (!Number.isSafeInteger(aMin) || !Number.isSafeInteger(aMax) || aMin < 0 || aMin >= aMax) continue
    for (let right = left + 1; right < definition.inputTokenRanges.length; right++) {
      const b = definition.inputTokenRanges[right]!
      const bMin = Number(b.minInputTokens)
      const bMax = Number(b.maxInputTokensExclusive)
      if (Number.isSafeInteger(bMin) && Number.isSafeInteger(bMax) && overlap(aMin, aMax, bMin, bMax)) {
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

function normalizeRates(rates: PricingRatesDraft): PricingRateSet {
  const ttl: Record<string, string> = {}
  for (const [seconds, raw] of Object.entries(rates.cacheWriteByTtl)) {
    if (!/^[1-9]\d*$/.test(seconds)) throw new Error('cacheWriteByTtl keys must be positive integer seconds')
    ttl[seconds] = normalizeDecimal(raw, `cacheWriteByTtl.${seconds}`, false)!
  }
  return {
    input: normalizeDecimal(rates.input, 'base.input', true),
    output: normalizeDecimal(rates.output, 'base.output', true),
    cacheRead: normalizeDecimal(rates.cacheRead, 'base.cacheRead', true),
    cacheWrite: normalizeDecimal(rates.cacheWrite, 'base.cacheWrite', true),
    cacheWriteByTtl: ttl,
    reasoning: rates.reasoning == null
      ? rates.reasoning
      : normalizeDecimal(rates.reasoning, 'base.reasoning', true) ?? undefined,
  }
}

function normalizeFactors(factors: PricingFactorsDraft, path: string): PricingFactors {
  const factor = normalizeDecimal(factors.factor, `${path}.factor`, false)!
  const output: PricingFactors = { factor }
  for (const [source, target] of [
    ['inputFactor', 'inputFactor'], ['outputFactor', 'outputFactor'], ['cacheReadFactor', 'cacheReadFactor'],
    ['cacheWriteFactor', 'cacheWriteFactor'], ['reasoningFactor', 'reasoningFactor'],
  ] as const) {
    const value = normalizeDecimal(factors[source], `${path}.${source}`, true)
    if (value !== null && value !== factor) output[target] = value
  }
  return output
}

function normalizeDefinition(definition: PricingDefinitionDraft): PricingDefinition {
  const conflicts = findPricingConflicts(definition)
  if (conflicts.length) throw new Error(`pricing rules conflict: ${conflicts[0]!.keys.join(', ')}`)
  const inputTokenRanges: PricingRule[] = definition.inputTokenRanges.map((range, index) => {
    const min = range.minInputTokens.trim()
    const max = range.maxInputTokensExclusive.trim()
    if (!/^(?:0|[1-9]\d*)$/.test(min) || !/^[1-9]\d*$/.test(max)) {
      throw new Error(`inputTokenRanges[${index}] requires token bounds`)
    }
    const minInputTokens = Number(min)
    const maxInputTokensExclusive = Number(max)
    if (!Number.isSafeInteger(minInputTokens) || !Number.isSafeInteger(maxInputTokensExclusive) || minInputTokens >= maxInputTokensExclusive) {
      throw new Error(`inputTokenRanges[${index}] has invalid token bounds`)
    }
    const rule: Extract<PricingRule, { type: 'input_token_range' }> = {
      type: 'input_token_range', minInputTokens, maxInputTokensExclusive,
    }
    for (const field of ['input', 'output', 'cacheRead', 'cacheWrite'] as const) {
      const value = range.rates[field]
      if (value != null && value.trim() !== '') rule[field] = normalizeDecimal(value, `inputTokenRanges[${index}].${field}`, false)!
    }
    if (range.rates.reasoning === null) rule.reasoning = null
    else if (range.rates.reasoning != null && range.rates.reasoning.trim() !== '') {
      rule.reasoning = normalizeDecimal(range.rates.reasoning, `inputTokenRanges[${index}].reasoning`, false)!
    }
    if (range.cacheWriteTtlMode === 'unpartitioned') rule.cacheWriteByTtl = {}
    if (range.cacheWriteTtlMode === 'custom') {
      rule.cacheWriteByTtl = {}
      for (const [seconds, value] of Object.entries(range.cacheWriteByTtl)) {
        if (!/^[1-9]\d*$/.test(seconds)) throw new Error(`inputTokenRanges[${index}] TTL must be positive seconds`)
        rule.cacheWriteByTtl[seconds] = normalizeDecimal(value, `inputTokenRanges[${index}].TTL.${seconds}`, false)!
      }
    }
    return rule
  })
  const serviceTiers: PricingRule[] = definition.serviceTiers.map((tier, index) => {
    const serviceTier = tier.serviceTier.trim()
    if (!serviceTier) throw new Error(`serviceTiers[${index}].serviceTier is required`)
    return {
      type: 'service_tier_multiplier',
      serviceTier,
      ...normalizeFactors(tier.factors, `serviceTiers[${index}].factors`),
    }
  })
  const timezones = new Set(definition.timePeriods.map((period) => period.timezone.trim()).filter(Boolean))
  if (timezones.size > 1) throw new Error('timePeriods must share one timezone')
  const timePeriods: PricingRule[] = definition.timePeriods.map((period, index) => {
    const start = minuteOfDay(period.start)
    const end = minuteOfDay(period.end)
    if (start == null || end == null || start >= end) throw new Error(`timePeriods[${index}] must be a non-crossing time range`)
    if (!period.timezone.trim()) throw new Error(`timePeriods[${index}].timezone is required`)
    try { new Intl.DateTimeFormat('en-US', { timeZone: period.timezone }) }
    catch { throw new Error(`timePeriods[${index}].timezone must be an IANA time zone`) }
    const weekdays = [...new Set(period.weekdays)].sort((a, b) => a - b)
    if (weekdays.some((day) => !Number.isInteger(day) || day < 1 || day > 7)) throw new Error(`timePeriods[${index}].weekdays must be ISO 1..7`)
    return {
      type: 'time_multiplier',
      ...(weekdays.length ? { weekdays } : {}),
      start: period.start, end: period.end, timezone: period.timezone,
      ...normalizeFactors(period.factors, `timePeriods[${index}].factors`),
    }
  })
  inputTokenRanges.sort((left, right) => left.type === 'input_token_range' && right.type === 'input_token_range'
    ? left.minInputTokens - right.minInputTokens : 0)
  serviceTiers.sort((left, right) => left.type === 'service_tier_multiplier' && right.type === 'service_tier_multiplier'
    ? left.serviceTier.localeCompare(right.serviceTier) : 0)
  timePeriods.sort((left, right) => {
    if (left.type !== 'time_multiplier' || right.type !== 'time_multiplier') return 0
    return (left.weekdays?.[0] ?? 0) - (right.weekdays?.[0] ?? 0) || left.start.localeCompare(right.start)
  })
  return { base: normalizeRates(definition.base), rules: [...inputTokenRanges, ...serviceTiers, ...timePeriods] }
}

/** Validate a full local draft, flatten fixed rule groups, and strip all client keys. */
export function normalizePricingDraft(draft: ModelPricingDraft): ModelPricingPlan {
  const definition = normalizeDefinition(draft.definition)
  const dates = new Set<string>()
  const changes = draft.changes.map((change, index) => {
    if (!validUtcDate(change.from)) throw new Error(`changes[${index}].from must be a UTC date`)
    if (dates.has(change.from)) throw new Error(`duplicate price change date: ${change.from}`)
    dates.add(change.from)
    return { from: change.from, ...normalizeDefinition(change.definition) }
  }).sort((left, right) => left.from.localeCompare(right.from))
  return { ...definition, changes }
}

/** Resolve an effective definition by UTC date, returning unknown if a planned date is required. */
export function pricingDefinitionAt(
  plan: ModelPricingPlan,
  requestedAt?: string | null,
): PricingDefinition | null {
  const base = { base: plan.base, rules: plan.rules }
  if (!plan.changes.length) return base
  if (!requestedAt) return null
  const date = requestedAt.slice(0, 10)
  const change = plan.changes.filter((item) => item.from <= date).at(-1)
  return change ? { base: change.base, rules: change.rules } : base
}

/** Split a cross-midnight interval into this day and the next ISO weekday. */
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

/** Use BigInt decimal arithmetic for prices; never coerce money through IEEE-754. */
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
