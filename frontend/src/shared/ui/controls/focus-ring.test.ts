import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'
import postcss from 'postcss'

it('keeps shared focus on one global outline with forced-color support', () => {
  const css = postcss.parse(readFileSync(resolve(__dirname, '../../../styles.css'), 'utf8'))
  const rings: string[] = []
  css.walkDecls('outline', (declaration) => {
    // The base focus contract must not couple itself to component class names.
    expect(declaration.parent?.type).toBe('rule')
    if (declaration.parent?.type === 'rule') {
      expect(declaration.parent.selector).not.toMatch(/\.[a-z][\w-]*/i)
    }
    if (declaration.value !== 'none') rings.push(declaration.value)
  })
  expect(rings).toEqual(['1px solid var(--focus)'])
  expect(css.toString().match(/--focus: var\(--border\)/g)).toHaveLength(1)
  expect(css.toString()).toContain('outline-offset: var(--focus-offset)')
  expect(css.toString()).toMatch(/@media \(forced-colors: active\)[^}]*--focus: Highlight/)
})
