import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 快捷键分组标题的大小写契约：拉丁语标题保留大写小标题样式，
 * 中文下不做大写变换，产品术语（如 Thread）保留原拼写。
 */
const styles = readFileSync(resolve(process.cwd(), 'src', 'styles.css'), 'utf8')

function rule(pattern: RegExp): string {
  const match = styles.match(pattern)
  expect(match).not.toBeNull()
  return match?.[0] ?? ''
}

describe('shortcut group title casing', () => {
  it('keeps the uppercase eyebrow for latin locales', () => {
    expect(rule(/^\.shortcut-group-title\s*\{[^}]*\}/m)).toContain('text-transform: uppercase')
  })

  it('leaves Chinese group titles untransformed', () => {
    expect(rule(/^:lang\(zh\) \.shortcut-group-title\s*\{[^}]*\}/m)).toContain(
      'text-transform: none',
    )
  })
})
