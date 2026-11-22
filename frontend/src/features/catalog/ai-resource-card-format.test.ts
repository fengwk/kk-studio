import { act } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { setLocale } from '@/shared/i18n'
import {
  formatCompactList,
  formatDurationMillis,
} from '@/features/catalog/ai-resource-card-format'

describe('ai-resource-card-format', () => {
  it('compacts long lists with +N', () => {
    expect(formatCompactList(['a', 'b', 'c'], 3)).toBe('a, b, c')
    expect(formatCompactList(['a', 'b', 'c', 'd', 'e'], 2)).toBe('a, b +3')
    expect(formatCompactList([], 2)).toBe('—')
    expect(formatCompactList(null)).toBe('—')
    expect(formatCompactList([null, ' ', 'a'])).toBe('a')
    expect(formatCompactList([], 2, '')).toBe('')
  })

  it('renders whole seconds as seconds and everything else as milliseconds', () => {
    // 意图：Provider 与 MCP 卡共用这一条规则，整秒值按秒显示、非整秒保留毫秒，
    // 且不做四舍五入，避免把 61.5 秒说成 62 秒。
    expect(formatDurationMillis('180000')).toBe('180 秒')
    expect(formatDurationMillis(30_000)).toBe('30 秒')
    expect(formatDurationMillis('45000')).toBe('45 秒')
    expect(formatDurationMillis(1500)).toBe('1500 毫秒')
    expect(formatDurationMillis(61_500)).toBe('61500 毫秒')
  })

  it('keeps empty and unparsable durations honest', () => {
    expect(formatDurationMillis(null)).toBe('—')
    expect(formatDurationMillis(undefined)).toBe('—')
    expect(formatDurationMillis('')).toBe('—')
    expect(formatDurationMillis('not-a-number')).toBe('not-a-number')
  })

  it('keeps the same unit spacing in en-US', () => {
    act(() => setLocale('en-US'))
    expect(formatDurationMillis(30_000)).toBe('30s')
    expect(formatDurationMillis(1500)).toBe('1500ms')
    expect(formatDurationMillis(null)).toBe('—')
    act(() => setLocale('zh-CN'))
  })
})
