import { describe, expect, it } from 'vitest'
import {
  COLLAPSED_MAX_CHARS,
  disclosureHint,
  discloseText,
  isMcpToolName,
  toolDisclosureQuota,
} from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'

describe('tool disclosure quota', () => {
  it('uses tail windows for bash and leading windows for write/edit/mcp', () => {
    expect(toolDisclosureQuota('bash', 'active')).toEqual({ preview: 10, body: 0 })
    expect(toolDisclosureQuota('bash', 'settled')).toEqual({ preview: 10, body: 0 })
    expect(toolDisclosureQuota('write', 'active')).toEqual({ preview: -1, body: 0 })
    expect(toolDisclosureQuota('write', 'settled')).toEqual({ preview: 10, body: 0 })
    expect(toolDisclosureQuota('edit', 'active')).toEqual({ preview: -1, body: 0 })
    expect(toolDisclosureQuota('edit', 'settled')).toEqual({ preview: 10, body: 0 })
    expect(toolDisclosureQuota('mcp_github_list_issues', 'settled'))
      .toEqual({ preview: 5, body: 0 })
  })

  it('recognizes every mcp_ prefixed tool and hides all other tools', () => {
    expect(isMcpToolName('mcp_github_list_issues')).toBe(true)
    expect(isMcpToolName('  MCP__filesystem__read  ')).toBe(true)
    expect(isMcpToolName('read')).toBe(false)
    expect(toolDisclosureQuota('read', 'settled')).toEqual({ preview: 0, body: 0 })
    expect(toolDisclosureQuota('ask_user', 'active')).toEqual({ preview: 0, body: 0 })
    expect(toolDisclosureQuota('unknown', 'settled')).toEqual({ preview: 0, body: 0 })
  })
})

describe('discloseText', () => {
  it('hidden quota shows no text but reports the real hidden line count', () => {
    expect(discloseText('one\ntwo\nthree', 0)).toEqual({
      text: '',
      hiddenLines: 3,
      truncated: false,
    })
    expect(discloseText('', 0)).toEqual({ text: '', hiddenLines: 0, truncated: false })
  })

  it('negative limit keeps the complete text regardless of characters', () => {
    const text = `${'x'.repeat(COLLAPSED_MAX_CHARS + 500)}\nsecond`
    expect(discloseText(text, -1)).toEqual({
      text,
      hiddenLines: 0,
      truncated: false,
    })
  })

  it('keeps the leading window and counts hidden whole lines', () => {
    const result = discloseText('1\n2\n3\n4\n5', 2)
    expect(result).toEqual({ text: '1\n2', hiddenLines: 3, truncated: false })
    expect(disclosureHint(result, 'start')).toBe('… 3 more lines')
  })

  it('keeps the trailing window for bash and reports earlier lines', () => {
    const result = discloseText('1\n2\n3\n4\n5', 2, 'end')
    expect(result).toEqual({ text: '4\n5', hiddenLines: 3, truncated: false })
    expect(disclosureHint(result, 'end')).toBe('… 3 earlier lines')
  })

  it('does not turn a trailing newline into an extra logical line', () => {
    const result = discloseText('1\n2\n3\n', 2)
    expect(result).toEqual({ text: '1\n2', hiddenLines: 1, truncated: false })
  })

  it('caps collapsed characters by code point without splitting surrogate pairs', () => {
    const emoji = '😀'
    const result = discloseText(emoji.repeat(10), 5, 'start', 6)
    expect(result.text).toBe(emoji.repeat(6))
    expect(result.hiddenLines).toBe(0)
    expect(result.truncated).toBe(true)
    expect(disclosureHint(result, 'start')).toBe('… truncated')
  })

  it('reports a partially shown line as truncated without pretending the whole line is lost', () => {
    const result = discloseText('first\nsecond line that is long\nthird', 2, 'start', 9)
    expect(result.text).toBe('first\nsec')
    expect(result.hiddenLines).toBe(1)
    expect(result.truncated).toBe(true)
    expect(disclosureHint(result, 'start')).toBe('… 1 more line, truncated')
  })

  it('keeps the end of a bash tail window when early lines are huge and the exit error is last', () => {
    // 尾部窗口内有多行早期大行，末尾才是退出码：字符上限必须保留末尾。
    const huge1 = `huge-a-${'x'.repeat(2_000)}`
    const huge2 = `huge-b-${'y'.repeat(2_000)}`
    const text = [huge1, huge2, 'middle-1', '[Command exited with code 2.]'].join('\n')
    const result = discloseText(text, 10, 'end', 1_500)

    expect(result.text.endsWith('middle-1\n[Command exited with code 2.]')).toBe(true)
    expect(result.text).not.toContain('huge-a-')
    // huge2 只剩尾部（部分可见），middle 与 exit 完整可见；huge1 整行隐藏。
    expect(result.hiddenLines).toBe(1)
    expect(result.truncated).toBe(true)
    expect(disclosureHint(result, 'end')).toBe('… 1 earlier line, truncated')
    expect(Array.from(result.text)).toHaveLength(1_500)
  })

  it('never splits an emoji when keeping the tail suffix', () => {
    const emoji = '😀'
    const result = discloseText(`${emoji.repeat(2_000)}\nexit 2`, 2, 'end', 1_500)
    expect(result.text.endsWith('\nexit 2')).toBe(true)
    expect(Array.from(result.text)).toHaveLength(1_500)
    expect(result.text.startsWith('😀')).toBe(true)
    expect(result.hiddenLines).toBe(0)
    expect(result.truncated).toBe(true)
  })

  it('uses singular line wording and distinguishes earlier truncation', () => {
    expect(disclosureHint(discloseText('a\nb', 1), 'start')).toBe('… 1 more line')
    expect(disclosureHint(discloseText('a\nb', 1, 'end'), 'end')).toBe('… 1 earlier line')
    expect(disclosureHint(discloseText('abcdef\nb', 1, 'start', 3), 'start'))
      .toBe('… 1 more line, truncated')
    expect(disclosureHint(discloseText('abcdef', 1, 'end', 2), 'end'))
      .toBe('… truncated')
  })

  it('returns no hint when the complete text already fits', () => {
    expect(disclosureHint(discloseText('a\nb', 5), 'start')).toBeNull()
    expect(disclosureHint(discloseText('a\nb', -1), 'start')).toBeNull()
  })
})
