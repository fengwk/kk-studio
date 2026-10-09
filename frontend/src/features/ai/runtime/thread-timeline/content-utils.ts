import { asRecord, getString } from '@/features/ai/runtime/payload-json'
import type {
  ToolAttachment,
  ToolAttachmentType,
  ToolContent,
  TurnUsage,
  UsageCost,
} from '@/features/ai/runtime/thread-timeline-types'
import { apiBaseUrl } from '@/shared/api/client'

const MANAGED_RESOURCE_URI_PATTERN = /^(file:|s3:)/i
const SHA256_PATTERN = /^[0-9a-f]{64}$/

export function contentText(content: Record<string, unknown>): string {
  const type = getString(content.type)
  if (type === 'text' || type === 'thinking') {
    return getString(content.text)
  }
  if (type === 'json') {
    return stringifyJsonContent(content.json)
  }
  return ''
}

/**
 * 规范的 Tool content -> 有序展示内容。
 *
 * 只有非空 text、显式 json 字段与合法 resource 会保留；思考等其它类型不进入工具展示。
 * resource 的 URI/名称/媒体类型原样保留，媒体 URL 只在渲染时解析或直接使用 data:。
 */
export function toToolContent(content: Record<string, unknown>): ToolContent | null {
  const type = getString(content.type)
  if (type === 'text') {
    const text = getString(content.text)
    return text ? { type: 'text', text } : null
  }
  if (type === 'json') {
    return 'json' in content ? { type: 'json', value: content.json } : null
  }
  const attachment = toResourceAttachment(content)[0]
  return attachment ? { type: 'resource', attachment } : null
}

/** 投影一组规范 Tool contents，保持顺序并丢弃空内容。 */
export function toToolContents(contents: readonly Record<string, unknown>[]): ToolContent[] {
  const projected: ToolContent[] = []
  for (const content of contents) {
    const item = toToolContent(content)
    if (item != null) {
      projected.push(item)
    }
  }
  return projected
}

/**
 * json 内容的紧凑文本：字符串值先按 JSON 解析（规范 codec 两种形态都会出现），
 * 无法解析时按原字符串处理。用于把 JSON 结果交给只读解析器。
 */
export function jsonContentText(value: unknown): string {
  const encoded = JSON.stringify(normalizeJsonValue(value))
  return encoded === undefined ? String(normalizeJsonValue(value)) : encoded
}

/** json 内容的格式化正文：仅在结果声明为 json 时缩进展示，纯文本绝不猜测。 */
export function formatJsonContent(value: unknown): string {
  const normalized = normalizeJsonValue(value)
  if (typeof normalized === 'string') {
    return normalized
  }
  try {
    const encoded = JSON.stringify(normalized, null, 2)
    return encoded === undefined ? String(normalized) : encoded
  } catch {
    return String(normalized)
  }
}

function normalizeJsonValue(value: unknown): unknown {
  if (typeof value !== 'string') {
    return value
  }
  const trimmed = value.trim()
  if (!trimmed) {
    return value
  }
  try {
    return JSON.parse(trimmed)
  } catch {
    return value
  }
}

/** 规范的 Tool delta：对 object/array 类型的 json 值进行序列化而非丢弃。 */
function stringifyJsonContent(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  if (value == null) {
    return ''
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return ''
    }
  }
  return String(value)
}

/**
 * 规范的 resource content -> attachment：`{type:'resource', uri, mediaType, name, size,
 * sha256, preview?}` 与 durable `{type:'resource', blobId, name, preview?}`。
 * URI 是稳定的展示/链接标识；file:/s3: URI 不会被当作 base64 负载。
 * blobId 资源不携带 URI——预览/下载 URL 只在渲染时通过 storage service 解析。
 */
