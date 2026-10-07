import { describe, expect, it } from 'vitest'
import { formatTimestamp, timestampMillis } from '@/features/ai/environment/environment-utils'

/** 同一时刻的 wire 形态：后端数字时间戳是 epoch 秒（StrictJackson 可带小数），也可能是数字毫秒。 */
const ISO = '2026-07-20T00:00:10.250Z'
const EPOCH_MILLIS = 1784505610250
const EPOCH_SECONDS = 1784505610.25

describe('timestampMillis', () => {
  // 测试意图：数字时间戳按 epoch 秒解释，否则毫秒时间戳会把截止点算到过去。
  it('converts numeric epoch seconds, including fractions, to milliseconds', () => {
    expect(timestampMillis(EPOCH_SECONDS)).toBe(EPOCH_MILLIS)
    expect(timestampMillis(1784505610)).toBe(1784505610000)
  })

  // 测试意图：已经是毫秒量级的数值保持不变。
  it('keeps numeric milliseconds unchanged', () => {
    expect(timestampMillis(EPOCH_MILLIS)).toBe(EPOCH_MILLIS)
  })

  // 测试意图：数字字符串与 ISO 字符串按同一时刻解析。
  it('parses numeric strings and ISO strings', () => {
    expect(timestampMillis(String(EPOCH_SECONDS))).toBe(EPOCH_MILLIS)
    expect(timestampMillis(String(EPOCH_MILLIS))).toBe(EPOCH_MILLIS)
    expect(timestampMillis(ISO)).toBe(EPOCH_MILLIS)
  })

  // 测试意图：空值和无法解析的值统一为 null，调用方无需再区分具体形态。
  it('returns null for empty or unparsable values', () => {
    expect(timestampMillis(null)).toBeNull()
    expect(timestampMillis(undefined)).toBeNull()
    expect(timestampMillis('')).toBeNull()
    expect(timestampMillis('  ')).toBeNull()
    expect(timestampMillis('not-a-time')).toBeNull()
    expect(timestampMillis(Number.NaN)).toBeNull()
    expect(timestampMillis(Number.POSITIVE_INFINITY)).toBeNull()
  })
})

describe('formatTimestamp', () => {
  // 测试意图：同一时刻的各 wire 形态显示一致，页面展示不受后端序列化形态影响。
  it('renders every supported representation of the same instant identically', () => {
    const rendered = formatTimestamp(ISO, 'en-US')
    expect(rendered).not.toBe('')
    expect(formatTimestamp(EPOCH_SECONDS, 'en-US')).toBe(rendered)
    expect(formatTimestamp(EPOCH_MILLIS, 'en-US')).toBe(rendered)
    expect(formatTimestamp(String(EPOCH_SECONDS), 'en-US')).toBe(rendered)
  })

  // 测试意图：缺值不渲染，非法值保留原文便于暴露服务端给出了非时间值。
  it('renders empty values as empty and keeps unparsable text as-is', () => {
    expect(formatTimestamp(null, 'en-US')).toBe('')
    expect(formatTimestamp(undefined, 'en-US')).toBe('')
    expect(formatTimestamp('', 'en-US')).toBe('')
    expect(formatTimestamp('  ', 'en-US')).toBe('')
    expect(formatTimestamp('not-a-time', 'en-US')).toBe('not-a-time')
  })
})
