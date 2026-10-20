import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

// The style gate (scripts/check-css.mjs) enforces the token contract; these tests pin both the
// real tree and the rules themselves so the gate cannot silently weaken.
const SCRIPT = resolve(__dirname, '../scripts/check-css.mjs')

const TOKENS = `:root {
  /* @palette:start */
  --palette-ink-900: #101412;
  /* @palette:end */
  --bg: var(--palette-ink-900);
  --font-size-sm: 13px;
}
`

function runGate(root: string) {
  const result = spawnSync(process.execPath, [SCRIPT, '--root', root], { encoding: 'utf8' })
  return { ok: result.status === 0, output: `${result.stdout}${result.stderr}` }
}

describe('style gate on the real tree', () => {
  it('passes for the whole frontend', () => {
    const result = runGate(resolve(__dirname, '..'))
    expect(result.output).toContain('style gate passed')
    expect(result.ok).toBe(true)
  })
})

describe('style gate rules', () => {
  const dirs: string[] = []
  afterEach(() => {
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true })
    }
  })

  function fixture(feature: string) {
    const root = mkdtempSync(join(tmpdir(), 'css-gate-'))
    dirs.push(root)
    mkdirSync(join(root, 'src'))
    writeFileSync(join(root, 'src/styles.css'), TOKENS)
    writeFileSync(join(root, 'src/feature.css'), feature)
    return root
  }

  it.each([
    ['undefined custom property', '.a { color: var(--missing); }', 'undefined-var'],
    ['var fallback', '.a { color: var(--bg, #fff); }', 'fallback'],
    ['raw hex color', '.a { color: #fff; }', 'raw-color'],
    ['rgba color', '.a { color: rgba(0, 0, 0, 0.5); }', 'raw-color'],
    ['named color', '.a { color: white; }', 'raw-color'],
    ['palette token in a component', '.a { color: var(--palette-ink-900); }', 'palette-layer'],
    ['px font size', '.a { font-size: 11px; }', 'font-size'],
    ['rem font size', '.a { font-size: 0.875rem; }', 'font-size'],
    ['font shorthand', '.a { font: 12px/1.4 serif; }', 'font-size'],
    ['large z-index', '.a { z-index: 999; }', 'z-index'],
  ])('rejects %s', (_name, css, rule) => {
    const result = runGate(fixture(css))
    expect(result.ok).toBe(false)
    expect(result.output).toContain(`[${rule}]`)
  })

  it('accepts scale tokens, local stacking and semantic colors', () => {
    const css = '.a { color: var(--bg); font-size: var(--font-size-sm); z-index: 2; font: inherit; }'
    expect(runGate(fixture(css)).ok).toBe(true)
  })
})
