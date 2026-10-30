/**
 * 精确 JSON number 的通用编解码：JSON 原文 -> {@link RawJsonNumber} -> JSON number。
 *
 * <p>浏览器 JSON.parse 会先舍入 number，因此读取包含 `equals` + `path` 结构（request-body 定价条件）的响应时使用 reviver 的
 * `context.source` 保留原文。写出使用 `JSON.rawJSON`。两个能力缺一不可；缺失时对含数字的条件显式失败，绝不 Number() 往返。
 */

/** 内部 sentinel：JSON.parse reviver 保留下来的 number 原文。 */
export interface RawJsonNumber {
  readonly rawJson: string
}

/** `JSON.rawJSON` 返回的原生对象：JSON.stringify 会把它写成 JSON number。 */
export interface NativeRawJsonNumber {
  readonly rawJSON: string
}

export type ExactJsonNumber = RawJsonNumber | NativeRawJsonNumber

const JSON_NUMBER = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/
const MAX_PRECISION = 64
const MAX_SCALE = 18
const SOURCE_ACCESS_PROBE = '0'

export function isRawJsonNumber(value: unknown): value is ExactJsonNumber {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  return typeof (value as RawJsonNumber).rawJson === 'string'
    || typeof (value as NativeRawJsonNumber).rawJSON === 'string'
}

/** 读取内部 sentinel 或原生 rawJSON 对象中的完整原文。 */
export function rawJsonNumberText(value: ExactJsonNumber): string {
  if (typeof (value as RawJsonNumber).rawJson === 'string') {
    return (value as RawJsonNumber).rawJson
  }
  return (value as NativeRawJsonNumber).rawJSON
}

/**
 * 同时检查 `JSON.rawJSON` 与 `JSON.parse` reviver 的 `context.source`。
 *
 * <p>只检查 rawJSON 不足以支撑 GET -> draft -> PUT 往返，因为读取阶段已经可能被 JSON.parse 舍入。
 */
export function supportsLosslessJsonNumbers(): boolean {
  if (typeof (JSON as unknown as { rawJSON?: unknown }).rawJSON !== 'function') {
    return false
  }
  let source: string | undefined
  try {
    const parse = JSON.parse as unknown as (
      input: string,
      reviver: (this: unknown, key: string, value: unknown, context?: { source?: string }) => unknown,
    ) => unknown
    parse(SOURCE_ACCESS_PROBE, function (_key, value, context) {
      source = context?.source
      return value
    })
  } catch {
    return false
  }
  return source === SOURCE_ACCESS_PROBE
}

/** 校验并规范化 JSON number 原文为无指数、去尾零的普通十进制文本；零统一为 `0`。 */
export function canonicalJsonNumber(raw: string): string {
  const text = raw.trim()
  if (!JSON_NUMBER.test(text)) {
    throw new Error('value must be a JSON number')
  }
  let body = text
  let sign = ''
  if (body.startsWith('-')) {
    sign = '-'
    body = body.slice(1)
  }
  const exponentIndex = body.search(/[eE]/)
  let exponent = 0
  if (exponentIndex >= 0) {
    const exponentText = body.slice(exponentIndex + 1)
    exponent = Number(exponentText)
    if (!Number.isSafeInteger(exponent)) {
      throw new Error('JSON number exponent is out of range')
    }
    body = body.slice(0, exponentIndex)
  }
  const dot = body.indexOf('.')
  const integerPart = dot >= 0 ? body.slice(0, dot) : body
  const fractionPart = dot >= 0 ? body.slice(dot + 1) : ''
  let digits = `${integerPart}${fractionPart}`.replace(/^0+/, '')
  // value = digits * 10^scale
  let scale = exponent - fractionPart.length
  if (!digits) {
    return '0'
  }
  const trimmed = digits.replace(/0+$/, '')
  scale += digits.length - trimmed.length
  digits = trimmed
  if (digits.length > MAX_PRECISION) {
    throw new Error(`JSON number precision must not exceed ${MAX_PRECISION}`)
  }
  if (scale < -MAX_SCALE || scale > MAX_SCALE) {
    throw new Error(`JSON number scale must be between -${MAX_SCALE} and ${MAX_SCALE}`)
  }
  if (scale >= 0) {
    // 正 scale 是整数末尾补零，例如 1e3 -> 1000。
    return `${sign}${digits}${'0'.repeat(scale)}`
  }
  // 负 scale 是插入小数点，例如 digits=25, scale=-1 -> 2.5；digits=1, scale=-2 -> 0.01。
  const split = digits.length + scale
  if (split > 0) {
    return `${sign}${digits.slice(0, split)}.${digits.slice(split)}`
  }
  return `${sign}0.${'0'.repeat(-split)}${digits}`
}

/** 把原文转换为真正的 JSON number；能力缺失时显式失败。 */
export function wireJsonNumber(value: ExactJsonNumber, field: string): unknown {
  const canonical = canonicalJsonNumber(rawJsonNumberText(value))
  const rawJSON = (JSON as unknown as { rawJSON?: (text: string) => unknown }).rawJSON
  if (!supportsLosslessJsonNumbers() || typeof rawJSON !== 'function') {
    throw new Error(`${field} requires exact JSON number support (JSON.rawJSON + JSON.parse source)`)
  }
  return rawJSON(canonical)
}

/**
 * 读取 JSON 响应时只把 requestBody 定价条件对象中的数字 equals 原文保留为 {@link RawJsonNumber}。
 *
 * <p>reviver 在数字阶段只暂存 `context.source`，不改变数值；当 key 为 `requestBody` 且对象确实带文本 path 与数字 equals 时，
 * 才把 equals 替换成内部 sentinel。其它 `{path, equals}`、普通 `{rawJson}` / `{rawJSON}` 对象以及普通 JSON 完全不变。
 * 缺少 source access 时只对数字 requestBody 条件显式失败，绝不 Number() 舍入。
 */
export function parseJsonPreservingPathEqualsNumbers(text: string): unknown {
  type Reviver = (
    this: Record<string, unknown> | undefined,
    key: string,
    value: unknown,
    context?: { source?: string },
  ) => unknown
  const sources = new WeakMap<object, string>()
  const reviver: Reviver = function (key, value, context) {
    if (typeof value === 'number' && this && key === 'equals' && typeof this.path === 'string') {
      if (typeof context?.source === 'string') {
        sources.set(this, context.source)
      }
      return value
    }
    if (key === 'requestBody' && this
      && typeof value === 'object' && value !== null && !Array.isArray(value)) {
      const condition = value as Record<string, unknown>
      if (typeof condition.path === 'string' && typeof condition.equals === 'number') {
        const source = sources.get(condition)
        if (source === undefined) {
          throw new Error(
            'JSON.parse source text access is required to preserve exact JSON numbers',
          )
        }
        condition.equals = { rawJson: source }
      }
    }
    return value
  }
  return (JSON.parse as unknown as (input: string, parseReviver: Reviver) => unknown)(text, reviver)
}
