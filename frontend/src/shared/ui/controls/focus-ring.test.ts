import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'
import postcss from 'postcss'

it('keeps shared focus on one global outline with forced-color support', () => {
  const css = postcss.parse(readFileSync(resolve(__dirname, '../../../styles.css'), 'utf8'))
  const rings: string[] = []
  css.walkDecls('outline', (declaration) => {
    if (declaration.value !== 'none') rings.push(declaration.value)
  })
  expect(rings).toEqual(['1px solid var(--focus)'])
  expect(css.toString()).toContain('--focus: var(--border)')
  expect(css.toString()).toMatch(/@media \(forced-colors: active\)[^}]*--focus: Highlight/)
})
