import { describe, expect, it } from 'vitest'
import {
  formatEditPreview,
  formatWritePreview,
  parseToolArguments,
  previewForToolCall,
  type ToolCallPreview,
} from '@/features/thread/runtime/thread-panel/messages/tool-previews'

/** 从 edit 预览取出流式输入的条目列表；同时确认当前处于 input 模式。 */
function inputEntries(preview: ToolCallPreview | null) {
  if (preview?.kind !== 'edit' || preview.mode !== 'input') {
    throw new Error(`expected edit input preview, got ${JSON.stringify(preview)}`)
  }
  return preview.entries
}

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

  it('uses the canonical write review diff instead of proposed content for approval', () => {
    const preview = previewForToolCall(
      'write',
      '{"path":"relative.txt","content":"proposed"}',
      {
        editReview: {
          path: '/canonical/new.txt',
          diff: '+1|proposed',
          sha256: null,
        },
      },
    )

    expect(preview).toMatchObject({
      kind: 'write',
      path: '/canonical/new.txt',
      review: { diff: '+1|proposed', sha256: null },
    })
  })

  it('keeps parsed content while write arguments are still streaming', () => {
    const parsed = parseToolArguments('{"path":"App.java","content":"one\\ntwo\\nthree\\nfour\\nfive\\nsix\\nseven\\neight')
    const preview = formatWritePreview(parsed)

    expect(parsed.complete).toBe(false)
    expect(preview.path).toBe('App.java')
    expect(preview.lines).toEqual(['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight'])
  })

  it('renders one streamed edit entry as explicit input rather than diff', () => {
    const parsed = parseToolArguments(
      '{"path":"App.java","edits":[{"old_string":"alpha\\nbeta","new_string":"alpha\\nBETA',
    )
    const preview = formatEditPreview(parsed, { streaming: true })

    expect(inputEntries(preview)).toEqual([
      { oldText: 'alpha\nbeta', newText: 'alpha\nBETA', replaceAll: false },
    ])
    expect(preview).toMatchObject({ kind: 'edit', mode: 'input', path: 'App.java' })
  })

  it('keeps every parsed entry of a multi-entry edit that is still streaming', () => {
    const parsed = parseToolArguments(
      '{"path":"App.java","edits":['
      + '{"old_string":"one","new_string":"ONE","replace_all":true},'
      + '{"old_string":"two","new_string":"TWO"},'
      + '{"old_string":"three","new_',
    )
    const preview = formatEditPreview(parsed, { streaming: true })

    expect(parsed.complete).toBe(false)
    expect(inputEntries(preview)).toEqual([
      { oldText: 'one', newText: 'ONE', replaceAll: true },
      { oldText: 'two', newText: 'TWO', replaceAll: false },
      { oldText: 'three', newText: '', replaceAll: false },
    ])
  })

  it('omits entries while edits itself is still an empty or unparsed array', () => {
    expect(inputEntries(formatEditPreview(
      parseToolArguments('{"path":"App.java","edits":['),
      { streaming: true },
    ))).toEqual([])
    expect(inputEntries(formatEditPreview(
      parseToolArguments('{"path":"App.java","edits":'),
      { streaming: true },
    ))).toEqual([])
    // 顶层仍是旧的单替换参数时不伪造输入条目。
    expect(inputEntries(formatEditPreview(
      parseToolArguments('{"path":"App.java","old_string":"a","new_string":"b"}'),
      { streaming: true },
    ))).toEqual([])
  })

  it('renders preparing state after arguments finish while waiting for review', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      edits: [{ old_string: 'alpha\nbeta', new_string: 'alpha\nBETA' }],
    }))
    const preview = formatEditPreview(parsed, { isWaitingReview: true })

    expect(preview).toMatchObject({ kind: 'edit', mode: 'preparing', path: 'App.java' })
  })

  it('replaces streamed input with the real multi-hunk diff from editReview', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      edits: [
        { old_string: 'alpha\nbeta', new_string: 'alpha\nBETA' },
        { old_string: 'gamma', new_string: 'GAMMA', replace_all: true },
      ],
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
    if (preview?.kind === 'edit' && preview.mode === 'review') {
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
    } else {
      throw new Error('expected review preview')
    }
  })

  it('prefers editReview over streamed entries as soon as it exists', () => {
    const parsed = parseToolArguments(
      '{"path":"App.java","edits":[{"old_string":"alpha","new_string":"BETA',
    )
    const preview = formatEditPreview(parsed, {
      streaming: true,
      editReview: { path: '/canonical/App.java', diff: '-1|alpha\n+1|BETA', sha256: validSha },
    })

    expect(preview).toMatchObject({ kind: 'edit', mode: 'review', path: '/canonical/App.java' })
  })

  it('returns null for historical terminal calls without a review instead of eternal spinner', () => {
    const parsed = parseToolArguments(JSON.stringify({
      path: 'App.java',
      edits: [{ old_string: 'a', new_string: 'b' }],
    }))
    const preview = formatEditPreview(parsed, { isTerminalWithoutReview: true })
    expect(preview).toBeNull()
  })

  it('returns null for unknown tools and matches tool names case-insensitively', () => {
    expect(previewForToolCall('unknown_tool', '{"a":1}')).toBeNull()
    expect(previewForToolCall('  WRITE  ', '{"path":"a.txt","content":"x"}')?.kind).toBe('write')
    expect(previewForToolCall(
      'Edit',
      '{"path":"a.txt","edits":[{"old_string":"a","new_string":"b"}]}',
    )?.kind).toBe('edit')
  })

  it('treats empty and non-object JSON payloads as complete with empty values only when valid', () => {
    expect(parseToolArguments('')).toEqual({ raw: '', complete: false, values: {} })
    expect(parseToolArguments('   ')).toEqual({ raw: '   ', complete: false, values: {} })
    expect(parseToolArguments('"just-a-string"')).toEqual({
      raw: '"just-a-string"',
      complete: true,
      values: {},
    })
    expect(parseToolArguments('[]')).toEqual({ raw: '[]', complete: true, values: {} })
  })

  it('extracts partial boolean/number/string fields with escapes decoded', () => {
    // 完整闭合的 JSON 走 JSON.parse（原生解转义）；流式中途的未闭合值走前缀解析。
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
    expect(partial.values.content).toBe('one\ntwo')
  })

  it('keeps an unterminated trailing string value and drops unquoted garbage keys', () => {
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
