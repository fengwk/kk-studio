import { describe, expect, it } from 'vitest'
import {
  formatToolCallLinePreview,
  formatToolCallSummary,
  formatToolResultPreview,
  shouldSuppressTransientToolResult,
} from '@/features/ai/runtime/thread-panel/messages/tool-display'

describe('tool display', () => {
  it('formats pi-style summaries for the built-in tool contracts', () => {
    const readSummary = formatToolCallSummary('read', JSON.stringify({
      path: '/app/file.ts',
      workdir: 'src',
      offset: 2,
      limit: 20,
    }))
    expect(readSummary.text).toBe('read /app/file.ts in src [offset=2 limit=20]')
    expect(readSummary.coversArguments).toBe(true)
    expect(formatToolCallSummary('grep', JSON.stringify({
      pattern: 'needle',
      path: 'src',
      workdir: 'repo',
      include: '**/*.ts',
      ignore_case: true,
      limit: 12,
    })).text).toBe(
      'grep "needle" in src from repo [include=**/*.ts ignore_case limit=12]',
    )
    expect(formatToolCallSummary('bash', JSON.stringify({
      command: 'npm test',
      workdir: 'frontend',
      timeout_seconds: 30,
    })).text).toBe('bash npm test in frontend [timeout_seconds=30]')
    expect(formatToolCallSummary('task', JSON.stringify({
      subagent_type: 'explorer',
      session_id: 'child-1',
      maxTurns: 12,
      prompt: 'inspect the repository',
    })).text).toBe('task explorer [session_id=child-1 maxTurns=12]')
    expect(formatToolCallSummary('lsp_goto_definition', JSON.stringify({
      path: 'src/App.java',
      line: 42,
      character: 7,
    })).text).toBe('lsp_goto_definition src/App.java [line=42 character=7]')
  })

  it('falls back to a compact name + JSON summary without hiding long header content', () => {
    const customSummary = formatToolCallSummary('custom', '{\n  "value": true\n}')
    expect(customSummary.text).toBe('custom {"value":true}')
    expect(customSummary.coversArguments).toBe(false)
    const summary = formatToolCallSummary('custom', JSON.stringify({ value: 'x'.repeat(3_000) }))
    expect(summary.detail).toBe(JSON.stringify({ value: 'x'.repeat(3_000) }))
    expect(summary.detail.endsWith('…')).toBe(false)
  })

  it('uses a five-line streaming tail, seven settled write lines, and full edit/expanded previews', () => {
    const lines = Array.from({ length: 9 }, (_, index) => `line-${index + 1}`)

    expect(formatToolCallLinePreview(lines, { expanded: false })).toEqual({
      lines: [...lines.slice(0, 7), '... (2 more lines, 9 total)'],
      truncated: true,
    })
    expect(formatToolCallLinePreview(lines, {
      expanded: false,
      streaming: true,
    })).toEqual({
      lines: ['... (5 earlier lines)', ...lines.slice(-4)],
      truncated: true,
    })
    expect(formatToolCallLinePreview(lines, {
      expanded: false,
      full: true,
    })).toEqual({
      lines,
      truncated: false,
    })
    expect(formatToolCallLinePreview(lines, { expanded: true })).toEqual({
      lines,
      truncated: false,
    })
  })

  it('applies per-tool collapsed result budgets while preserving failures and expanded output', () => {
    const output = Array.from({ length: 12 }, (_, index) => `line-${index + 1}`).join('\n')

    expect(formatToolResultPreview('read', output, { expanded: false, error: false }))
      .toEqual({ text: '', maxLines: 0, truncated: true })
    expect(formatToolResultPreview('bash', output, { expanded: false, error: false }))
      .toEqual({
        text:
          `... (2 earlier lines)\n`
          + Array.from({ length: 10 }, (_, index) => `line-${index + 3}`).join('\n'),
        maxLines: 11,
        truncated: true,
      })
    expect(formatToolResultPreview('task', output, { expanded: false, error: false }))
      .toEqual({
        text:
          `... (7 earlier lines)\n`
          + Array.from({ length: 5 }, (_, index) => `line-${index + 8}`).join('\n'),
        maxLines: 6,
        truncated: true,
      })
    expect(formatToolResultPreview('read', output, { expanded: false, error: true }))
      .toEqual({ text: output, maxLines: null, truncated: false })
    expect(formatToolResultPreview('read', output, { expanded: true, error: false }))
      .toEqual({ text: output, maxLines: null, truncated: false })
  })

  it('caps collapsed result previews at 2500 characters', () => {
    const preview = formatToolResultPreview(
      'custom_tool',
      'x'.repeat(3_000),
      { expanded: false, error: false },
    )

    expect(preview.text.length).toBe(2_500)
    expect(preview.text.startsWith('... (output truncated)\n')).toBe(true)
    expect(preview.text.endsWith('...')).toBe(true)
    expect(preview.maxLines).toBe(6)
    expect(preview.truncated).toBe(true)
  })

  it('suppresses only valid task.status transport heartbeats', () => {
    expect(shouldSuppressTransientToolResult(
      'task',
      '{"kind":"task.status"}',
    )).toBe(true)
    expect(shouldSuppressTransientToolResult('task', '{"kind":"other"}')).toBe(false)
    expect(shouldSuppressTransientToolResult('bash', '{"kind":"task.status"}')).toBe(false)
  })

  it('formats write/edit summaries with option flags and workdir suffixes', () => {
    expect(formatToolCallSummary('write', JSON.stringify({
      path: 'App.java',
      workdir: 'src',
      content: 'class App {}',
    })).text).toBe('write App.java in src')
    // replace_all=true 渲染为旗标；false/缺省不出现。
    expect(formatToolCallSummary('edit', JSON.stringify({
      path: 'App.java',
      replace_all: true,
      old_string: 'a',
      new_string: 'b',
    })).text).toBe('edit App.java [replace_all]')
    expect(formatToolCallSummary('edit', JSON.stringify({
      path: 'App.java',
      replace_all: false,
    })).text).toBe('edit App.java')
    // 缺少 path 时返回空详情（coversArguments 仍为 true，避免回退到原始 JSON）。
    expect(formatToolCallSummary('write', JSON.stringify({ content: 'x' }))).toEqual({
      name: 'write',
      detail: '',
      text: 'write',
      coversArguments: true,
    })
  })

  it('formats bash/grep/find summaries and falls back when required fields are absent', () => {
    expect(formatToolCallSummary('bash', JSON.stringify({ command: 'npm test' })).text)
      .toBe('bash npm test')
    // command 缺失时回退为原始 JSON 文本（{} 是合法解析结果）。
    expect(formatToolCallSummary('bash', JSON.stringify({})).text).toBe('bash {}')
    expect(formatToolCallSummary('grep', JSON.stringify({
      pattern: 'needle',
      path: 'src',
      include: '**/*.ts',
      ignore_case: true,
      literal: false,
      multiline: true,
      limit: 5,
      timeout_seconds: 9,
    })).text).toBe(
      'grep "needle" in src [include=**/*.ts ignore_case multiline limit=5 timeout_seconds=9]',
    )
    // pattern/path 均缺失时回退为原始 JSON 文本。
    expect(formatToolCallSummary('grep', JSON.stringify({ include: 'x' })).text).toBe(
      'grep {"include":"x"}',
    )
    expect(formatToolCallSummary('find', JSON.stringify({
      pattern: '*.ts',
      path: 'src',
      limit: 3,
      timeout_seconds: 2,
    })).text).toBe('find *.ts in src [limit=3 timeout_seconds=2]')
    // pattern/path 均缺失时回退为原始 JSON 文本。
    expect(formatToolCallSummary('find', JSON.stringify({})).text).toBe('find {}')
  })

  it('formats lsp and load_skill summaries', () => {
    expect(formatToolCallSummary('lsp_workspace_symbols', JSON.stringify({
      path: 'src/App.java',
      query: 'UserService',
      limit: 20,
    })).text).toBe('lsp_workspace_symbols src/App.java "UserService" [limit=20]')
    expect(formatToolCallSummary('lsp_workspace_symbols', JSON.stringify({
      path: 'src',
    })).text).toBe('lsp_workspace_symbols src')
    expect(formatToolCallSummary('lsp_java_decompile', JSON.stringify({
      path: 'src/App.java',
      target: 'String (Class) - jdt://contents',
    })).text).toBe('lsp_java_decompile src/App.java "String (Class) - jdt://contents"')
    expect(formatToolCallSummary('lsp_java_decompile', JSON.stringify({
      path: 'src/App.java',
    })).text).toBe('lsp_java_decompile src/App.java')
    expect(formatToolCallSummary('load_skill', JSON.stringify({ name: 'dev' })).text)
      .toBe('load_skill dev')
    // name 缺失时回退为原始 JSON 文本（{} 是合法解析结果）。
    expect(formatToolCallSummary('load_skill', JSON.stringify({})).text).toBe('load_skill {}')
  })

  it('normalizes tool names and falls back to a placeholder when blank', () => {
    expect(formatToolCallSummary(' READ ', '{"path":"/a"}').name).toBe('READ')
    expect(formatToolCallSummary('  ', '').name).toBe('Tool')
    expect(formatToolCallSummary('custom', '').text).toBe('custom')
    expect(formatToolCallSummary('custom', 'not-json {').text).toBe('custom not-json {')
  })

  it('uses singular/plural truncation hints and keeps fully visible collapsed output stable', () => {
    const oneHidden = formatToolCallLinePreview(
      ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'],
      { expanded: false },
    )
    // 只有一行被隐藏时提示使用单数 line。
    expect(oneHidden.lines.at(-1)).toBe('... (1 more line, 8 total)')
    expect(oneHidden.truncated).toBe(true)
    expect(formatToolCallLinePreview(['only'], { expanded: false })).toEqual({
      lines: ['only'],
      truncated: false,
    })
    // 流式预览只有 1-4 行时没有 earlier 提示，也不截断。
    expect(formatToolCallLinePreview(['a', 'b'], { expanded: false, streaming: true })).toEqual({
      lines: ['a', 'b'],
      truncated: false,
    })
    // 5 行恰好等于流式窗口，不产生 earlier 提示。
    expect(formatToolCallLinePreview(
      ['a', 'b', 'c', 'd', 'e'],
      { expanded: false, streaming: true },
    )).toEqual({
      lines: ['a', 'b', 'c', 'd', 'e'],
      truncated: false,
    })
  })

  it('returns an empty quiet preview when the result text is already empty', () => {
    expect(formatToolResultPreview('read', '', { expanded: false, error: false })).toEqual({
      text: '',
      maxLines: 0,
      truncated: false,
    })
    expect(formatToolResultPreview('read', '  ', { expanded: false, error: false })).toEqual({
      text: '',
      maxLines: 0,
      truncated: false,
    })
  })

  it('suppresses task arrays and malformed JSON instead of treating them as heartbeats', () => {
    expect(shouldSuppressTransientToolResult('task', '[{"kind":"task.status"}]')).toBe(false)
    expect(shouldSuppressTransientToolResult('task', 'not-json')).toBe(false)
    expect(shouldSuppressTransientToolResult('task', 'null')).toBe(false)
  })
})
