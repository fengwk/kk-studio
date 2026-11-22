import fs from 'node:fs'
import path from 'node:path'
import type { TestInfo } from '@playwright/test'
import { expect, type Locator } from './fixture'

export async function readSearchHitText(scope: Locator) {
  return scope.evaluate((root) => {
    const box = root.querySelector<HTMLElement>('.search-hit-text')!
    const prefix = box.querySelector<HTMLElement>('.search-hit-prefix')
    const match = box.querySelector<HTMLElement>('.search-hit-match')!
    const mark = match.querySelector('mark')!
    const scopeRect = root.getBoundingClientRect()
    const boxRect = box.getBoundingClientRect()
    const markRect = mark.getBoundingClientRect()
    const matchRect = match.getBoundingClientRect()
    const firstText = document.createTreeWalker(box, NodeFilter.SHOW_TEXT).nextNode()!
    const firstGlyph = document.createRange()
    firstGlyph.setStart(firstText, 0)
    firstGlyph.setEnd(firstText, (firstText.textContent!.codePointAt(0) ?? 0) > 0xffff ? 2 : 1)

    // The painted ellipsis can cover the end of a mark whose DOM rectangle still fits.
    let matchVisibleRight = matchRect.right
    if (match.scrollWidth > match.clientWidth) {
      const style = getComputedStyle(match)
      const canvas = document.createElement('canvas').getContext('2d')!
      canvas.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
      matchVisibleRight -= canvas.measureText('…').width
    }
    return {
      text: box.textContent ?? '',
      availableWidth: boxRect.width,
      markWidth: markRect.width,
      markLeft: markRect.left - boxRect.left,
      markRight: markRect.right - boxRect.left,
      visibleLeft: Math.max(boxRect.left, scopeRect.left, matchRect.left) - boxRect.left,
      visibleRight: Math.min(boxRect.right, scopeRect.right, matchVisibleRight) - boxRect.left,
      firstGlyphLeft: firstGlyph.getBoundingClientRect().left - Math.max(boxRect.left, scopeRect.left),
      prefixWidth: prefix?.getBoundingClientRect().width ?? null,
    }
  })
}

type Geometry = Awaited<ReturnType<typeof readSearchHitText>>

export function expectFirstMarkVisible(geometry: Geometry, label: string): void {
  expect(geometry.markWidth, label).toBeGreaterThan(0)
  expect(geometry.markLeft, label).toBeGreaterThanOrEqual(geometry.visibleLeft - 0.5)
  expect(geometry.markRight, label).toBeLessThanOrEqual(geometry.visibleRight + 0.5)
}

export function expectNoLeadingClipping(geometry: Geometry, label: string): void {
  expect(geometry.firstGlyphLeft, label).toBeGreaterThanOrEqual(-0.5)
}

const METRICS_DIR = process.env.KK_SEARCH_HIT_METRICS_DIR
const SHOTS_DIR = process.env.KK_SEARCH_HIT_SHOTS_DIR

export function recordSearchHitMetrics(testInfo: TestInfo, name: string, geometry: Geometry): void {
  if (METRICS_DIR == null) {
    return
  }
  fs.mkdirSync(METRICS_DIR, { recursive: true })
  const record = { name, test: testInfo.title, ...geometry }
  fs.appendFileSync(path.join(METRICS_DIR, 'search-hit-metrics.jsonl'), `${JSON.stringify(record)}\n`)
}

export async function captureSearchHitShot(region: Locator, testInfo: TestInfo, name: string): Promise<void> {
  const file = SHOTS_DIR == null ? testInfo.outputPath(`${name}.png`) : path.join(SHOTS_DIR, `${name}.png`)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  await region.screenshot({ path: file })
  await testInfo.attach(name, { path: file, contentType: 'image/png' })
}