export function toResourceAttachment(content: Record<string, unknown>): ToolAttachment[] {
  const type = getString(content.type)
  const blobId = getString(content.blobId)
  if ((type === 'resource' || type === 'RESOURCE') && blobId.trim()) {
    const name = getString(content.name)
    if (!name.trim()) {
      return []
    }
    const mediaType = getString(content.mediaType)
    return [
      {
        type: resourceAttachmentType(mediaType),
        name,
        mime: mediaType,
        data: '',
        blobId,
        preview: getString(content.preview) || undefined,
        size: numberField(content, 'size', 'sizeBytes') || null,
      },
    ]
  }
  if (type !== 'resource') {
    return []
  }
  const uri = getString(content.uri)
  if (!uri.trim()) {
    return []
  }
  const mediaType = getString(content.mediaType)
  const name = getString(content.name)
  const size =
    typeof content.size === 'number' && Number.isSafeInteger(content.size) && content.size >= 0
      ? content.size
      : null
  const sha256 = getString(content.sha256) || null
  return [
    {
      type: resourceAttachmentType(mediaType),
      name,
      mime: mediaType,
      data: uri,
      preview: getString(content.preview) || undefined,
      size,
      sha256,
      downloadHref: managedResourceHref(uri, mediaType, name, size, sha256),
    },
  ]
}

function managedResourceHref(
  uri: string,
  mediaType: string,
  name: string,
  size: number | null,
  sha256: string | null,
): string | undefined {
  if (
    !MANAGED_RESOURCE_URI_PATTERN.test(uri)
    || size == null
    || !SHA256_PATTERN.test(sha256 ?? '')
    || !mediaType.trim()
  ) {
    return undefined
  }
  const query = new URLSearchParams({
    mediaType,
    size: String(size),
  })
  if (name.trim()) {
    query.set('name', name)
  }
  return `${apiBaseUrl}/harness/resources/${sha256}?${query}`
}

export function resourceAttachmentType(mediaType: string): ToolAttachmentType {
  if (mediaType.startsWith('image/')) {
    return 'image'
  }
  if (mediaType.startsWith('audio/')) {
    return 'audio'
  }
  if (mediaType.startsWith('video/')) {
    return 'video'
  }
  return 'file'
}

export function numberField(record: Record<string, unknown>, ...keys: string[]): number {
  for (const key of keys) {
    const value = record[key]
    if (typeof value === 'number' && Number.isFinite(value)) {
      return Math.max(0, value)
    }
    if (typeof value === 'string' && value.trim()) {
      const parsed = Number(value)
      if (Number.isFinite(parsed)) {
        return Math.max(0, parsed)
      }
    }
  }
  return 0
}

export function formatCompactTokens(count: number): string {
  if (!Number.isFinite(count) || count <= 0) {
    return '0'
  }
  if (count < 1000) {
    return String(Math.round(count))
  }
  if (count < 10_000) {
    return `${(count / 1000).toFixed(1)}k`
  }
  if (count < 1_000_000) {
    return `${Math.round(count / 1000)}k`
  }
  return `${(count / 1_000_000).toFixed(1)}M`
}

/** 读取投影的精确十进制文本：可选整数、可选小数，非负（后端金额不允许为负）。 */
const DECIMAL_PATTERN = /^\+?(\d+)(?:\.(\d+))?$/

interface DecimalParts {
  int: string
  frac: string
}

function parseDecimal(value: string): DecimalParts | null {
  const match = DECIMAL_PATTERN.exec(value.trim())
  if (match == null) {
    return null
  }
  return { int: match[1]!, frac: match[2] ?? '' }
}

/**
 * 两个十进制文本的精确求和（BigInt 对齐小数位，无浮点误差）。
 * 任一输入非法时返回 null，绝不用近似值兜底。
 */
export function addDecimalStrings(left: string, right: string): string | null {
  const a = parseDecimal(left)
  const b = parseDecimal(right)
  if (a == null || b == null) {
    return null
  }
  const scale = Math.max(a.frac.length, b.frac.length)
  const sum =
    BigInt(a.int + a.frac.padEnd(scale, '0')) + BigInt(b.int + b.frac.padEnd(scale, '0'))
  const digits = sum.toString().padStart(scale + 1, '0')
  if (scale === 0) {
    return digits
  }
  const intPart = digits.slice(0, digits.length - scale)
  const fracPart = digits.slice(digits.length - scale).replace(/0+$/, '')
  return fracPart ? `${intPart}.${fracPart}` : intPart
}

/** 费用展示阈值（1e-6）：精确金额落在 (0, 1e-6) 时展示为该下界，而不是伪造成 0 或夸大成 0.000001。 */
export const COST_DISPLAY_THRESHOLD = '0.000001'

/**
 * 精确十进制金额与阈值 1e-6 的比较：>0 大于、0 相等（含精确 0）、<0 小于、null 非法。
 * 用 BigInt 对齐小数位，不做任何浮点近似。
 */
