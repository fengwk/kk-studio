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
  it('wraps the complete header while the hover toggle stays out of layout flow', () => {
    const summary = rule(/\.thread-tool-summary\s*\{[^}]*\}/)
    const detail = rule(/\.thread-tool-summary-detail\s*\{[^}]*\}/)
    const toggle = rule(/\.thread-tool-toggle\s*\{[^}]*\}/)

    // 直接约束导致截图回归的 CSS：摘要可折行，透明按钮不保留 flex 槽位。
    expect(summary).toContain('flex-wrap: wrap')
    expect(summary).toContain('overflow: visible')
    expect(detail).toContain('white-space: normal')
    expect(detail).toContain('overflow-wrap: anywhere')
    expect(detail).not.toContain('text-overflow: ellipsis')
    expect(toggle).toContain('position: absolute')
    expect(toggle).not.toContain('flex: 0 0 20px')
    expect(toggle).not.toContain('margin-left: auto')
  })

  it('keeps preview/output regions out of the nested vertical scroll chain', () => {
    const preview = rule(/\.thread-tool-preview-body\s*\{[^}]*\}/)
    const output = rule(/\.thread-tool-output\s*\{[^}]*\}/)
    const expandedOutput = rule(/\.thread-tool-output\.is-expanded\s*\{[^}]*\}/)

    // 文本由 formatter 按行裁剪，内部只做 clip，滚轮应始终由 transcript 接管。
    expect(preview).toContain('overflow: hidden')
    expect(output).toContain('overflow: hidden')
    expect(output).not.toContain('overscroll-behavior')
    expect(expandedOutput).toContain('overflow: visible')
  })

  it('uses the danger token for approval rejection borders', () => {
    const dangerButton = rule(/\.ghost-btn\.danger\s*\{[^}]*\}/)

    // 组件只需声明 danger；该规则确保最终视觉始终使用设计系统的红色边框。
    expect(dangerButton).toContain('border-color: var(--danger-border)')
    expect(dangerButton).toContain('color: var(--danger)')
  })
})
