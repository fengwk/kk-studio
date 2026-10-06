import { describe, expect, it } from 'vitest'
import {
  flattenThinkingText,
  projectThinkingLine,
} from '@/features/ai/runtime/thread-panel/messages/thinking-text'

describe('flattenThinkingText', () => {
  // 收起态展示必须把全部换行与连续空白合并为单个空格，不改动展开用的原文。
  it('collapses newlines and runs of whitespace into single spaces', () => {
    expect(flattenThinkingText('first\n\nsecond   third\n\tfourth')).toBe(
      'first second third fourth',
    )
  })

  it('trims surrounding whitespace and returns empty for blank input', () => {
    expect(flattenThinkingText('  \n\n  ')).toBe('')
  })
})

describe('projectThinkingLine', () => {
  // 度量按字符数计算，边界完全确定；不依赖固定字符数实现，只验证按宽度取后缀。
  const measure = (text: string) => text.length

  it('keeps the whole line when it fits without any ellipsis', () => {
    expect(projectThinkingLine('short thinking', 100, measure)).toEqual({
      text: 'short thinking',
      truncated: false,
    })
  })

  // 前文放不下时保留最新尾部，并在左侧标记省略。
  it('keeps the longest suffix that fits and flags the leading omission', () => {
    const projection = projectThinkingLine('abcdefghij', 5, measure)
    // '…' + 'ghij' 宽度为 5，是最长可放下后缀；'fghij' 需要 6。
    expect(projection).toEqual({ text: 'ghij', truncated: true })
  })

  it('keeps mixed CJK/English/path tails in original reading order', () => {
    const flat = '分析 /usr/local/lib/node_modules/kk-studio 已完成'
    const projection = projectThinkingLine(flat, 12, measure)
    expect(projection.truncated).toBe(true)
    // 后缀取自原文尾部，顺序与原 Markdown 一致，未被反向排列。
    expect(flat.endsWith(projection.text)).toBe(true)
    expect(projection.text).toBe(flat.slice(flat.length - 11))
  })

  // 省略边界必须落在字素簇上：emoji、组合字符与 ZWJ 序列不能被 UTF-16 切断。
  it('cuts at grapheme boundaries instead of splitting a surrogate pair', () => {
    // '🙂abc' 的第二个 UTF-16 码元是低代理；按码元切会得到残缺 emoji。
    expect(projectThinkingLine('🙂abc', 4, measure)).toEqual({ text: 'abc', truncated: true })
    expect(projectThinkingLine('x🙂', 2, measure)).toEqual({ text: '', truncated: true })
  })

  it('never returns a lone surrogate or a dangling combining mark at any width', () => {
    const flat = 'e\u0301👨‍👩‍👧‍👦路径/usr/local abc'
    const brokenBoundary = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]|^[\p{M}\u200D]/u
    for (let width = 1; width <= 24; width += 1) {
      const projection = projectThinkingLine(flat, width, measure)
      expect(flat.endsWith(projection.text)).toBe(true)
      expect(projection.text).not.toMatch(brokenBoundary)
      // 结果不得超出可用宽度；只剩省略标记时宽度为 1，同样不越界。
      expect(measure(`…${projection.text}`)).toBeLessThanOrEqual(width)
    }
  })

  // 连省略标记都放不下时只保留省略标记，不渲染超出容器的假尾部。
  it('drops the tail entirely when only the ellipsis fits', () => {
    expect(projectThinkingLine('abcdef', 1, measure)).toEqual({ text: '', truncated: true })
  })

  // 无 Intl.Segmenter 的环境退化为码点切分，仍不得切断代理对。
  it('falls back to code-point boundaries when Intl.Segmenter is unavailable', () => {
    const intl = Intl as unknown as { Segmenter?: typeof Intl.Segmenter }
    const original = intl.Segmenter
    intl.Segmenter = undefined
    try {
      expect(projectThinkingLine('🙂abc', 4, measure)).toEqual({ text: 'abc', truncated: true })
    } finally {
      intl.Segmenter = original
    }
  })

  // 非单调度量不会制造越界假尾部：按整行返回，交由 CSS 裁剪。
  it('keeps the whole line when the measure is not monotone', () => {
    const weirdMeasure = (text: string) => (text.includes('…') ? 1 : text.length)
    expect(projectThinkingLine('abcdef', 3, weirdMeasure)).toEqual({
      text: 'abcdef',
      truncated: false,
    })
  })

  it('treats an empty line and a non-positive width safely', () => {
    expect(projectThinkingLine('', 100, measure)).toEqual({ text: '', truncated: false })
    expect(projectThinkingLine('abcdef', 0, measure)).toEqual({ text: 'abcdef', truncated: false })
  })
})
