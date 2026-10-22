import type {
  AgentModelConditionDTO,
  AgentModelFactorSetDTO,
  AgentModelPricingDefinitionDTO,
  AgentModelPricingMultiplierDTO,
  AgentModelPricingOverrideDTO,
  AgentModelRatePatchDTO,
  AgentModelRateSetDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { ModelPricingDraft } from '@/features/ai/catalog/ai-console-types'

const DECIMAL_PATTERN = /^(?:0|[1-9]\d*)(?:\.\d+)?$/
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const START_TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/
const END_TIME_PATTERN = /^(?:(?:[01]\d|2[0-3]):[0-5]\d|24:00)$/
export const MAX_CONDITION_DEPTH = 8
export const MAX_CONDITION_CHILDREN = 64

/** 新建价格完整为零；reasoning=null 表示跟随 output。 */
export function emptyPricingDraft(): ModelPricingDraft {
  return {
    base: {
      input: '0',
      output: '0',
      cacheRead: '0',
      cacheWrite: '0',
      cacheWriteByTtl: {},
      reasoning: null,
    },
    overrides: [],
    multipliers: [],
  }
}

/** 深拷贝价格定义，避免表单直接修改查询结果对象。 */
export function clonePricing(pricing: AgentModelPricingDefinitionDTO): ModelPricingDraft {
  return {
    base: {
      ...pricing.base,
      cacheWriteByTtl: { ...pricing.base.cacheWriteByTtl },
    },
    overrides: pricing.overrides.map((rule) => ({
      id: rule.id,
      when: cloneCondition(rule.when),
      rates: { ...rule.rates, ...(rule.rates.cacheWriteByTtl
        ? { cacheWriteByTtl: { ...rule.rates.cacheWriteByTtl } }
        : {}) },
    })),
    multipliers: pricing.multipliers.map((rule) => ({
      id: rule.id,
      when: cloneCondition(rule.when),
      factors: { ...rule.factors },
    })),
  }
}

export function cloneCondition(condition: AgentModelConditionDTO): AgentModelConditionDTO {
  return {
    ...(condition.context ? { context: { ...condition.context } } : {}),
    ...(condition.serviceTier ? { serviceTier: [...condition.serviceTier] } : {}),
    ...(condition.date ? { date: { ...condition.date } } : {}),
    ...(condition.time
      ? {
          time: {
            ...condition.time,
            ...(condition.time.weekdays ? { weekdays: [...condition.time.weekdays] } : {}),
          },
        }
      : {}),
    ...(condition.all ? { all: condition.all.map(cloneCondition) } : {}),
    ...(condition.any ? { any: condition.any.map(cloneCondition) } : {}),
    ...(condition.not ? { not: cloneCondition(condition.not) } : {}),
  }
}

function requireDecimal(value: string | undefined, field: string): string {
  const normalized = (value ?? '').trim()
  if (!DECIMAL_PATTERN.test(normalized)) {
    throw new Error(`${field} must be a non-negative decimal string`)
  }
  return normalized
}

function normalizeTtlMap(
  value: Record<string, string> | undefined,
  field: string,
): Record<string, string> {
  const entries = Object.entries(value ?? {})
  const result: Record<string, string> = {}
  for (const [rawSeconds, rawRate] of entries) {
    const seconds = rawSeconds.trim()
    if (!/^[1-9]\d*$/.test(seconds)) {
      throw new Error(`${field} TTL keys must be positive integer seconds`)
    }
    if (result[seconds] != null) {
      throw new Error(`${field} contains duplicate TTL seconds: ${seconds}`)
    }
    result[seconds] = requireDecimal(rawRate, `${field} TTL ${seconds}`)
  }
  return result
}

function requireInteger(
  value: number | undefined,
  field: string,
  min = 0,
): number | undefined {
  if (value == null) {
    return undefined
  }
  if (!Number.isInteger(value) || value < min) {
    throw new Error(`${field} must be an integer >= ${min}`)
  }
  return value
}

/** 校验并返回完整价格定义；任何缺字段都不自动补 0，避免把“继承”误写成显式价格。 */
export function normalizePricing(
  draft: ModelPricingDraft,
): AgentModelPricingDefinitionDTO {
  if (!draft || !draft.base) {
    throw new Error('pricing.base is required')
  }
  const base: AgentModelRateSetDTO = {
    input: requireDecimal(draft.base.input, 'pricing.base.input'),
    output: requireDecimal(draft.base.output, 'pricing.base.output'),
    cacheRead: requireDecimal(draft.base.cacheRead, 'pricing.base.cacheRead'),
    cacheWrite: requireDecimal(draft.base.cacheWrite, 'pricing.base.cacheWrite'),
    cacheWriteByTtl: normalizeTtlMap(draft.base.cacheWriteByTtl, 'pricing.base'),
    reasoning: draft.base.reasoning == null
      ? null
      : requireDecimal(draft.base.reasoning, 'pricing.base.reasoning'),
  }
  const ruleIds = new Set<string>()
  const overrides = draft.overrides.map((rule, index) => normalizeOverride(rule, index, ruleIds))
  const multipliers = draft.multipliers.map((rule, index) =>
    normalizeMultiplier(rule, index, ruleIds),
  )
  return { base, overrides, multipliers }
}

function normalizeRuleId(id: string, index: number, ids: Set<string>): string {
  const normalized = id.trim()
  if (!normalized) {
    throw new Error(`pricing rule ${index + 1} id is required`)
  }
  if (ids.has(normalized)) {
    throw new Error(`duplicate pricing rule id: ${normalized}`)
  }
  ids.add(normalized)
  return normalized
}

function normalizeRatePatch(rates: AgentModelRatePatchDTO): AgentModelRatePatchDTO {
  const result: AgentModelRatePatchDTO = {}
  if (rates.input != null && rates.input.trim() !== '') {
    result.input = requireDecimal(rates.input, 'override.rates.input')
  }
  if (rates.output != null && rates.output.trim() !== '') {
    result.output = requireDecimal(rates.output, 'override.rates.output')
  }
  if (rates.cacheRead != null && rates.cacheRead.trim() !== '') {
    result.cacheRead = requireDecimal(rates.cacheRead, 'override.rates.cacheRead')
  }
  if (rates.cacheWrite != null && rates.cacheWrite.trim() !== '') {
    result.cacheWrite = requireDecimal(rates.cacheWrite, 'override.rates.cacheWrite')
  }
  const ttl = normalizeTtlMap(rates.cacheWriteByTtl, 'override.rates')
  // 显式空 map 会原子清空 base TTL 覆盖；缺省属性才表示继承。
  if (rates.cacheWriteByTtl !== undefined) {
    result.cacheWriteByTtl = ttl
  }
  if (rates.reasoning === null) {
    result.reasoning = null
  } else if (rates.reasoning != null && rates.reasoning.trim() !== '') {
    result.reasoning = requireDecimal(rates.reasoning, 'override.rates.reasoning')
  }
  return result
}

function normalizeOverride(
  rule: AgentModelPricingOverrideDTO,
  index: number,
  ids: Set<string>,
): AgentModelPricingOverrideDTO {
  return {
    id: normalizeRuleId(rule.id, index, ids),
    when: normalizeCondition(rule.when, 0, `pricing.overrides[${index}].when`),
    rates: normalizeRatePatch(rule.rates),
  }
}

function normalizeFactors(factors: AgentModelFactorSetDTO): AgentModelFactorSetDTO {
  const result: AgentModelFactorSetDTO = {}
  for (const field of ['input', 'output', 'cacheRead', 'cacheWrite', 'reasoning'] as const) {
    const value = factors[field]
    if (value != null && value.trim() !== '') {
      result[field] = requireDecimal(value, `multiplier.factors.${field}`)
    }
  }
  return result
}

function normalizeMultiplier(
  rule: AgentModelPricingMultiplierDTO,
  index: number,
  ids: Set<string>,
): AgentModelPricingMultiplierDTO {
  return {
    id: normalizeRuleId(rule.id, index, ids),
    when: normalizeCondition(rule.when, 0, `pricing.multipliers[${index}].when`),
    factors: normalizeFactors(rule.factors),
  }
}

/**
 * 校验结构化条件。上下文上下界互斥、日期/时间格式、IANA 时区、
 * weekday 1..7，以及 all/any/not 的深度和宽度均有界；空对象恒真。
 */
export function normalizeCondition(
  condition: AgentModelConditionDTO,
  depth = 0,
  field = 'condition',
): AgentModelConditionDTO {
  if (depth > MAX_CONDITION_DEPTH) {
    throw new Error(`${field} exceeds maximum nesting depth`)
  }
  const result: AgentModelConditionDTO = {}

  if (condition.context) {
    const context = condition.context
    if (context.gt != null && context.gte != null) {
      throw new Error(`${field}.context cannot set both gt and gte`)
    }
    if (context.lt != null && context.lte != null) {
      throw new Error(`${field}.context cannot set both lt and lte`)
    }
    const normalized = compact({
      gt: requireInteger(context.gt, `${field}.context.gt`),
      gte: requireInteger(context.gte, `${field}.context.gte`),
      lt: requireInteger(context.lt, `${field}.context.lt`),
      lte: requireInteger(context.lte, `${field}.context.lte`),
    })
    if (Object.keys(normalized).length > 0) {
      result.context = normalized
    }
  }

  if (condition.serviceTier !== undefined) {
    if (condition.serviceTier.length > MAX_CONDITION_CHILDREN) {
      throw new Error(`${field}.serviceTier exceeds maximum value count`)
    }
    // 显式空列表恒假；必须与“缺省不约束”区分，不能收窄为 undefined。
    result.serviceTier = uniqueNonBlank(condition.serviceTier)
  }

  if (condition.date) {
    const date = compact({
      gte: normalizeDate(condition.date.gte, `${field}.date.gte`),
      lt: normalizeDate(condition.date.lt, `${field}.date.lt`),
    })
    if (Object.keys(date).length > 0) {
      result.date = date
    }
  }

  if (condition.time) {
    const time = condition.time
    const start = time.start.trim()
    const end = time.end.trim()
    if (!START_TIME_PATTERN.test(start)) {
      throw new Error(`${field}.time.start must be HH:mm before 24:00`)
    }
    if (!END_TIME_PATTERN.test(end)) {
      throw new Error(`${field}.time.end must be HH:mm or 24:00`)
    }
    if (start === end) {
      throw new Error(`${field}.time.start and end must differ; use 00:00-24:00 for all day`)
    }
    const timezone = time.timezone.trim()
    if (!timezone) {
      throw new Error(`${field}.time.timezone is required`)
    }
    try {
      new Intl.DateTimeFormat('en-US', { timeZone: timezone })
    } catch {
      throw new Error(`${field}.time.timezone must be an IANA time zone`)
    }
    const weekdays = [...new Set(time.weekdays ?? [])].sort((left, right) => left - right)
    if (weekdays.some((day) => !Number.isInteger(day) || day < 1 || day > 7)) {
      throw new Error(`${field}.time.weekdays must be integers 1..7`)
    }
    result.time = {
      start,
      end,
      timezone,
      ...(weekdays.length > 0 ? { weekdays } : {}),
    }
  }

  for (const key of ['all', 'any'] as const) {
    const children = condition[key]
    if (children) {
      if (children.length > MAX_CONDITION_CHILDREN) {
        throw new Error(`${field}.${key} exceeds maximum child count`)
      }
      result[key] = children.map((child, index) =>
        normalizeCondition(child, depth + 1, `${field}.${key}[${index}]`),
      )
    }
  }
  if (condition.not) {
    result.not = normalizeCondition(condition.not, depth + 1, `${field}.not`)
  }
  return result
}

function normalizeDate(value: string | undefined, field: string): string | undefined {
  const normalized = value?.trim()
  if (!normalized) {
    return undefined
  }
  if (!DATE_PATTERN.test(normalized) || Number.isNaN(Date.parse(`${normalized}T00:00:00Z`))) {
    throw new Error(`${field} must be YYYY-MM-DD`)
  }
  return normalized
}

function compact<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined),
  ) as T
}

