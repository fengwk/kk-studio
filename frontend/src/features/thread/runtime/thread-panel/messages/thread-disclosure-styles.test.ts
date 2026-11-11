import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 容器样式独立于工具卡片；复合选择器必须压过全局 `.thread-block` 的
 * padding/border/background 重置，且容器自身不创建内部滚动。
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
    'thread-disclosure.css',
  ),
  'utf8',
)

function rule(pattern: RegExp): string {
  const match = css.match(pattern)
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

describe('thread disclosure style contracts', () => {
  it('owns the full card surface through the compound thread-block selector', () => {
    const shell = rule(/\.thread-block\.thread-disclosure\s*\{[^}]*\}/)
    expect(shell).toContain('padding: 10px 12px')
    expect(shell).toContain('border: 1px solid var(--border)')
    expect(shell).toContain('background: var(--surface)')
    expect(shell).toContain('border-radius: 10px')
  })

  it.each([
    ['is-pending', 'var(--warning-soft)'],
    ['is-success', 'var(--accent-soft)'],
    ['is-error', 'var(--danger-soft)'],
    ['is-info', 'var(--info-soft)'],
    ['is-neutral', 'var(--scrim)'],
  ])('restores the %s tone background', (tone, token) => {
    const toneRule = rule(new RegExp(`\\.thread-block\\.thread-disclosure\\.${tone}\\s*\\{[^}]*\\}`))
    expect(toneRule).toContain(token)
  })

  it('clamps only the collapsed header and keeps preview/body separated without inner scrolling', () => {
    const clamped = rule(/\.thread-disclosure-header-text\.is-clamped\s*\{[^}]*\}/)
    expect(clamped).toContain('-webkit-box-orient: vertical')
    expect(clamped).toContain('overflow: hidden')
    // 28px 首行盒 = 20px 行高 + 上下 4px，留白在外层，不能加到 clamp 节点上多露一行。
    const main = rule(/\.thread-disclosure-header-main\s*\{[^}]*\}/)
    expect(main).toContain('padding: 4px 0')
    expect(main).toContain('line-height: 20px')
    expect(clamped).not.toContain('padding')

    const preview = rule(/\.thread-disclosure-preview,[\s\S]*?\.thread-disclosure-body\s*\{[^}]*\}/)
    expect(preview).toContain('border-top: 1px solid var(--border)')
    expect(css).not.toContain('max-height')
    expect(css).not.toContain('overflow-y: auto')
  })

  it('defines the universal hint, error block and long-card collapse action', () => {
    expect(rule(/\.thread-disclosure-hint\s*\{[^}]*\}/)).toContain('cursor: pointer')
    expect(rule(/\.thread-disclosure-error\s*\{[^}]*\}/)).toContain('var(--danger)')
    expect(rule(/\.thread-disclosure-collapse\s*\{[^}]*\}/)).toContain('align-self: flex-end')
  })
})
