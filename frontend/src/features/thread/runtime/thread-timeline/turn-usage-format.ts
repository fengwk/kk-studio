import type { TurnUsage, UsageCost } from '@/features/thread/runtime/thread-timeline-types'
import { translate, type TranslationKey } from '@/shared/i18n'
import { formatCompactTokens } from '@/features/thread/runtime/thread-timeline/content-utils'
import {
  formatUsageCost,
  mergeUsageCost,
  normalizeUsageCost,
} from '@/features/thread/runtime/thread-timeline/usage-cost'

export {
  addDecimalStrings,
  formatUsageCost,
  normalizeUsageCost,
} from '@/features/thread/runtime/thread-timeline/usage-cost'

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

/**
 * 两个可空非负安全整数相加；任一为 null 或非非负安全整数返回 null（最小未知传播）；
 * 相加后超出安全整数范围时发生溢出，返回 null（未知）。
 */
export function addNullableSafeInteger(
  a: number | null | undefined,
  b: number | null | undefined,
): number | null {
  if (a == null || b == null || !isNonNegativeSafeInteger(a) || !isNonNegativeSafeInteger(b)) {
    return null
  }
  const sum = a + b
  return Number.isSafeInteger(sum) ? sum : null
}

/** 构造一个字段全部为 unknown (null) 的 TurnUsage 对象。 */
function createUnknownTurnUsage(): TurnUsage {
  return {
    input: null,
    output: null,
    cacheRead: null,
    cacheWrite: null,
    reasoning: null,
    providerTotal: null,
    cost: null,
    decodeTokens: null,
    decodeDurationMillis: null,
    contextInputTokens: null,
  }
}

/**
 * 从持久 ASSISTANT Entry 的 `assistantMetadata` 提取 provider usage。
 *
 * 只按 canonical 字段名解析；
 * 只有 assistantMetadata 为 null/undefined 才是无模型调用，返回 null；
 * 显式存在的空对象、数组、primitive 或损坏 usage 返回全未知 TurnUsage（不创造零）；
 * reasoningTokens 必须满足非负安全整数且 known reasoning <= output 一致校验，非法不得真 0；
 * providerTotalTokens 必须为非负安全整数，非法不得真 0；
 * 合法 usage 下 reasoning 未知且后端 cost 已知仍保留费用；非法或缺失 usage 时完整费用 unknown（null）。
 */
export function parseAssistantUsage(
  metadata: unknown,
  usageCost: unknown = null,
): TurnUsage | null {
  if (metadata == null) {
    return null
  }
  if (typeof metadata !== 'object' || Array.isArray(metadata) || Object.keys(metadata).length === 0) {
    return createUnknownTurnUsage()
  }

  const metaRecord = metadata as Record<string, unknown>
  const rawUsage = metaRecord.usage

  if (rawUsage == null || typeof rawUsage !== 'object') {
    return createUnknownTurnUsage()
  }

  const usage = rawUsage as Record<string, unknown>
  const rawInput = usage.inputTokens
  const rawOutput = usage.outputTokens
  const rawCacheRead = usage.cacheReadTokens
  const rawCacheWrite = usage.cacheWriteTokens

  if (
    !isNonNegativeSafeInteger(rawInput)
    || !isNonNegativeSafeInteger(rawOutput)
    || !isNonNegativeSafeInteger(rawCacheRead)
    || !isNonNegativeSafeInteger(rawCacheWrite)
  ) {
    return createUnknownTurnUsage()
  }

  const input = rawInput
  const output = rawOutput
  const cacheRead = rawCacheRead
  const cacheWrite = rawCacheWrite

  let reasoning: number | null = null
  if (isNonNegativeSafeInteger(usage.reasoningTokens)) {
    if (usage.reasoningTokens <= output) {
      reasoning = usage.reasoningTokens
    } else {
      reasoning = null
    }
  }

  const providerTotal = isNonNegativeSafeInteger(usage.providerTotalTokens)
    ? usage.providerTotalTokens
    : null

  const cost = normalizeUsageCost(usageCost)

  // decodeDurationMillis 是 harness 持久字段：只接受 backend 形态的安全整数 > 0。
  // 小数、Infinity、超安全整数、字符串及非规范形态一律视为无效（null）。
  const decodeDurationMillis =
    typeof metaRecord.decodeDurationMillis === 'number'
    && Number.isSafeInteger(metaRecord.decodeDurationMillis)
    && metaRecord.decodeDurationMillis > 0
      ? metaRecord.decodeDurationMillis
      : null

  const decodeTokens = decodeDurationMillis != null ? output : null
  const contextSum = input + cacheRead + cacheWrite
  const contextInputTokens = Number.isSafeInteger(contextSum) ? contextSum : null

  return {
    input,
    output,
    cacheRead,
    cacheWrite,
    reasoning,
    providerTotal,
    cost,
    decodeTokens,
    decodeDurationMillis,
    contextInputTokens,
  }
}

