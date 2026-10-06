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

  // 连省略标记都放不下时只保留省略标记，不渲染超出容器的假尾部。
  it('drops the tail entirely when only the ellipsis fits', () => {
    expect(projectThinkingLine('abcdef', 1, measure)).toEqual({ text: '', truncated: true })
  })

  it('treats an empty line and a non-positive width safely', () => {
    expect(projectThinkingLine('', 100, measure)).toEqual({ text: '', truncated: false })
    expect(projectThinkingLine('abcdef', 0, measure)).toEqual({ text: 'abcdef', truncated: false })
  })
})
