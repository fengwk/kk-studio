import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Composer 提示条的样式契约：失败原因必须完整可读。
 *
 * toast 只承载需要用户读完的错误原因，因此消息文本允许换行、不做单行省略，
 * 关闭入口在换行后仍然留在原位；普通提示的时长由 useComposerToast 决定。
 */
const css = readFileSync(
  resolve(
    process.cwd(),
    'src',
    'features',
    'thread',
    'runtime',
    'thread-panel',
    'thread-composer.css',
  ),
  'utf8',
)

/** 只匹配以该选择器开头的规则本体，避免命中复合选择器。 */
function rule(source: string, pattern: RegExp): string {
  const match = source.match(pattern)
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

describe('composer toast style contracts', () => {
  it('wraps the failure message instead of truncating it to one line', () => {
    const message = rule(css, /^\.composer-toast-message\s*\{[^}]*\}/m)

    expect(message).not.toContain('white-space: nowrap')
    expect(message).not.toContain('text-overflow: ellipsis')
    expect(message).toContain('white-space: pre-wrap')
    expect(message).toContain('overflow-wrap: anywhere')
  })

  it('bounds the failure detail and keeps the dismiss control reachable', () => {
    const toast = rule(css, /^\.composer-toast\s*\{[^}]*\}/m)
    const message = rule(css, /^\.composer-toast-message\s*\{[^}]*\}/m)

    // 极长的服务端错误不能让提示条盖住整个会话；有界滚动仍可读到全文。
    expect(message).toContain('max-height: min(24em, 50vh)')
    expect(message).toContain('overflow-y: auto')
    // 关闭按钮跟随首行，换行后的长文本不会把它推离可视区。
    expect(toast).toContain('align-items: flex-start')
  })
})
