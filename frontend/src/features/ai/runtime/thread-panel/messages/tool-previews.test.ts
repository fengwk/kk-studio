import { describe, expect, it } from 'vitest'
import {
  formatEditPreview,
  formatWritePreview,
  generateSimpleDiffLines,
  parseToolArguments,
  previewForToolCall,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'

describe('tool previews', () => {
  it('parses complete write arguments and keeps the full content', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'SortingAlgorithms.java',
      content: 'line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\n',
    }))
    const preview = formatWritePreview(parsed)

    expect(parsed.complete).toBe(true)
    expect(preview.path).toBe('SortingAlgorithms.java')
    expect(preview.lines).toEqual([
      'line1',
      'line2',
      'line3',
      'line4',
      'line5',
      'line6',
      'line7',
      'line8',
    ])
  })

  it('keeps parsed content while write arguments are still streaming', () => {
    const parsed = parseToolArguments('{"path":"App.java","content":"one\\ntwo\\nthree\\nfour\\nfive\\nsix\\nseven\\neight')
    const preview = formatWritePreview(parsed)

    expect(parsed.complete).toBe(false)
    expect(preview.path).toBe('App.java')
    expect(preview.lines).toEqual(['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight'])
  })

  it('renders an edit preview as a numbered-style diff instead of raw JSON', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      old_string: 'alpha\nbeta\ngamma',
      new_string: 'alpha\nBETA\ngamma',
    }))
    const preview = formatEditPreview(parsed)

    expect(previewForToolCall('edit', parsed.raw)?.kind).toBe('edit')
    expect(preview.path).toBe('App.java')
    expect(generateSimpleDiffLines('alpha\nbeta\ngamma', 'alpha\nBETA\ngamma')).toEqual([
      ' alpha',
      '-beta',
      '+BETA',
      ' gamma',
    ])
    expect(preview.lines).toEqual([
      ' alpha',
      '-beta',
      '+BETA',
      ' gamma',
    ])
  })

  it('returns null for unknown tools and matches tool names case-insensitively', () => {
    expect(previewForToolCall('unknown_tool', '{"a":1}')).toBeNull()
    expect(previewForToolCall('  WRITE  ', '{"path":"a.txt","content":"x"}')?.kind).toBe('write')
    expect(previewForToolCall('Edit', '{"path":"a.txt","old_string":"a","new_string":"b"}')?.kind)
      .toBe('edit')
  })

  it('treats empty and non-object JSON payloads as incomplete with empty values', () => {
    expect(parseToolArguments('')).toEqual({ raw: '', complete: false, values: {} })
    expect(parseToolArguments('   ')).toEqual({ raw: '   ', complete: false, values: {} })
    expect(parseToolArguments('"just-a-string"')).toEqual({
      raw: '"just-a-string"',
      complete: true,
      values: {},
    })
    expect(parseToolArguments('[]')).toEqual({ raw: '[]', complete: true, values: {} })
  })

  it('extracts partial boolean/number/string fields and unescapes string values', () => {
    // 完整闭合的 JSON 走 complete 路径（JSON.parse 原生解转义）；流式中途的未闭合字符串走 partial 提取。
    const complete = parseToolArguments(
      '{"path":"App.java","replace_all":true,"offset":12,"content":"one\\ntwo"}',
    )
    expect(complete.complete).toBe(true)
    expect(complete.values).toEqual({
      path: 'App.java',
      replace_all: true,
      offset: 12,
      content: 'one\ntwo',
    })
    const partial = parseToolArguments(
      '{"path":"App.java","replace_all":true,"offset":12,"content":"one\\ntwo',
    )
    expect(partial.complete).toBe(false)
    expect(partial.values.path).toBe('App.java')
    expect(partial.values.replace_all).toBe(true)
    expect(partial.values.offset).toBe(12)
    // 流式提取器的 unescapeJsonString 会展开 \\n 字面量。
    expect(partial.values.content).toBe('one\ntwo')
  })

  it('keeps an unterminated trailing string value but drops unquoted garbage keys', () => {
    const parsed = parseToolArguments('{"path":"App.java","content":"unclosed')
    expect(parsed.complete).toBe(false)
    expect(parsed.values.path).toBe('App.java')
    expect(parsed.values.content).toBe('unclosed')
    expect(parsed.values.garbage).toBeUndefined()
  })

  it('builds a full diff with context and an empty diff for empty inputs', () => {
    expect(generateSimpleDiffLines('', '')).toEqual([])
    expect(generateSimpleDiffLines('keep\na\nb', 'keep\nX\nY')).toEqual([
      ' keep',
      '-a',
      '-b',
      '+X',
      '+Y',
    ])
    // 只有 new_string 时全部新增；只有 old_string 时全部删除。
    expect(generateSimpleDiffLines('', 'added')).toEqual(['+added'])
    expect(generateSimpleDiffLines('removed', '')).toEqual(['-removed'])
    // 完全相同的内容没有 diff 行。
    expect(generateSimpleDiffLines('same', 'same')).toEqual([' same'])
  })

  it('drops the trailing empty line produced by a content that ends with a newline', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'a.txt',
      content: 'one\ntwo\n',
    }))
    expect(formatWritePreview(parsed).lines).toEqual(['one', 'two'])
  })
})
