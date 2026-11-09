import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 工具适配器只保留 Header 内容与正文样式；卡片外壳、chevron 与底部收起由
 * ThreadDisclosure 负责。对话记录禁止内部滚动，审批卡片通过 `.is-bounded` 例外。
 */
const css = readFileSync(
  resolve(
    process.cwd(),
    'src',
    'features',
    'thread',
    'runtime',
    'thread-panel',
    'messages',
    'tool-card.css',
  ),
  'utf8',
)
const globalCss = readFileSync(resolve(process.cwd(), 'src', 'styles.css'), 'utf8')

function rule(source: string, pattern: RegExp): string {
  const match = source.match(pattern)
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

describe('tool card style contracts', () => {
  it('wraps the header parameters without truncating the complete text', () => {
    const summary = rule(css, /\.thread-tool-summary\s*\{[^}]*\}/)
    const detail = rule(css, /\.thread-tool-summary-detail\s*\{[^}]*\}/)

    expect(summary).toContain('display: block')
    expect(summary).toContain('white-space: pre-wrap')
    expect(summary).toContain('overflow-wrap: anywhere')
    expect(detail).toContain('white-space: pre-wrap')
    expect(detail).toContain('overflow-wrap: anywhere')
    expect(detail).not.toContain('text-overflow: ellipsis')
    expect(detail).not.toContain('overflow-x: auto')
  })

  it('keeps transcript output unbounded and only bounds the approval card viewport', () => {
    const output = rule(css, /\.thread-tool-output\s*\{[^}]*\}/)
    const bounded = rule(css, /\.thread-tool-output\.is-bounded\s*\{[^}]*\}/)
    const pre = rule(css, /\.thread-tool-pre\s*\{[^}]*margin: 0[^}]*\}/)

    expect(output).not.toContain('max-height')
    expect(output).not.toContain('overflow: auto')
    expect(bounded).toContain('max-height')
    expect(bounded).toContain('overflow: auto')
    expect(bounded).toContain('overscroll-behavior: auto')
    expect(pre).toContain('font-family: var(--mono)')
  })

  it('styles the header subagent action without a UUID-width layout', () => {
    const link = rule(css, /\.thread-tool-thread-link\s*\{[^}]*\}/)
    expect(link).toContain('display: inline-flex')
    expect(link).toContain('white-space: nowrap')
    expect(link).not.toContain('overflow-wrap: anywhere')
  })

  it('uses the danger token for approval rejection borders', () => {
    const dangerButton = rule(globalCss, /\.ghost-btn\.danger\s*\{[^}]*\}/)

    // 组件只需声明 danger；该规则确保最终视觉始终使用设计系统的红色边框。
    expect(dangerButton).toContain('border-color: var(--danger-border)')
    expect(dangerButton).toContain('color: var(--danger)')
  })
})
