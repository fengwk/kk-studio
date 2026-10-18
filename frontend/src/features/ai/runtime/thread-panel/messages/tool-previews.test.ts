import { describe, expect, it } from 'vitest'
import {
  formatEditPreview,
  formatWritePreview,
  parseToolArguments,
  previewForToolCall,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'

describe('tool previews', () => {
  const validSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

  it('parses complete write arguments and keeps the full content and workdir', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'SortingAlgorithms.java',
      workdir: '/home/user/project',
      content: 'line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\n',
    }))
    const preview = formatWritePreview(parsed)

    expect(parsed.complete).toBe(true)
    expect(preview.path).toBe('SortingAlgorithms.java')
    expect(preview.workdir).toBe('/home/user/project')
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

  it('renders streamed edit input as explicit input rather than diff', () => {
    const parsed = parseToolArguments('{"path":"App.java","old_string":"alpha\\nbeta","new_string":"alpha\\nBETA')
    const preview = formatEditPreview(parsed, { streaming: true })

    expect(preview?.kind).toBe('edit')
    if (preview && preview.kind === 'edit') {
      expect(preview.mode).toBe('input')
      if (preview.mode === 'input') {
        expect(preview.path).toBe('App.java')
        expect(preview.oldText).toBe('alpha\nbeta')
        expect(preview.newText).toBe('alpha\nBETA')
      }
    }
  })

  it('renders preparing state after arguments finish while waiting for review', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      old_string: 'alpha\nbeta',
      new_string: 'alpha\nBETA',
    }))
    const preview = formatEditPreview(parsed, { isWaitingReview: true })

    expect(preview?.kind).toBe('edit')
    if (preview && preview.kind === 'edit') {
      expect(preview.mode).toBe('preparing')
      if (preview.mode === 'preparing') {
        expect(preview.path).toBe('App.java')
      }
    }
  })

  it('replaces input with real multi-hunk numbered file diff from editReview', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      old_string: 'alpha\nbeta',
      new_string: 'alpha\nBETA',
    }))
    const realDiff = '@@ -10,3 +10,3 @@\n 10|context\n-11|alpha\n-12|beta\n+11|alpha\n+12|BETA\n 13|context'
    const preview = formatEditPreview(parsed, {
      editReview: {
        path: '/canonical/App.java',
        diff: realDiff,
        sha256: validSha,
      },
    })

    expect(preview?.kind).toBe('edit')
    if (preview && preview.kind === 'edit') {
      expect(preview.mode).toBe('review')
      if (preview.mode === 'review') {
        expect(preview.path).toBe('/canonical/App.java')
        expect(preview.diff).toBe(realDiff)
        expect(preview.sha256).toBe(validSha)
        expect(preview.lines).toEqual([
          '@@ -10,3 +10,3 @@',
          ' 10|context',
          '-11|alpha',
          '-12|beta',
          '+11|alpha',
          '+12|BETA',
          ' 13|context',
        ])
      }
    }
  })

  it('returns null for historical terminal calls without a review instead of eternal spinner', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      old_string: 'a',
      new_string: 'b',
    }))
    const preview = formatEditPreview(parsed, { isTerminalWithoutReview: true })
    expect(preview).toBeNull()
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

  it('drops the trailing empty line produced by a content that ends with a newline', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'a.txt',
      content: 'one\ntwo\n',
    }))
    expect(formatWritePreview(parsed).lines).toEqual(['one', 'two'])
  })
})