function uniqueNonBlank(values: ReadonlyArray<string>): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))]
}

/** 供规则摘要显示；只描述结构化条件，不拼接原始 JSON。 */
export function describeCondition(condition: AgentModelConditionDTO): string {
  const parts: string[] = []
  if (condition.context) {
    const context = condition.context
    if (context.gt != null) parts.push(`context > ${context.gt}`)
    if (context.gte != null) parts.push(`context ≥ ${context.gte}`)
    if (context.lt != null) parts.push(`context < ${context.lt}`)
    if (context.lte != null) parts.push(`context ≤ ${context.lte}`)
  }
  if (condition.serviceTier !== undefined) {
    parts.push(
      condition.serviceTier.length > 0
        ? `tier ∈ ${condition.serviceTier.join(', ')}`
        : 'tier ∈ ∅',
    )
  }
  if (condition.date?.gte) parts.push(`date ≥ ${condition.date.gte}`)
  if (condition.date?.lt) parts.push(`date < ${condition.date.lt}`)
  if (condition.time) {
    const weekdays = condition.time.weekdays?.join(',')
    parts.push(
      `time ${condition.time.start}-${condition.time.end} ${condition.time.timezone}`
        + (weekdays ? ` [${weekdays}]` : ''),
    )
  }
  if (condition.all) parts.push(`all(${condition.all.length})`)
  if (condition.any) parts.push(`any(${condition.any.length})`)
  if (condition.not) parts.push('not')
  return parts.length > 0 ? parts.join(' · ') : 'always'
}