/**
 * 估算缓存命中率：cacheRead / (input + cacheRead + cacheWrite)。
 * 返回未取整的百分数，展示层统一保留两位小数；任一输入未知或分母为 0 时返回 null，
 * 展示层在分母为 0 且已知时默认显示 0.00%，在未知时显示 noData。
 */
export function calculateCacheHitRate(usage: {
  input: number | null
  cacheRead: number | null
  cacheWrite: number | null
}): number | null {
  if (usage.input == null || usage.cacheRead == null || usage.cacheWrite == null) {
    return null
  }
  const denominator = usage.input + usage.cacheRead + usage.cacheWrite
  if (!Number.isSafeInteger(denominator) || denominator <= 0) {
    return null
  }
  return (usage.cacheRead / denominator) * 100
}

/**
 * 有效测速样本：decodeTokens 为有限非负 token、decodeDurationMillis 为有限正 duration。
 * 缺失、负数、NaN/Infinity 或 0 样本一律视为无效，绝不进入速率分子与分母。
 */
function isValidDecodeSample(sample: {
  decodeTokens?: number | null
  decodeDurationMillis?: number | null
}): sample is { decodeTokens: number; decodeDurationMillis: number } {
  return (
    sample.decodeTokens != null
    && Number.isFinite(sample.decodeTokens)
    && sample.decodeTokens >= 0
    && sample.decodeDurationMillis != null
    && Number.isFinite(sample.decodeDurationMillis)
    && sample.decodeDurationMillis > 0
  )
}

/**
 * 估算解码速率：decodeTokens * 1000 / decodeDurationMillis。
 * 仅在有效样本上计算，无样本返回 null；速率 >= 10 取整、< 10 保留 1 位小数。
 */
export function calculateDecodeTokensPerSecond(usage: {
  decodeTokens?: number | null
  decodeDurationMillis?: number | null
}): number | null {
  if (!isValidDecodeSample(usage)) {
    return null
  }
  const rate = (usage.decodeTokens * 1000) / usage.decodeDurationMillis
  return rate >= 10 ? Math.round(rate) : Math.round(rate * 10) / 10
}

/**
 * 累加 TurnUsage 共享函数。
 * next 为 null 时原样返回；current 为 null 时返回 next；否则调用 mergeTurnUsage。
 */
export function accumulateTurnUsage(
  current: TurnUsage | null,
  next: TurnUsage | null,
): TurnUsage | null {
  if (next == null) {
    return current
  }
  return current == null ? next : mergeTurnUsage(current, next)
}

/**
 * 合并同一 Turn 或 branch 内多个 ASSISTANT 调用的 Usage。
 * 消耗累加；最小未知传播（任一未知则为 null）；safe integer 溢出变未知；
 * known reasoning <= output 一致校验，非法不得真 0；
 * 费用按精确十进制求和（缺失或跨币种 -> null）；最新一次调用上下文覆盖
 * contextInputTokens；测速样本仅累加有效样本分子与分母。
 */
