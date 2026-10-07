import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 工具卡片样式已迁出全局 `styles.css`，独立为 `tool-card.css`（由 ToolMessageBlock 引入）。
 * 设计系统级按钮 token（`.ghost-btn.danger`）仍留在全局样式，故同时读取两者。
 */
const css = readFileSync(
  resolve(
    process.cwd(),
    'src',
    'features',
    'ai',
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
  it('wraps the header with no horizontal scroll and pins the toggle to the first row top-right', () => {
    const summary = rule(css, /\.thread-tool-summary\s*\{[^}]*\}/)
    const detail = rule(css, /\.thread-tool-summary-detail\s*\{[^}]*\}/)
    const toggle = rule(css, /\.thread-tool-toggle\s*\{[^}]*\}/)

    // 参数与名称属于同一 inline 流，不把 detail 作为 flex 整块提前下移。
    expect(summary).toContain('display: block')
    expect(summary).not.toContain('flex-wrap')
    expect(detail).not.toContain('flex:')
    expect(detail).toContain('white-space: pre-wrap')
    expect(detail).toContain('overflow-wrap: anywhere')
    expect(detail).not.toContain('white-space: nowrap')
    expect(detail).not.toContain('overflow-x: auto')
    expect(detail).not.toContain('text-overflow: ellipsis')
    // 状态与展开按钮占独立、不收缩的尾部，不覆盖参数。
    const tail = rule(css, /\.thread-tool-tail\s*\{[^}]*\}/)
    expect(tail).toContain('flex: 0 0 auto')
    expect(toggle).toContain('width: 28px')
    expect(toggle).toContain('opacity: 1')
    expect(toggle).not.toContain('margin-left: auto')
  })

  it('uses exactly one bounded readonly viewport with monospace text and native boundary chaining', () => {
    const output = rule(css, /\.thread-tool-output\s*\{[^}]*\}/)
    const pre = rule(css, /\.thread-tool-pre\s*\{[^}]*margin: 0[^}]*\}/)

    // 唯一只读视口：有界高度 + 内部滚动 + 等宽字体；边界处滚轮自然链到外层。
    expect(output).toContain('max-height')
    expect(output).toContain('overflow: auto')
    expect(output).toContain('overscroll-behavior: auto')
    expect(pre).toContain('font-family: var(--mono)')
    // 不再有旧的展开态/行预算契约。
    expect(css).not.toContain('.thread-tool-output.is-expanded')
    expect(css).not.toMatch(/--thread-tool-output-lines/)
  })

  it('uses the danger token for approval rejection borders', () => {
    const dangerButton = rule(globalCss, /\.ghost-btn\.danger\s*\{[^}]*\}/)

    // 组件只需声明 danger；该规则确保最终视觉始终使用设计系统的红色边框。
    expect(dangerButton).toContain('border-color: var(--danger-border)')
    expect(dangerButton).toContain('color: var(--danger)')
  })
})
