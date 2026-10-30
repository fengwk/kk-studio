/**
 * request-body 定价条件的精确 JSON 标量编解码。
 *
 * <p>通用精确 JSON number 能力位于 `shared/lib/lossless-json-number.ts`；本文件只定义定价条件的标量语义。
 */

import {
  canonicalJsonNumber,
  isRawJsonNumber,
  parseJsonPreservingPathEqualsNumbers,
  rawJsonNumberText,
  supportsLosslessJsonNumbers,
  wireJsonNumber,
  type ExactJsonNumber,
} from '@/shared/lib/lossless-json-number'

export { isRawJsonNumber }
export type { ExactJsonNumber }

export type PricingScalar = string | boolean | null | ExactJsonNumber

export type PricingScalarKind = 'string' | 'number' | 'boolean' | 'null'

/** 条件值类型；number 同时接受已解析的 JS number（仅用于可信测试/旧数据）与精确原文。 */
export function scalarKind(value: unknown): PricingScalarKind | undefined {
  if (value === null) return 'null'
  if (typeof value === 'string') return 'string'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number' || isRawJsonNumber(value)) return 'number'
  return undefined
}

/** 把编辑/读取到的标量规范化为可精确写出的 DTO 值。 */
export function normalizePricingScalar(value: unknown, field: string): PricingScalar {
  const kind = scalarKind(value)
  switch (kind) {
    case 'null':
      return null
    case 'string':
      return value as string
    case 'boolean':
      return value as boolean
    case 'number': {
      const raw = isRawJsonNumber(value) ? rawJsonNumberText(value) : String(value as number)
      const canonical = canonicalJsonNumber(raw)
      if (!supportsLosslessJsonNumbers()) {
        throw new Error(`${field} requires exact JSON number support (JSON.rawJSON + JSON.parse source)`)
      }
      return { rawJson: canonical }
    }
    default:
      throw new Error(`${field} must be a JSON string, number, boolean or null`)
  }
}

/** 写出时把精确原文变成 JSON number；不支持的运行时就地失败，绝不悄悄写字符串或舍入值。 */
export function wirePricingScalar(value: PricingScalar, field: string): unknown {
  if (!isRawJsonNumber(value)) {
    return value
  }
  return wireJsonNumber(value, field)
}

/** UI 摘要：数字仍显示精确原文，字符串用 JSON 引号避免歧义。 */
export function pricingScalarText(value: PricingScalar): string {
  if (value === null) return 'null'
  if (typeof value === 'string') return JSON.stringify(value)
  if (typeof value === 'boolean') return String(value)
  return rawJsonNumberText(value)
}

/** 读取模型/定价响应时保留 requestBody.equals 的 number 原文。 */
export function parseJsonPreservingPricingScalars(text: string): unknown {
  return parseJsonPreservingPathEqualsNumbers(text)
}
