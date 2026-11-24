import { describe, expect, it } from 'vitest'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { checkCss, debtCounts, selectorTargets } from './check-css.mjs'

describe('CSS ownership selector targets', () => {
  it('checks only the rightmost target and ignores classes inside :not/:has', () => {
    expect(selectorTargets('.card .button:not(.danger):has(.icon)')).toEqual(['button'])
  })

  it('handles comma-separated target selectors independently', () => {
    expect(selectorTargets('.card:hover, .button:focus-visible')).toEqual(['card', 'button'])
  })

  it('handles nested selector functions without confusing their commas with outer separators', () => {
    expect(selectorTargets('.layout :is(.button:not(:where(.danger, .is-active)), .row .link)')).toEqual(['button', 'link'])
  })
})

it('counts only declarations outside the visual contract', () => {
  expect(debtCounts('src/styles.css', `
    :where(button):focus-visible { outline: 1px solid var(--border); outline-offset: 1px; }
    .ok { padding: 0 1px calc(var(--space-2) - 1px); box-shadow: var(--shadow-md); }
    @media (forced-colors: active) { .ok { color: Highlight !important; } }
    @media (prefers-reduced-motion: reduce) { .ok { animation: none !important; } }
    .bad { padding: calc(var(--space-2) + 2px); gap: 0 var(--control-h); box-shadow: 0 0 2px; }
    .bad { outline-offset: 2px; transition: opacity 120ms, all 200ms; color: inherit !important; }
    @media (forced-colors: active), print { .bad { color: inherit !important; } }
  `)).toEqual({ spacing: 2, 'box-shadow': 1, outline: 1, 'transition-all': 1, important: 2 })
})

it('rejects a new debt file and an override even when a baseline already exists', () => {
  const root = mkdtempSync(join(tmpdir(), 'check-css-'))
  try {
    mkdirSync(join(root, 'src/shared/ui/controls'), { recursive: true })
    mkdirSync(join(root, 'scripts'))
    writeFileSync(join(root, 'src/styles.css'), ':root { /* @palette:start */ /* @palette:end */ }\n')
    writeFileSync(join(root, 'src/shared/ui/controls/Button.css'), '.button { padding: 0; }\n')
    writeFileSync(join(root, 'src/page.css'), '.page .button { padding: 3px; }\n')
    writeFileSync(join(root, 'scripts/check-css-baseline.json'), '{}\n')
    expect(checkCss(root, { updateBaseline: true })).toEqual([
      'src/page.css [spacing] 1 exceeds baseline 0',
      'src/page.css:1 [shared-owner] .button belongs to src/shared/ui/controls/Button.css',
    ])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
