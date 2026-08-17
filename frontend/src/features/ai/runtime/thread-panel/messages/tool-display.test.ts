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
      'mcp_call_tool',
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
})
