import { describe, expect, it } from 'vitest'
import {
  calculateCacheHitRate,
  calculateDecodeTokensPerSecond,
  formatTurnUsageText,
  mergeTurnUsage,
  parseAssistantUsage,
} from '@/features/ai/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'

describe('content-utils usage & speed & cache calculations', () => {
  // 验证缓存命中率公式：cacheRead / (input + cacheRead + cacheWrite含long)
  // 分母为 0 时返回 null，展示为 "—" 而非假 0
  describe('calculateCacheHitRate', () => {
    it('returns null when denominator is 0', () => {
      expect(calculateCacheHitRate({ input: 0, cacheRead: 0, cacheWrite: 0 })).toBeNull()
    })

    it('calculates correct rounded percentage when denominator is positive', () => {
      // 100 cacheRead / (200 input + 100 cacheRead + 100 cacheWrite) = 100 / 400 = 25%
      expect(
        calculateCacheHitRate({
          input: 200,
          cacheRead: 100,
          cacheWrite: 100,
        }),
      ).toBe(25)

      // 1 / 3 = 33%
      expect(
        calculateCacheHitRate({
          input: 1,
          cacheRead: 1,
          cacheWrite: 1,
        }),
      ).toBe(33)
    })
  })

  // 验证解码速率：仅有效样本 (duration > 0 && decodeTokens >= 0) 参与，无样本返回 null
  describe('calculateDecodeTokensPerSecond', () => {
    it('returns null when decodeDurationMillis is missing, null, or zero', () => {
      expect(calculateDecodeTokensPerSecond({})).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: null, decodeTokens: 100 })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 0, decodeTokens: 100 })).toBeNull()
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: -10, decodeTokens: 100 })).toBeNull()
    })

    it('returns rounded tok/s when valid sample exists', () => {
      // 100 tokens in 2000ms = 50 tok/s
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 2000, decodeTokens: 100 })).toBe(50)
      // 40 tokens in 750ms = 53.33 -> 53 tok/s
      expect(calculateDecodeTokensPerSecond({ decodeDurationMillis: 750, decodeTokens: 40 })).toBe(53)
    })
  })

  // 验证 parseAssistantUsage 能防御兼容 wire number/string，严格有限正 duration 解析
  describe('parseAssistantUsage duration and decode tokens', () => {
    it('parses valid numeric decodeDurationMillis and computes decodeTokens', () => {
      const usage = parseAssistantUsage({
        usage: {
          inputTokens: 100,
          outputTokens: 50,
          cacheReadTokens: 10,
          cacheWriteTokens: 20,
          reasoningTokens: 15,
        },
        cost: 0.05,
        decodeDurationMillis: 1000,
      })
      expect(usage).toEqual({
        input: 100,
        output: 50,
        cacheRead: 10,
        cacheWrite: 20,
        reasoning: 15,
        providerTotal: 0,
        cost: 0.05,
        decodeTokens: 65, // 50 output + 15 reasoning
        decodeDurationMillis: 1000,
        contextInputTokens: 130, // 100 + 10 + 20
      })
    })

    it('parses valid string decodeDurationMillis safely and filters non-positive', () => {
      const usageWithString = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20 },
        cost: 0.01,
        decodeDurationMillis: ' 800 ',
      })
      expect(usageWithString?.decodeDurationMillis).toBe(800)
      expect(usageWithString?.decodeTokens).toBe(20)

      const usageWithInvalid = parseAssistantUsage({
        usage: { inputTokens: 10, outputTokens: 20 },
        cost: 0.01,
        decodeDurationMillis: 'not-a-number',
      })
      expect(usageWithInvalid?.decodeDurationMillis).toBeNull()
      expect(usageWithInvalid?.decodeTokens).toBeNull()
    })
  })

  // 验证 mergeTurnUsage：累加消耗与有效测速样本，contextInputTokens 采用 latest
  describe('mergeTurnUsage', () => {
    it('merges two TurnUsage records with speed and context updates', () => {
      const first: TurnUsage = {
        input: 100,
        output: 30,
        cacheRead: 20,
        cacheWrite: 10,
        reasoning: 5,
        providerTotal: 165,
        cost: 0.01,
        decodeTokens: 35,
        decodeDurationMillis: 700,
        contextInputTokens: 130,
      }
      const second: TurnUsage = {
        input: 150,
        output: 40,
        cacheRead: 50,
        cacheWrite: 0,
        reasoning: 10,
        providerTotal: 250,
        cost: 0.02,
        decodeTokens: 50,
        decodeDurationMillis: 1000,
        contextInputTokens: 200,
      }

      const merged = mergeTurnUsage(first, second)
      expect(merged.cost).toBeCloseTo(0.03, 10)
      expect(merged.input).toBe(250)
      expect(merged.output).toBe(70)
      expect(merged.cacheRead).toBe(70)
      expect(merged.cacheWrite).toBe(10)
      expect(merged.reasoning).toBe(15)
      expect(merged.providerTotal).toBe(415)
      expect(merged.decodeTokens).toBe(85)
      expect(merged.decodeDurationMillis).toBe(1700)
      expect(merged.contextInputTokens).toBe(200)
    })

    it('preserves existing speed if next call has no measurement', () => {
      const first: TurnUsage = {
        input: 10,
        output: 5,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: 0,
        providerTotal: 15,
        cost: 0.001,
        decodeTokens: 5,
        decodeDurationMillis: 200,
        contextInputTokens: 10,
      }
      const second: TurnUsage = {
        input: 20,
        output: 10,
        cacheRead: 0,
        cacheWrite: 0,
        reasoning: 0,
        providerTotal: 30,
        cost: 0.002,
        decodeTokens: null,
        decodeDurationMillis: null,
        contextInputTokens: 20,
      }
      const merged = mergeTurnUsage(first, second)
      expect(merged.decodeTokens).toBe(5)
      expect(merged.decodeDurationMillis).toBe(200)
      expect(merged.contextInputTokens).toBe(20)
    })
  })

  // 验证每回合 meta usage 字符串格式化规范：包含 cache 率与 tok/s，零/空态展示 "—"
  describe('formatTurnUsageText', () => {
    it('formats turn usage with cache and tok/s', () => {
      const text = formatTurnUsageText({
        input: 100,
        output: 50,
        cacheRead: 50,
        cacheWrite: 0,
        cost: 0.005,
        decodeTokens: 50,
        decodeDurationMillis: 1000,
      })
      // 50 / (100 + 50 + 0) = 33%
      // 50 * 1000 / 1000 = 50 tok/s
      expect(text).toBe('↑100 · ↓50 · R50 · $0.005 · cache 33% · 50 tok/s')
    })

    it('formats empty cache rate and speed as em dash placeholders', () => {
      const text = formatTurnUsageText({
        input: 0,
        output: 0,
        cacheRead: 0,
        cacheWrite: 0,
        cost: 0,
      })
      expect(text).toBe('↑0 · ↓0 · $0.000 · cache — · — tok/s')
    })
  })
})
