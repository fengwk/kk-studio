import { describe, expect, it } from 'vitest'
import {
  accumulateTurnUsage,
  addDecimalStrings,
  addNullableSafeInteger,
  calculateCacheHitRate,
  calculateDecodeTokensPerSecond,
  formatTurnUsageText,
  formatUsageCost,
  mergeTurnUsage,
  normalizeUsageCost,
  parseAssistantUsage,
} from '@/features/chat/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/chat/runtime/thread-timeline-types'

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
  // 验证缓存命中率公式：cacheRead / (input + cacheRead + cacheWrite)
  // 函数只返回未取整的百分数；两位小数由展示层统一处理。分母为 0 时返回 null。
  describe('calculateCacheHitRate', () => {
    it('does not turn an overflowing cache denominator into a measured zero', () => {
      const tokens = { input: Number.MAX_SAFE_INTEGER, output: 0, cacheRead: 1, cacheWrite: 0, cost: null }
      expect(calculateCacheHitRate(tokens)).toBeNull()
      expect(formatTurnUsageText(tokens)).toContain('cache 暂无数据')
    })
    it('returns null when denominator is 0', () => {
      expect(calculateCacheHitRate({ input: 0, cacheRead: 0, cacheWrite: 0 })).toBeNull()
    })

    it('calculates the unrounded percentage when denominator is positive', () => {
      expect(calculateCacheHitRate({ input: 200, cacheRead: 100, cacheWrite: 100 })).toBeCloseTo(25)
      expect(calculateCacheHitRate({ input: 1, cacheRead: 1, cacheWrite: 1 })).toBeCloseTo(100 / 3)
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
    it('counts output and cache writes once, ignoring the obsolete long-cache alias', () => {
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
        cacheWrite: 20,
        reasoning: 15,
        providerTotal: 200,
        cost: { currency: 'USD', amount: '0.000500000000' },
        decodeTokens: 50,
        decodeDurationMillis: 1000,
        contextInputTokens: 130,
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
      // 携带模型 metadata 但缺 canonical 字段：返回全 unknown TurnUsage，不采信别名数字
      expect(parsed).toEqual({
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
      })
    })

    // 旧 payload 里的 metadata.cost 不再被读取：费用只能来自读取投影
    it('never prices from assistant metadata', () => {
      const parsed = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
        cost: 0.25,
        costTotal: '0.5',
      })
      expect(parsed?.cost).toBeNull()
    })

    it('requires the four non-negative safe integer fields and returns null for no metadata, unknown for invalid', () => {
      // 只有字段缺失才是无模型调用；显式空对象、数组和 primitive 不能漏计。
      expect(parseAssistantUsage(null)).toBeNull()
      expect(parseAssistantUsage(undefined)).toBeNull()
      for (const malformed of [{}, [], false, 0, 'metadata']) {
        expect(parseAssistantUsage(malformed)).toMatchObject({
          input: null, output: null, cacheRead: null, cacheWrite: null, cost: null,
        })
      }

      const unknownUsage = {
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

      // 有模型 metadata 但缺 usage 或字段非法：返回全 unknown TurnUsage（不创造真 0）
      expect(parseAssistantUsage({ usage: null })).toEqual(unknownUsage)
      expect(parseAssistantUsage({ usage: {} })).toEqual(unknownUsage)
      expect(parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20, cacheReadTokens: 5 }, // 缺少 cacheWriteTokens
      })).toEqual(unknownUsage)
      expect(parseAssistantUsage({
        usage: { inputTokens: -1, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
      })).toEqual(unknownUsage)
      expect(parseAssistantUsage({
        usage: { inputTokens: 1.5, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
      })).toEqual(unknownUsage)
      expect(parseAssistantUsage({
        usage: { inputTokens: '10', outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
      })).toEqual(unknownUsage)

      // 四必填真实全零也必须保留；缺费用/测速不是缺 usage。
      expect(parseAssistantUsage({
        usage: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
      })).toMatchObject({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0, cost: null })

      // 携带真实 0 费用或有效指标时正常保留
      const zeroWithCost = parseAssistantUsage(
        {
          usage: { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
        },
        { currency: 'USD', amount: '0' },
      )
      expect(zeroWithCost).toEqual({
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: null,
        providerTotal: null,
        cost: { currency: 'USD', amount: '0' },
        decodeTokens: null,
        decodeDurationMillis: null,
        contextInputTokens: 0,
      })
    })

    it('treats reasoningTokens and providerTotalTokens as nullable unknown', () => {
      const parsed = parseAssistantUsage({
        usage: {
          inputTokens: 10,
          outputTokens: 20,
          cacheReadTokens: 0,
          cacheWriteTokens: 0,
          reasoningTokens: 'bad',
        },
      })
      expect(parsed?.reasoning).toBeNull()
      expect(parsed?.providerTotal).toBeNull()
    })

    it('accepts only positive safe integer decodeDurationMillis', () => {
      const valid = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
        decodeDurationMillis: 800,
      })
      expect(valid?.decodeDurationMillis).toBe(800)
      expect(valid?.decodeTokens).toBe(20)

      for (const bad of ['800', 0, -5, 1.5, Number.POSITIVE_INFINITY, Number.NaN, 2 ** 53]) {
        const parsed = parseAssistantUsage({
          usage: { inputTokens: 10, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
          decodeDurationMillis: bad,
        })
        expect(parsed?.decodeDurationMillis).toBeNull()
        expect(parsed?.decodeTokens).toBeNull()
      }
      const aliased = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20, cacheReadTokens: 0, cacheWriteTokens: 0 },
        decode_duration_millis: 800,
      })
      expect(aliased?.decodeDurationMillis).toBeNull()
    })

    // known reasoning <= output 一致校验；invalid optional 不得真 0；合法 usage reasoning 未知且后端 cost 已知仍保留费用
    it('enforces known reasoning <= output and retains known cost when reasoning is unknown', () => {
      // 1. 合法 reasoning：reasoning <= output
      const validReasoning = parseAssistantUsage(
        {
          usage: { inputTokens: 100, outputTokens: 50, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: 30 },
        },
        { currency: 'USD', amount: '0.005' },
      )
      expect(validReasoning?.reasoning).toBe(30)
      expect(validReasoning?.cost).toEqual({ currency: 'USD', amount: '0.005' })

      // 2. 违反一致性：reasoning > output -> reasoning 变为 null（不得真 0），合法 usage 保留后端 cost
      const excessiveReasoning = parseAssistantUsage(
        {
          usage: { inputTokens: 100, outputTokens: 50, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: 60 },
        },
        { currency: 'USD', amount: '0.005' },
      )
      expect(excessiveReasoning?.reasoning).toBeNull()
      expect(excessiveReasoning?.cost).toEqual({ currency: 'USD', amount: '0.005' })

      // 3. invalid optional（负数、非整数、字符串）-> null，不得真 0，保留已知费用
      const negativeReasoning = parseAssistantUsage(
        {
          usage: { inputTokens: 100, outputTokens: 50, cacheReadTokens: 0, cacheWriteTokens: 0, reasoningTokens: -1 },
        },
        { currency: 'USD', amount: '0.005' },
      )
      expect(negativeReasoning?.reasoning).toBeNull()
      expect(negativeReasoning?.cost).toEqual({ currency: 'USD', amount: '0.005' })

      // 4. invalid providerTotalTokens 不得真 0
      const badProviderTotal = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 10, cacheReadTokens: 0, cacheWriteTokens: 0, providerTotalTokens: -5 },
      })
      expect(badProviderTotal?.providerTotal).toBeNull()
    })

    it('sets contextInputTokens to null on safe integer overflow', () => {
      const overflow = parseAssistantUsage({
        usage: {
          inputTokens: Number.MAX_SAFE_INTEGER,
          outputTokens: 10,
          cacheReadTokens: 1,
          cacheWriteTokens: 0,
        },
      })
      expect(overflow?.contextInputTokens).toBeNull()
    })
  })

  describe('addNullableSafeInteger', () => {
    it('adds two safe integers correctly', () => {
      expect(addNullableSafeInteger(10, 20)).toBe(30)
      expect(addNullableSafeInteger(0, 0)).toBe(0)
    })

    it('propagates null if either operand is null or undefined', () => {
      expect(addNullableSafeInteger(10, null)).toBeNull()
      expect(addNullableSafeInteger(null, 10)).toBeNull()
      expect(addNullableSafeInteger(null, null)).toBeNull()
      expect(addNullableSafeInteger(undefined, 10)).toBeNull()
    })

    it('returns null if either operand is not a non-negative safe integer', () => {
      expect(addNullableSafeInteger(-1, 10)).toBeNull()
      expect(addNullableSafeInteger(10, -5)).toBeNull()
      expect(addNullableSafeInteger(1.5, 10)).toBeNull()
      expect(addNullableSafeInteger(NaN, 10)).toBeNull()
      expect(addNullableSafeInteger(Infinity, 10)).toBeNull()
    })

    it('turns into null (unknown) on safe integer overflow', () => {
      expect(addNullableSafeInteger(Number.MAX_SAFE_INTEGER, 1)).toBeNull()
      expect(addNullableSafeInteger(Number.MAX_SAFE_INTEGER, 100)).toBeNull()
    })
  })

  describe('accumulateTurnUsage', () => {
    it('returns current when next is null', () => {
      const current = usage({ input: 10, output: 5 })
      expect(accumulateTurnUsage(current, null)).toBe(current)
    })

    it('returns next when current is null', () => {
      const next = usage({ input: 10, output: 5 })
      expect(accumulateTurnUsage(null, next)).toBe(next)
    })

    it('merges through mergeTurnUsage when both are non-null', () => {
      const first = usage({ input: 10, output: 5 })
      const second = usage({ input: 20, output: 10 })
      expect(accumulateTurnUsage(first, second)?.input).toBe(30)
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

    it('keeps reasoning and providerTotal as null if either is null', () => {
      const known = usage({ output: 20, reasoning: 10, providerTotal: 100 })
      const unknownReasoning = usage({ output: 20, reasoning: null, providerTotal: 50 })
      const merged1 = mergeTurnUsage(known, unknownReasoning)
      expect(merged1.reasoning).toBeNull()
      expect(merged1.providerTotal).toBe(150)

      const unknownTotal = usage({ output: 20, reasoning: 5, providerTotal: null })
      const merged2 = mergeTurnUsage(known, unknownTotal)
      expect(merged2.reasoning).toBe(15)
      expect(merged2.providerTotal).toBeNull()
    })

    // 定向用例：known + unknown 顺序双向合并（最小未知传播）
    it('merges known and unknown bidirectionally with identical unknown propagation', () => {
      const known: TurnUsage = {
        input: 100,
        output: 50,
        cacheRead: 20,
        cacheWrite: 10,
        reasoning: 15,
        providerTotal: 170,
        cost: { currency: 'USD', amount: '0.05' },
        decodeTokens: 50,
        decodeDurationMillis: 1000,
        contextInputTokens: 130,
      }
      const unknown: TurnUsage = {
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

      // 1. known + unknown
      const forward = mergeTurnUsage(known, unknown)
      // 2. unknown + known
      const backward = mergeTurnUsage(unknown, known)

      // 双向顺序下，所有计数与费用字段均变为 null（未知传播）
      for (const merged of [forward, backward]) {
        expect(merged.input).toBeNull()
        expect(merged.output).toBeNull()
        expect(merged.cacheRead).toBeNull()
        expect(merged.cacheWrite).toBeNull()
        expect(merged.reasoning).toBeNull()
        expect(merged.providerTotal).toBeNull()
        expect(merged.cost).toBeNull()
        // 有效测速样本保留
        expect(merged.decodeTokens).toBe(50)
        expect(merged.decodeDurationMillis).toBe(1000)
      }

      // 上下文输入取最新：forward 的 next 是 unknown，因此为 null；backward 的 next 是 known，因此为 130
      expect(forward.contextInputTokens).toBeNull()
      expect(backward.contextInputTokens).toBe(130)
    })

    // 定向用例：safeinteger合并溢出变未知
    it('turns tokens into null on safe integer overflow during merge', () => {
      const huge = usage({ input: Number.MAX_SAFE_INTEGER, output: 100 })
      const extra = usage({ input: 1, output: 50 })
      const merged = mergeTurnUsage(huge, extra)
      expect(merged.input).toBeNull() // 溢出变未知
      expect(merged.output).toBe(150) // 未溢出正常求和
    })

    // 定向用例：合并后 known reasoning <= output 一致校验
    it('enforces reasoning <= output on merged values', () => {
      // 即使单独两个调用自身满足 reasoning <= output，若合并后 reasoning > output 则置为 null
      const first = usage({ output: 20, reasoning: 18 })
      const second = usage({ output: 5, reasoning: 10 })
      const merged = mergeTurnUsage(first, second)
      // reasoning: 28, output: 25 -> 28 > 25 -> null（不得真 0）
      expect(merged.output).toBe(25)
      expect(merged.reasoning).toBeNull()
    })

    // 定向用例：decodeTokens/duration合并也须安全整数溢出null
    it('nulls decode measurement when merged sample overflows safe integer', () => {
      const s1 = usage({ decodeTokens: Number.MAX_SAFE_INTEGER, decodeDurationMillis: 500 })
      const s2 = usage({ decodeTokens: 1, decodeDurationMillis: 500 })
      const mergedTokensOverflow = mergeTurnUsage(s1, s2)
      expect(mergedTokensOverflow.decodeTokens).toBeNull()
      expect(mergedTokensOverflow.decodeDurationMillis).toBeNull()

      const d1 = usage({ decodeTokens: 10, decodeDurationMillis: Number.MAX_SAFE_INTEGER })
      const d2 = usage({ decodeTokens: 10, decodeDurationMillis: 1 })
      const mergedDurationOverflow = mergeTurnUsage(d1, d2)
      expect(mergedDurationOverflow.decodeTokens).toBeNull()
      expect(mergedDurationOverflow.decodeDurationMillis).toBeNull()
    })

    // 定向用例：合法 usage reasoning 未知且后端 cost 已知仍保留费用并正常相加
    it('retains merged cost when reasoning is unknown but costs are known', () => {
      const u1 = usage({
        output: 50,
        reasoning: null,
        cost: { currency: 'USD', amount: '0.005' },
      })
      const u2 = usage({
        output: 50,
        reasoning: 20,
        cost: { currency: 'USD', amount: '0.003' },
      })
      const merged = mergeTurnUsage(u1, u2)
      expect(merged.cost).toEqual({ currency: 'USD', amount: '0.008' })
      expect(merged.reasoning).toBeNull()
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
      expect(text).toBe('↑100 · ↓50 · R50 · W0 · $0.005 · cache 33.33% · 50 tok/s')
    })

    it('formats a quarter cache hit rate with two decimals and keeps missing speed as noData', () => {
      expect(formatTurnUsageText({
        input: 150, output: 50, cacheRead: 50, cacheWrite: 0,
        cost: { currency: 'USD', amount: '0.005' },
      }, (k) => k === 'ai.runtime.status.noData' ? '暂无数据' : k)).toBe(
        '↑150 · ↓50 · R50 · W0 · $0.005 · cache 25.00% · 暂无数据',
      )
    })

    it('shows noData for missing cost and speed while preserving real 0', () => {
      // 缺失费用和速率：显示 noData，不可显示 0
      expect(formatTurnUsageText({
        input: 0, output: 0, cacheRead: 0, cacheWrite: 0, cost: null,
      }, (k) => k === 'ai.runtime.status.noData' ? '暂无数据' : k)).toBe(
        '↑0 · ↓0 · R0 · W0 · 暂无数据 · cache 0.00% · 暂无数据',
      )
      // 英文 noData
      expect(formatTurnUsageText({
        input: 0, output: 0, cacheRead: 0, cacheWrite: 0, cost: null,
      }, (k) => k === 'ai.runtime.status.noData' ? 'No data' : k)).toBe(
        '↑0 · ↓0 · R0 · W0 · No data · cache 0.00% · No data',
      )

      // 真实 0：费用 $0，速率 0 tok/s，真实 0 保留
      expect(formatTurnUsageText({
        input: 0, output: 0, cacheRead: 0, cacheWrite: 0,
        cost: { currency: 'USD', amount: '0' },
        decodeTokens: 0,
        decodeDurationMillis: 1000,
      }, (k) => k === 'ai.runtime.status.noData' ? '暂无数据' : k)).toBe(
        '↑0 · ↓0 · R0 · W0 · $0 · cache 0.00% · 0 tok/s',
      )
    })

    it('keeps the same fields when only cache write has usage', () => {
      expect(formatTurnUsageText({
        input: 100, output: 50, cacheRead: 0, cacheWrite: 20,
        cost: { currency: 'USD', amount: '0.005' },
        decodeTokens: 50,
        decodeDurationMillis: 1000,
      })).toBe('↑100 · ↓50 · R0 · W20 · $0.005 · cache 0.00% · 50 tok/s')
    })

    // 定向用例：0 与未知不同；全未知 usage 在中英文下全部显示 noData，不创造真 0
    it('formats fully unknown usage as localized noData without inventing zero', () => {
      const unknownUsage = {
        input: null,
        output: null,
        cacheRead: null,
        cacheWrite: null,
        cost: null,
        decodeTokens: null,
        decodeDurationMillis: null,
      }
      // 中文
      expect(formatTurnUsageText(unknownUsage, (k) => k === 'ai.runtime.status.noData' ? '暂无数据' : k)).toBe(
        '↑暂无数据 · ↓暂无数据 · R暂无数据 · W暂无数据 · 暂无数据 · cache 暂无数据 · 暂无数据',
      )
      // 英文
      expect(formatTurnUsageText(unknownUsage, (k) => k === 'ai.runtime.status.noData' ? 'No data' : k)).toBe(
        '↑No data · ↓No data · RNo data · WNo data · No data · cache No data · No data',
      )
    })
  })
})
