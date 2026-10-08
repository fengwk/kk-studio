import { describe, expect, it } from 'vitest'
import { formatToolCallSummary } from '@/features/ai/runtime/thread-panel/messages/tool-display'

describe('tool display', () => {
  it('formats pi-style summaries for the built-in tool contracts', () => {
    const readSummary = formatToolCallSummary('read', JSON.stringify({
      path: '/app/file.ts',
      workdir: '/srv/project',
      offset: 2,
      limit: 20,
    }))
    expect(readSummary.text).toBe('read /app/file.ts in /srv/project [offset=2 limit=20]')
    expect(formatToolCallSummary('grep', JSON.stringify({
      pattern: 'needle',
      path: 'src',
      workdir: '/srv/project',
      include: '**/*.ts',
      ignore_case: true,
      limit: 12,
    })).text).toBe(
      'grep "needle" in src from /srv/project [include=**/*.ts ignore_case limit=12]',
    )
    expect(formatToolCallSummary('bash', JSON.stringify({
      command: 'npm test',
      workdir: '/srv/project/frontend',
      timeout_seconds: 30,
    })).text).toBe('bash npm test in /srv/project/frontend [timeout_seconds=30]')
    expect(formatToolCallSummary('lsp_goto_definition', JSON.stringify({
      path: 'src/App.java',
      workdir: '/srv/project',
      line: 42,
      character: 7,
    })).text).toBe(
      'lsp_goto_definition src/App.java in /srv/project [line=42 character=7]',
    )
  })

  it('keeps task thread_id in the same options and the user prompt out of the header', () => {
    expect(formatToolCallSummary('task', JSON.stringify({
      subagent_type: 'explorer',
      thread_id: 'child-1',
      max_turns: 12,
      prompt: 'inspect the repository',
    })).text).toBe('task explorer [max_turns=12 thread_id=child-1]')
    expect(formatToolCallSummary('task', '{"subagent_type":"Explorer","max_turns":4}').text)
      .toBe('task Explorer [max_turns=4]')
  })

  it('keeps the ask_user question out of the header', () => {
    // 问题/选项/答案整体下移到只读记录，Header 不重复问题文本。
    expect(formatToolCallSummary('ask_user', JSON.stringify({
      questions: [{ question: 'Which environment?', options: ['dev', 'prod'] }],
    }))).toEqual({
      name: 'ask_user',
      detail: '',
      text: 'ask_user',
    })
  })

  it('falls back to compact JSON for unknown tools without altering string content', () => {
    const customSummary = formatToolCallSummary('custom', '{\n  "value": true\n}')
    expect(customSummary.text).toBe('custom {"value":true}')
    const summary = formatToolCallSummary('custom', JSON.stringify({ value: 'x'.repeat(3_000) }))
    // 超长值完整保留在 Header 参数区（由换行承载），不做省略。
    expect(summary.detail).toBe(JSON.stringify({ value: 'x'.repeat(3_000) }))
    expect(summary.detail.endsWith('…')).toBe(false)
    // 字符串内容原样保留：不折叠内部空白、不转义换行、不改动引号与反斜杠。
    const messy = JSON.stringify({
      script: 'line1\n  line2 "quoted" \\backslash\\',
      nested: { text: 'a\tb' },
    })
    expect(formatToolCallSummary('mcp__server__tool', messy).detail).toBe(messy)
  })

  it('keeps real false/0/empty values and never invents defaults for unknown tools', () => {
    const detail = formatToolCallSummary('mcp__server__tool', JSON.stringify({
      flag: false,
      count: 0,
      empty: '',
      nullable: null,
      list: [],
      object: {},
      filled: 1,
    })).detail
    expect(detail).toBe(JSON.stringify({
      flag: false,
      count: 0,
      empty: '',
      nullable: null,
      list: [],
      object: {},
      filled: 1,
    }))
    // 未传入的键不会补默认值。
    expect(detail).not.toContain('timeout_seconds')
    expect(detail).not.toContain('workdir')
    // 布尔旗标只在已知工具的上层摘要里折叠为名字，未知工具必须保留 false。
    expect(formatToolCallSummary('grep', JSON.stringify({
      pattern: 'x',
      literal: false,
      ignore_case: false,
    })).text).toBe('grep "x" in .')
  })

  it('formats write/edit summaries with option flags and workdir suffixes', () => {
    expect(formatToolCallSummary('write', JSON.stringify({
      path: 'App.java',
      workdir: '/srv/project/src',
      content: 'class App {}',
    })).text).toBe('write App.java in /srv/project/src')
    // replace_all=true 渲染为旗标；false/缺省不出现（影响范围参数仍可见）。
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
    // 缺少 path 时返回空详情（不回退到原始 JSON，避免重复铺开 old/new）。
    expect(formatToolCallSummary('write', JSON.stringify({ content: 'x' }))).toEqual({
      name: 'write',
      detail: '',
      text: 'write',
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

  it('formats lsp tool summaries', () => {
    expect(formatToolCallSummary('lsp_workspace_symbols', JSON.stringify({
      path: 'src/App.java',
      workdir: '/srv/project',
      query: 'UserService',
      limit: 20,
    })).text).toBe(
      'lsp_workspace_symbols src/App.java in /srv/project "UserService" [limit=20]',
    )
    expect(formatToolCallSummary('lsp_workspace_symbols', JSON.stringify({
      path: 'src',
      workdir: '/srv/project',
    })).text).toBe('lsp_workspace_symbols src in /srv/project')
    expect(formatToolCallSummary('lsp_java_decompile', JSON.stringify({
      path: 'src/App.java',
      workdir: '/srv/project',
      target: 'String (Class) - jdt://contents',
    })).text).toBe(
      'lsp_java_decompile src/App.java in /srv/project "String (Class) - jdt://contents"',
    )
    expect(formatToolCallSummary('lsp_java_decompile', JSON.stringify({
      path: 'src/App.java',
      workdir: '/srv/project',
    })).text).toBe('lsp_java_decompile src/App.java in /srv/project')
  })

  it('normalizes tool names and falls back to a placeholder when blank', () => {
    expect(formatToolCallSummary(' READ ', '{"path":"/a"}').name).toBe('READ')
    expect(formatToolCallSummary('  ', '').name).toBe('Tool')
    expect(formatToolCallSummary('custom', '').text).toBe('custom')
    expect(formatToolCallSummary('custom', 'not-json {').text).toBe('custom not-json {')
  })
})
