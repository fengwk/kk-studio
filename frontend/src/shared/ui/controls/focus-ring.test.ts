import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// 焦点环厚度是跨控件的统一契约：--focus-ring 只在 2px 的 box-shadow 环上使用，与全局 2px --focus 轮廓一致。
// 静态扫描 src 下全部样式，避免 Select/Checkbox 之类控件再长出更粗的焦点环特例。
const SRC = resolve(__dirname, '../../..')
const RING = /box-shadow:\s*0 0 0 (\S+?) var\(--focus-ring\)/

type Ring = { file: string; selector: string; width: string }

function cssFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      return cssFiles(full)
    }
    return entry.name.endsWith('.css') ? [full] : []
  })
}

function focusRings(): Ring[] {
  return cssFiles(SRC).flatMap((file) => {
    const rings: Ring[] = []
    let selector = ''
    for (const line of readFileSync(file, 'utf8').split('\n')) {
      const trimmed = line.trim()
      if (trimmed.endsWith('{')) {
        selector = trimmed.slice(0, -1).trim()
      }
      const match = RING.exec(trimmed)
      if (match) {
        rings.push({ file: relative(SRC, file), selector, width: match[1] })
      }
    }
    return rings
  })
}

describe('focus ring width', () => {
  it('uses 2px wherever --focus-ring is applied', () => {
    expect(focusRings().filter((ring) => ring.width !== '2px')).toEqual([])
  })

  it('covers the select trigger and checkbox box rings', () => {
    // 防退化：正则或目录改动若停止命中这两个控件，上面的契约就失去意义。
    const rings = focusRings().filter((ring) => ring.file === join('shared', 'ui', 'controls', 'controls.css'))
    expect(rings).toContainEqual({
      file: join('shared', 'ui', 'controls', 'controls.css'),
      selector: '.ui-select-trigger:focus-visible',
      width: '2px',
    })
    expect(rings).toContainEqual({
      file: join('shared', 'ui', 'controls', 'controls.css'),
      selector: '.ui-checkbox-input:focus-visible + .ui-checkbox-box',
      width: '2px',
    })
  })
})
