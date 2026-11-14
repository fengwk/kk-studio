import { describe, expect, it } from 'vitest'
import {
  addDecimalStrings,
  formatUsageCost,
  normalizeUsageCost,
} from '@/features/thread/runtime/thread-timeline/usage-cost'

describe('usage cost projection', () => {
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
})