function compareDecimalToThreshold(amount: string): number | null {
  const parts = parseDecimal(amount)
  if (parts == null) {
    return null
  }
  const scale = Math.max(parts.frac.length, 6)
  const value = BigInt(parts.int + parts.frac.padEnd(scale, '0'))
  if (value === 0n) {
    return 0
  }
  return value < 10n ** BigInt(scale - 6) ? -1 : 1
}

/**
 * 十进制金额的数值展示文本：统一四舍五入到最多 6 位小数并去掉尾随 0。
 * 只负责数值部分（不含货币与阈值下界）；非法输入返回 null。
 */
export function formatDecimalAmount(amount: string): string | null {
  const parts = parseDecimal(amount)
  if (parts == null) {
    return null
  }
  // 保留 6 位展示 + 1 位用于 HALF_UP 进位。
  const padded = (parts.frac + '0000000').slice(0, 7)
  let scaled = BigInt(parts.int + padded.slice(0, 6))
  if (padded[6] != null && padded[6] >= '5') {
    scaled += 1n
  }
  const digits = scaled.toString().padStart(7, '0')
  const intPart = digits.slice(0, digits.length - 6)
  const fracPart = digits.slice(digits.length - 6).replace(/0+$/, '')
  return fracPart ? `${intPart}.${fracPart}` : intPart
}

const CURRENCY_SYMBOLS: Record<string, string | undefined> = {
  USD: '$',
  CNY: '¥',
  EUR: '€',
  GBP: '£',
}

/** 货币展示前缀：常见币种用符号，其余用 "CODE "。 */
function currencyPrefix(currency: string): string {
  const symbol = CURRENCY_SYMBOLS[currency]
  return symbol == null ? `${currency} ` : symbol
}

/**
 * 读取投影 -> 展示文本；null 表示无可用定价（绝不显示成 $0）。
 * 精确金额落在 (0, 1e-6) 时展示 `<$0.000001`："<" 在货币符号/代码之前。
 */
export function formatUsageCost(cost: UsageCost | null): string | null {
  if (cost == null || !cost.currency.trim()) {
    return null
  }
  const order = compareDecimalToThreshold(cost.amount)
  if (order == null) {
    return null
  }
  const prefix = currencyPrefix(cost.currency)
  if (order < 0) {
    return `<${prefix}${COST_DISPLAY_THRESHOLD}`
  }
  const amount = formatDecimalAmount(cost.amount)
  return amount == null ? null : `${prefix}${amount}`
}

/** 校验后端 usageCost 读取投影；currency/amount 任一非法即视为无定价。 */
export function normalizeUsageCost(value: unknown): UsageCost | null {
  const record = asRecord(value)
  const currency = getString(record.currency).trim()
  const amount = getString(record.amount).trim()
  if (!currency || parseDecimal(amount) == null) {
    return null
  }
  return { currency, amount }
}

/**
 * 合并两次调用的读取投影费用。
 * 缺失（未定价）或跨币种时返回 null：绝不给出误导性的完整总额，也绝不伪造成 $0。
 */
export function mergeUsageCost(left: UsageCost | null, right: UsageCost | null): UsageCost | null {
  if (left == null || right == null || left.currency !== right.currency) {
    return null
  }
  const amount = addDecimalStrings(left.amount, right.amount)
  return amount == null ? null : { currency: left.currency, amount }
}

/**
 * 从持久 ASSISTANT Entry 的 `assistantMetadata` 提取 provider usage。
 *
 * 只接受 harness 持久 codec 的 canonical 字段名，不兼容 snake_case / 常见别名；
 * 费用来自 Entry 的 usageCost 读取投影，绝不从 metadata.cost 自行定价。
 */
