import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(resolve(process.cwd(), 'src', 'styles.css'), 'utf8')

function rule(pattern: RegExp): string {
  const match = css.match(pattern)
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

describe('tool card style contracts', () => {
  it('keeps the header single-line with a reserved gutter for the rightmost toggle', () => {
    const header = rule(/\.thread-tool-header\s*\{[^}]*\}/)
    const summary = rule(/\.thread-tool-summary\s*\{[^}]*\}/)
    const detail = rule(/\.thread-tool-summary-detail\s*\{[^}]*\}/)
    const toggle = rule(/\.thread-tool-toggle\s*\{[^}]*\}/)

    // Header 恒为单行：参数区自己横向滚动，未知/MCP 的数千字 JSON 也不撑高卡片。
    expect(summary).toContain('flex-wrap: nowrap')
    expect(detail).toContain('white-space: nowrap')
    expect(detail).toContain('overflow-x: auto')
    expect(detail).toContain('overflow-y: hidden')
    expect(detail).toContain('overflow-wrap: normal')
    expect(detail).not.toContain('text-overflow: ellipsis')
    // 右侧为箭头预留固定留白，箭头绝不覆盖参数文本，也不占用 flex 槽位。
    expect(header).toContain('padding: 0 22px 0 0')
    expect(toggle).toContain('position: absolute')
    expect(toggle).not.toContain('flex: 0 0 20px')
    expect(toggle).not.toContain('margin-left: auto')
  })

  it('uses exactly one bounded readonly viewport that contains inner scrollback', () => {
    const output = rule(/\.thread-tool-output\s*\{[^}]*\}/)

    // 唯一只读视口：有界高度 + 内部滚动，且内部回看不把外层卡片滚走。
    expect(output).toContain('max-height')
    expect(output).toContain('overflow: auto')
    expect(output).toContain('overscroll-behavior: contain')
    // 不再有旧的展开态/行预算契约。
    expect(css).not.toContain('.thread-tool-output.is-expanded')
    expect(css).not.toMatch(/--thread-tool-output-lines/)
  })

  it('uses the danger token for approval rejection borders', () => {
    const dangerButton = rule(/\.ghost-btn\.danger\s*\{[^}]*\}/)

    // 组件只需声明 danger；该规则确保最终视觉始终使用设计系统的红色边框。
    expect(dangerButton).toContain('border-color: var(--danger-border)')
    expect(dangerButton).toContain('color: var(--danger)')
  })
})
