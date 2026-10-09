import { describe, expect, it } from 'vitest'
import {
  addDecimalStrings,
  calculateCacheHitRate,
  calculateDecodeTokensPerSecond,
  formatTurnUsageText,
  formatUsageCost,
  mergeTurnUsage,
  normalizeUsageCost,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'

function usage(overrides: Partial<TurnUsage> = {}): TurnUsage {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    reasoning: 0,
    providerTotal: 0,
    cost: null,
    ...overrides,
  }
}

describe('content-utils usage & speed & cache calculations', () => {
  // 验证缓存命中率公式：cacheRead / (input + cacheRead + cacheWrite含long)
  // 分母为 0 时返回 null，展示为 "—" 而非假 0
  describe('calculateCacheHitRate', () => {
    it('returns null when denominator is 0', () => {
      expect(calculateCacheHitRate({ input: 0, cacheRead: 0, cacheWrite: 0 })).toBeNull()
    })

    it('calculates correct rounded percentage when denominator is positive', () => {
      expect(calculateCacheHitRate({ input: 200, cacheRead: 100, cacheWrite: 100 })).toBe(25)
      expect(calculateCacheHitRate({ input: 1, cacheRead: 1, cacheWrite: 1 })).toBe(33)
    })
  })

  // 验证解码速率：仅有效样本（有限非负 token + 有限正 duration）参与，无样本返回 null
  describe('calculateDecodeTokensPerSecond', () => {
    it('returns null when there is no valid sample', () => {
      expect(calculateDecodeTokensPerSecond({})).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: null, decodeTokens: 100 })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 0, decodeTokens: 100 })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 1000, decodeTokens: null })).toBeNull()
    })

    // 坏样本：负数/NaN/Infinity token 与非有限 duration 一律不进入分子分母，杜绝 Infinity 速率
    it('rejects bad samples without producing Infinity or NaN', () => {
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 1000, decodeTokens: -1 })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 1000, decodeTokens: Number.NaN })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 1000, decodeTokens: Number.POSITIVE_INFINITY })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: Number.POSITIVE_INFINITY, decodeTokens: 100 })).toBeNull()
    })

    it('rounds to integer at rate >= 10 and keeps one decimal below 10', () => {
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 2000, decodeTokens: 100 })).toBe(50)
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 2000, decodeTokens: 5 })).toBe(2.5)
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 1000, decodeTokens: 3 })).toBe(3)
    })
  })

  // 只接受 harness 持久 codec 的 canonical 字段名：snake_case / 常见别名一律不再兼容
  describe('parseAssistantUsage canonical fields', () => {
    it('parses canonical usage with cost from the read projection', () => {
      const parsed = parseAssistantUsage(
        {
          usage: {
            inputTokens: 100,
            outputTokens: 50,
            cacheReadTokens: 10,
            cacheWriteTokens: 20,
            cacheWriteLongTokens: 5,
            reasoningTokens: 15,
            providerTotalTokens: 200,
          },
          decodeDurationMillis: 1000,
        },
        { currency: 'USD', amount: '0.000500000000' },
      )
      expect(parsed).toEqual({
        input: 100,
        output: 50,
        cacheRead: 10,
        cacheWrite: 25, // 20 + 5 long
        reasoning: 15,
        providerTotal: 200,
        cost: { currency: 'USD', amount: '0.000500000000' },
        decodeTokens: 65, // 50 output + 15 reasoning
        decodeDurationMillis: 1000,
        contextInputTokens: 135, // 100 + 10 + 25
      })
    })

    it('ignores snake_case and common aliases entirely', () => {
      const parsed = parseAssistantUsage({
        usage: {
          input_tokens: '10',
          completionTokens: 20,
          cachedTokens: 3,
          cache_write_tokens: '4',
          totalTokens: 44,
        },
      })
      expect(parsed).toBeNull()
    })

    // 旧 payload 里的 metadata.cost 不再被读取：费用只能来自读取投影
    it('never prices from assistant metadata', () => {
      const parsed = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20 },
        cost: 0.25,
        costTotal: '0.5',
      })
      expect(parsed?.cost).toBeNull()
    })

    it('accepts only positive safe integer decodeDurationMillis', () => {
      const valid = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20 },
        decodeDurationMillis: 800,
      })
      expect(valid?.decodeDurationMillis).toBe(800)
      expect(valid?.decodeTokens).toBe(20)

      for (const bad of ['800', 0, -5, 1.5, Number.POSITIVE_INFINITY, Number.NaN, 2 ** 53]) {
        const parsed = parseAssistantUsage({
          usage: { inputTokens: 10, outputTokens: 20 },
          decodeDurationMillis: bad,
        })
        expect(parsed?.decodeDurationMillis).toBeNull()
        expect(parsed?.decodeTokens).toBeNull()
      }
      const aliased = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20 },
        decode_duration_millis: 800,
      })
      expect(aliased?.decodeDurationMillis).toBeNull()
    })
  })

  // normalizeUsageCost：只接受 currency + 精确十进制 amount，其余一律视为未定价（null）
  describe('normalizeUsageCost', () => {
    it('accepts a well-formed projection and rejects malformed ones', () => {
      expect(normalizeUsageCost({ currency: 'USD', amount: '0.5' }))
        .toEqual({ currency: 'USD', amount: '0.5' })
      expect(normalizeUsageCost({ currency: '', amount: '0.5' })).toBeNull()
      expect(normalizeUsageCost({ currency: 'USD', amount: 'abc' })).toBeNull()
      expect(normalizeUsageCost({ currency: 'USD', amount: '-1' })).toBeNull()
      expect(normalizeUsageCost(null)).toBeNull()
      expect(normalizeUsageCost(undefined)).toBeNull()
    })
  })

  // 精确十进制求和：无浮点误差，非法输入返回 null
  describe('addDecimalStrings', () => {
    it('sums exactly across different scales', () => {
      expect(addDecimalStrings('0.1', '0.2')).toBe('0.3')
      expect(addDecimalStrings('0.0000004', '0.000000400000')).toBe('0.0000008')
      expect(addDecimalStrings('1', '0.000000000001')).toBe('1.000000000001')
      expect(addDecimalStrings('99999999999999999999', '1')).toBe('100000000000000000000')
    })

    it('returns null for invalid operands', () => {
      expect(addDecimalStrings('abc', '1')).toBeNull()
      expect(addDecimalStrings('1', '')).toBeNull()
    })
  })

  // 费用展示：先精确求和后统一 6 位精度；<1e-6 的精确非零金额显示 <$0.000001（"<" 在货币符号之前）
  describe('formatUsageCost', () => {
    it('rounds to at most 6 decimals and trims trailing zeros', () => {
      expect(formatUsageCost({ currency: 'USD', amount: '0.500000000000' })).toBe('$0.5')
      expect(formatUsageCost({ currency: 'USD', amount: '0.125' })).toBe('$0.125')
      expect(formatUsageCost({ currency: 'USD', amount: '1.99999951' })).toBe('$2')
      expect(formatUsageCost({ currency: 'USD', amount: '0' })).toBe('$0')
    })

    it('keeps exact 0 as 0 but shows any sub-threshold amount as <$0.000001', () => {
      expect(formatUsageCost({ currency: 'USD', amount: '0.000001' })).toBe('$0.000001')
      // 9e-7 精确小于 1e-6：不能被四舍五入夸大成 0.000001
      expect(formatUsageCost({ currency: 'USD', amount: '0.0000009' })).toBe('<$0.000001')
      expect(formatUsageCost({ currency: 'USD', amount: '0.0000004' })).toBe('<$0.000001')
      expect(formatUsageCost({ currency: 'USD', amount: '0.000000000001' })).toBe('<$0.000001')
    })

    it('falls back to the currency code for unknown currencies and never fabricates 0', () => {
      // 未知/自定义币种按 "CODE 金额" 前缀如实呈现，绝不丢币种也绝不当成 $0
      expect(formatUsageCost({ currency: 'XYZ', amount: '0.25' })).toBe('XYZ 0.25')
      expect(formatUsageCost({ currency: 'XYZ', amount: '0.0000001' })).toBe('<XYZ 0.000001')
      expect(formatUsageCost(null)).toBeNull()
      expect(formatUsageCost({ currency: '', amount: '0.25' })).toBeNull()
    })
  })

  // mergeTurnUsage：累加消耗与有效测速样本，contextInputTokens 采用 latest，费用精确求和
  describe('mergeTurnUsage', () => {
    it('merges two TurnUsage records with speed, context and exact cost', () => {
      const first = usage({
        input: 100, output: 30, cacheRead: 20, cacheWrite: 10, reasoning: 5, providerTotal: 165,
        cost: { currency: 'USD', amount: '0.1' },
        decodeTokens: 35, decodeDurationMillis: 700, contextInputTokens: 130,
      })
      const second = usage({
        input: 150, output: 40, cacheRead: 50, cacheWrite: 0, reasoning: 10, providerTotal: 250,
        cost: { currency: 'USD', amount: '0.2' },
        decodeTokens: 50, decodeDurationMillis: 1000, contextInputTokens: 200,
      })

      const merged = mergeTurnUsage(first, second)
      expect(merged.cost).toEqual({ currency: 'USD', amount: '0.3' })
      expect(merged.input).toBe(250)
      expect(merged.output).toBe(70)
      expect(merged.cacheRead).toBe(70)
      expect(merged.reasoning).toBe(15)
      expect(merged.decodeTokens).toBe(85)
      expect(merged.decodeDurationMillis).toBe(1700)
      expect(merged.contextInputTokens).toBe(200)
    })

    // 缺失或跨币种时不得伪造完整总额
    it('drops the total when a part is unpriced or in another currency', () => {
      const priced = usage({ cost: { currency: 'USD', amount: '0.1' } })
      expect(mergeTurnUsage(priced, usage({ cost: null })).cost).toBeNull()
      expect(mergeTurnUsage(priced, usage({ cost: { currency: 'EUR', amount: '0.1' } })).cost).toBeNull()
    })

    it('ignores invalid speed samples when merging', () => {
      const invalid = usage({ decodeTokens: Number.POSITIVE_INFINITY, decodeDurationMillis: 100 })
      const valid = usage({ decodeTokens: 5, decodeDurationMillis: 200 })
      const merged = mergeTurnUsage(invalid, valid)
      expect(merged.decodeTokens).toBe(5)
      expect(merged.decodeDurationMillis).toBe(200)
    })

    it('preserves existing speed if next call has no measurement', () => {
      const merged = mergeTurnUsage(
        usage({ decodeTokens: 5, decodeDurationMillis: 200, contextInputTokens: 10 }),
        usage({ decodeTokens: null, decodeDurationMillis: null, contextInputTokens: 20 }),
      )
      expect(merged.decodeTokens).toBe(5)
      expect(merged.decodeDurationMillis).toBe(200)
      expect(merged.contextInputTokens).toBe(20)
    })
  })

  // 缺失指标的展示默认值不改变原始 usage facts。
  describe('formatTurnUsageText', () => {
    it('formats turn usage with cache, cost and tok/s', () => {
      const text = formatTurnUsageText({
        input: 100,
        output: 50,
        cacheRead: 50,
        cacheWrite: 0,
        cost: { currency: 'USD', amount: '0.005' },
        decodeTokens: 50,
        decodeDurationMillis: 1000,
      })
      expect(text).toBe('↑100 · ↓50 · R50 · W0 · $0.005 · cache 33% · 50 tok/s')
    })

    it('defaults missing metrics to zero without assuming a currency', () => {
      expect(formatTurnUsageText({
        input: 0, output: 0, cacheRead: 0, cacheWrite: 0, cost: null,
      })).toBe('↑0 · ↓0 · R0 · W0 · 0 · cache 0% · 0 tok/s')
    })

    it('keeps the same fields when only cache write has usage', () => {
      expect(formatTurnUsageText({
        input: 100, output: 50, cacheRead: 0, cacheWrite: 20,
        cost: { currency: 'USD', amount: '0.005' },
      })).toBe('↑100 · ↓50 · R0 · W20 · $0.005 · cache 0% · 0 tok/s')
    })
  })
})