export function parseAssistantUsage(
  metadata: Record<string, unknown>,
  usageCost: unknown = null,
): TurnUsage | null {
  const usage = asRecord(metadata.usage)
  const input = numberField(usage, 'inputTokens')
  const output = numberField(usage, 'outputTokens')
  const cacheRead = numberField(usage, 'cacheReadTokens')
  const cacheWrite =
    numberField(usage, 'cacheWriteTokens') + numberField(usage, 'cacheWriteLongTokens')
  const reasoning = numberField(usage, 'reasoningTokens')
  const providerTotal = numberField(usage, 'providerTotalTokens')
  const cost = normalizeUsageCost(usageCost)

  // decodeDurationMillis 是 harness 持久字段：只接受 backend 形态的安全整数 > 0。
  // 小数、Infinity、超安全整数、字符串以及 snake_case 别名一律视为无效（null）。
  const decodeDurationMillis =
    typeof metadata.decodeDurationMillis === 'number'
    && Number.isSafeInteger(metadata.decodeDurationMillis)
    && metadata.decodeDurationMillis > 0
      ? metadata.decodeDurationMillis
      : null
  const decodeTokens = decodeDurationMillis != null ? output + reasoning : null
  const contextInputTokens = input + cacheRead + cacheWrite

  if (
    input <= 0
    && output <= 0
    && cacheRead <= 0
    && cacheWrite <= 0
    && reasoning <= 0
    && providerTotal <= 0
    && cost == null
    && decodeDurationMillis == null
  ) {
    return null
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
 * 估算缓存命中率：cacheRead / (input + cacheRead + cacheWrite)。
 * 分母为 0 时返回 null，摘要默认显示 0%。
 */
export function calculateCacheHitRate(usage: {
  input: number
  cacheRead: number
  cacheWrite: number
}): number | null {
  const denominator = usage.input + usage.cacheRead + usage.cacheWrite
  if (denominator <= 0) {
    return null
  }
  return Math.round((usage.cacheRead / denominator) * 100)
}

/**
 * 有效测速样本：decodeTokens 为有限非负 token、decodeDurationMillis 为有限正 duration。
 * 缺失、负数、NaN/Infinity 或 0 样本一律视为无效，绝不进入速率分子与分母。
 */
export function isValidDecodeSample(sample: {
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
 * 仅在有效样本上计算，无样本返回 null，摘要默认显示 0；速率 >= 10 取整、< 10 保留 1 位小数。
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
 * 合并同一 Turn 内多个 ASSISTANT 调用的 Usage。
 * 消耗累加；费用按精确十进制求和（缺失或跨币种 -> null）；最新一次调用上下文覆盖
 * contextInputTokens；测速样本仅累加有效样本分子与分母。
 */
export function mergeTurnUsage(existing: TurnUsage, next: TurnUsage): TurnUsage {
  const input = existing.input + next.input
  const output = existing.output + next.output
  const cacheRead = existing.cacheRead + next.cacheRead
  const cacheWrite = existing.cacheWrite + next.cacheWrite
  const reasoning = existing.reasoning + next.reasoning
  const providerTotal = existing.providerTotal + next.providerTotal
  const cost = mergeUsageCost(existing.cost, next.cost)
  const contextInputTokens = next.contextInputTokens ?? existing.contextInputTokens ?? null

  let decodeTokens: number | null = null
  let decodeDurationMillis: number | null = null

  if (isValidDecodeSample(existing) && isValidDecodeSample(next)) {
    decodeTokens = existing.decodeTokens + next.decodeTokens
    decodeDurationMillis = existing.decodeDurationMillis + next.decodeDurationMillis
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
 * `↑input · ↓output · RcacheRead · WcacheWrite · cost · cache N% · X tok/s`。
 * 所有统计项固定保留；无缓存比例、测速样本或定价时默认显示 0，
 * 不为缺失定价假定币种。默认值仅用于展示，不写入 usage facts。
 * 统计项分隔符统一为 U+00B7，Footer 只在其外层使用 U+2223 分组，不覆盖本函数。
 */
export function formatTurnUsageText(usage: {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
  cost: UsageCost | null
  decodeTokens?: number | null
  decodeDurationMillis?: number | null
}): string {
  const parts = [
    `↑${formatCompactTokens(usage.input)}`,
    `↓${formatCompactTokens(usage.output)}`,
    `R${formatCompactTokens(usage.cacheRead)}`,
    `W${formatCompactTokens(usage.cacheWrite)}`,
  ]
  parts.push(formatUsageCost(usage.cost) ?? '0')
  const cacheHitRate = calculateCacheHitRate(usage)
  parts.push(`cache ${cacheHitRate ?? 0}%`)
  const speed = calculateDecodeTokensPerSecond(usage)
  parts.push(`${speed ?? 0} tok/s`)
  return parts.join(' · ')
}
