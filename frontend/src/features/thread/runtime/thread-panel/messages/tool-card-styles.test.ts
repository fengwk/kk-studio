import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 工具卡片的样式契约：Header 内容、固定短窗口、有界详情与 mutation 预览。
 *
 * 卡片外壳、chevron 与错误槽位由 ThreadDisclosure 提供；本测试同时锁定
 * 「短窗口不折行、只有详情和审批卡片内部滚动」以及「静态提示行不可点击」这两条
 * 用户可见的边界。
 */
const messagesDir = resolve(
  process.cwd(),
  'src',
  'features',
  'thread',
  'runtime',
  'thread-panel',
  'messages',
)
const css = readFileSync(resolve(messagesDir, 'tool-card.css'), 'utf8')
const disclosureCss = readFileSync(resolve(messagesDir, 'thread-disclosure.css'), 'utf8')
const globalCss = readFileSync(resolve(process.cwd(), 'src', 'styles.css'), 'utf8')

/** 只匹配以该选择器开头的规则本体，避免命中复合选择器。 */
function rule(source: string, pattern: RegExp): string {
  const match = source.match(pattern)
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

describe('tool card style contracts', () => {
  it('wraps the header parameters without truncating the complete text', () => {
    const summary = rule(css, /^\.thread-tool-summary\s*\{[^}]*\}/m)
    const detail = rule(css, /^\.thread-tool-summary-detail\s*\{[^}]*\}/m)

    expect(summary).toContain('display: block')
    expect(summary).toContain('white-space: pre-wrap')
    expect(summary).toContain('overflow-wrap: anywhere')
    expect(detail).toContain('white-space: pre-wrap')
    expect(detail).toContain('overflow-wrap: anywhere')
    expect(detail).not.toContain('text-overflow: ellipsis')
    expect(detail).not.toContain('overflow-x: auto')
  })

  it('keeps the fixed short window unwrapped and bounds only the detail region', () => {
    const output = rule(css, /^\.thread-tool-output\s*\{[^}]*\}/m)
    const shortWindow = rule(css, /^\.thread-tool-short-preview \.thread-tool-output\s*\{[^}]*\}/m)
    const details = rule(css, /^\.thread-tool-details\s*\{[^}]*\}/m)
    const bounded = rule(css, /^\.thread-tool-output\.is-bounded\s*\{[^}]*\}/m)
    const pre = rule(css, /\.thread-tool-pre\s*\{[^}]*margin: 0[^}]*\}/)

    // 基础正文容器自身不滚动：滚动只发生在详情区和审批卡片上。
    expect(output).not.toContain('max-height')
    expect(output).not.toContain('overflow: auto')
    expect(shortWindow).toContain('white-space: pre')
    expect(shortWindow).toContain('overflow-x: auto')

    // 详情区是有界的完整记录，键盘可滚动并到达边界后链到外层。
    expect(details).toContain('max-height: min(24em, 42vh)')
    expect(details).toContain('overflow: auto')
    expect(details).toContain('overscroll-behavior: auto')
    expect(rule(css, /^\.thread-tool-details:focus-visible\s*\{[^}]*\}/m))
      .toContain('outline: 1px solid var(--focus-ring)')

    expect(bounded).toContain('max-height')
    expect(bounded).toContain('overflow: auto')
    expect(bounded).toContain('overscroll-behavior: auto')
    expect(pre).toContain('font-family: var(--mono)')
  })

  it('uses one neutral shell for every tool state and no success marker', () => {
    const neutral = rule(css, /^\.thread-block\.thread-block-tool\.is-neutral\s*\{[^}]*\}/m)

    expect(neutral).toContain('background: var(--surface)')
    // 成功不再用勾选图标，失败也不再换掉外壳底色：状态只由 Header 标签与数据属性表达。
    expect(css).not.toContain('.thread-tool-success-icon')
    expect(css).not.toContain('.thread-tool-approval-note')
  })

  it('leaves error framing to the independent disclosure error slot', () => {
    const error = rule(css, /^\.thread-tool-error\s*\{[^}]*\}/m)
    const slot = rule(disclosureCss, /^\.thread-disclosure-error-slot,[^{]*\{[^}]*\}/m)

    // 错误块在槽位内不再自带上间距/下间距，间隔由槽位统一提供。
    expect(error).toContain('margin: 0')
    expect(slot).toContain('.thread-disclosure-preview')
    expect(slot).toContain('.thread-disclosure-body')
    expect(slot).toContain('margin-top: 8px')
    expect(slot).toContain('border-top: 1px solid var(--border)')
  })

  it('only gives the clickable hint a pointer cursor', () => {
    const hint = rule(disclosureCss, /^\.thread-disclosure-hint\s*\{[^}]*\}/m)
    const buttonHint = rule(disclosureCss, /^button\.thread-disclosure-hint\s*\{[^}]*\}/m)

    // bash/write/edit 的省略计数是静态文本，不能看起来可点击。
    expect(hint).not.toContain('cursor: pointer')
    expect(buttonHint).toContain('cursor: pointer')
  })

  it('styles the header subagent action without a UUID-width layout', () => {
    const link = rule(css, /^\.thread-tool-thread-link\s*\{[^}]*\}/m)
    expect(link).toContain('display: inline-flex')
    expect(link).toContain('white-space: nowrap')
    expect(link).not.toContain('overflow-wrap: anywhere')
  })

  it('keeps the approval review diff behind a native summary', () => {
    const summary = rule(css, /^\.thread-tool-approval-review > summary\s*\{[^}]*\}/m)

    expect(rule(css, /^\.thread-tool-approval-review\s*\{[^}]*\}/m)).toContain('margin-top: 8px')
    expect(summary).toContain('cursor: pointer')
  })

  it('uses the danger token for approval rejection borders', () => {
    const dangerButton = rule(globalCss, /\.ghost-btn\.danger\s*\{[^}]*\}/)

    // 组件只需声明 danger；该规则确保最终视觉始终使用设计系统的红色边框。
    expect(dangerButton).toContain('border-color: var(--danger-border)')
    expect(dangerButton).toContain('color: var(--danger)')
  })
})
