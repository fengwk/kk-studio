import { describe, expect, it } from 'vitest'
import { formatByteCount } from '@/shared/lib/format-bytes'

describe('formatByteCount', () => {
  // 测试意图：wire 字符串与本地 number 输入共用同一套 1024 进制展示，整数结果不显示 .0。
  it('formats strings and numbers with the same 1024-based units', () => {
    expect(formatByteCount('0')).toBe('0 B')
    expect(formatByteCount(0)).toBe('0 B')
    expect(formatByteCount('1023')).toBe('1023 B')
    expect(formatByteCount('1024')).toBe('1 KB')
    expect(formatByteCount('2048')).toBe('2 KB')
    expect(formatByteCount(2048)).toBe('2 KB')
    expect(formatByteCount('8388608')).toBe('8 MB')
    expect(formatByteCount(30 * 1024 * 1024)).toBe('30 MB')
  })

  // 测试意图：大容量继续向 GB/TB 进位，而不是在 MB 处停在巨大的数字上。
  it('continues into GB and TB', () => {
    expect(formatByteCount('1073741824')).toBe('1 GB')
    expect(formatByteCount(String(5 * 1024 ** 4))).toBe('5 TB')
    // 保留一位小数，但去掉多余的 .0。
    expect(formatByteCount('1610612736')).toBe('1.5 GB')
    expect(formatByteCount('1536')).toBe('1.5 KB')
  })

  // 测试意图：非法或不安全的 wire 值保持原文，绝不把未知值伪装成具体容量。
  it('preserves malformed or unsafe values verbatim', () => {
    expect(formatByteCount('not-a-number')).toBe('not-a-number')
    expect(formatByteCount('-1')).toBe('-1')
    expect(formatByteCount('1.5')).toBe('1.5')
    expect(formatByteCount(String(Number.MAX_SAFE_INTEGER + 2))).toBe('9007199254740992')
    expect(formatByteCount(Number.NaN)).toBe('NaN')
    expect(formatByteCount(-1)).toBe('-1')
  })
})