export function mergeTurnUsage(existing: TurnUsage, next: TurnUsage): TurnUsage {
  const input = addNullableSafeInteger(existing.input, next.input)
  const output = addNullableSafeInteger(existing.output, next.output)
  const cacheRead = addNullableSafeInteger(existing.cacheRead, next.cacheRead)
  const cacheWrite = addNullableSafeInteger(existing.cacheWrite, next.cacheWrite)

  let reasoning = addNullableSafeInteger(existing.reasoning, next.reasoning)
  if (reasoning != null && output != null && reasoning > output) {
    reasoning = null
  }

  const providerTotal = addNullableSafeInteger(existing.providerTotal, next.providerTotal)
  const cost = mergeUsageCost(existing.cost, next.cost)
  const contextInputTokens =
    next.contextInputTokens !== undefined
      ? next.contextInputTokens
      : (existing.contextInputTokens ?? null)

  let decodeTokens: number | null = null
  let decodeDurationMillis: number | null = null

  if (isValidDecodeSample(existing) && isValidDecodeSample(next)) {
    const tokensSum = existing.decodeTokens + next.decodeTokens
    const durationSum = existing.decodeDurationMillis + next.decodeDurationMillis
    if (
      isNonNegativeSafeInteger(tokensSum)
      && Number.isSafeInteger(durationSum)
      && durationSum > 0
    ) {
      decodeTokens = tokensSum
      decodeDurationMillis = durationSum
    } else {
      decodeTokens = null
      decodeDurationMillis = null
    }
  } else if (isValidDecodeSample(existing)) {
    decodeTokens = existing.decodeTokens
    decodeDurationMillis = existing.decodeDurationMillis
  } else if (isValidDecodeSample(next)) {
    decodeTokens = next.decodeTokens
    decodeDurationMillis = next.decodeDurationMillis
  }

  return {
    input,
    output,
    cacheRead,
    cacheWrite,
    reasoning,
    providerTotal,
    cost,
    decodeTokens,
    decodeDurationMillis,
    contextInputTokens,
  }
}

/**
 * Turn usage 摘要文本（Conversation TurnSummary、Event TURN_END 与 Footer 共用）：
 * `↑input · ↓output · RcacheRead · WcacheWrite · cost · cache N.NN% · X tok/s`。
 * 所有统计项固定保留；未知 token、缺失费用或测速样本时显示 noData（不显示 0），
 * 真实 0 保留，不为缺失定价假定币种。默认值仅用于展示，不写入 usage facts。
 * 统计项分隔符统一为 U+00B7，Footer 只在其外层使用 U+2223 分组，不覆盖本函数。
 */
export function formatTurnUsageText(
  usage: {
    input: number | null
    output: number | null
    cacheRead: number | null
    cacheWrite: number | null
    cost: UsageCost | null
    decodeTokens?: number | null
    decodeDurationMillis?: number | null
  },
  t: (key: TranslationKey) => string = translate,
): string {
  const noData = t('ai.runtime.status.noData')
  const parts = [
    usage.input != null ? `↑${formatCompactTokens(usage.input)}` : `↑${noData}`,
    usage.output != null ? `↓${formatCompactTokens(usage.output)}` : `↓${noData}`,
    usage.cacheRead != null ? `R${formatCompactTokens(usage.cacheRead)}` : `R${noData}`,
    usage.cacheWrite != null ? `W${formatCompactTokens(usage.cacheWrite)}` : `W${noData}`,
  ]
  const costText = formatUsageCost(usage.cost)
  parts.push(costText ?? noData)
  const cacheHitRate = calculateCacheHitRate(usage)
  parts.push(
    cacheHitRate != null
      ? `cache ${cacheHitRate.toFixed(2)}%`
      : usage.input === 0 && usage.cacheRead === 0 && usage.cacheWrite === 0
        ? 'cache 0.00%'
        : `cache ${noData}`,
  )
  const speed = calculateDecodeTokensPerSecond(usage)
  parts.push(`${speed ?? noData} tok/s`)
  return parts.join(' · ')
}
