import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { DWC_SLOT_CODE, glyphFragments } from '../terminal-grid'
import { gridFromCapturedView, type CapturedView } from './captured-view'

const here = path.dirname(fileURLToPath(import.meta.url))
const fixtures = JSON.parse(
  readFileSync(path.join(here, 'captured-views.json'), 'utf8'),
) as Array<{ name: string; view: CapturedView }>

function fixture(name: string): CapturedView {
  const found = fixtures.find((entry) => entry.name === name)
  if (!found) {
    throw new Error(`missing fixture ${name}`)
  }
  return found.view
}

describe('captured view adapter', () => {
  // 16 份真实快照无损转换：每行槽数严格等于 cols，历史/备用屏元数据保留。
  it('converts every captured view with exactly cols slots per line', () => {
    expect(fixtures.length).toBe(16)
    for (const { name, view } of fixtures) {
      const grid = gridFromCapturedView(view)
      expect(grid.lines.length, name).toBe(view.lines.length)
      for (const line of grid.lines) {
        expect(line.slots.length, name).toBe(view.cols)
      }
      expect(grid.cols, name).toBe(view.cols)
      expect(grid.history, name).toBe(view.history)
      expect(grid.alternate, name).toBe(view.alternate)
    }
  })

  it('keeps DWC continuation and trims padding into explicit empty slots', () => {
    const line = gridFromCapturedView(fixture('cjk-emoji')).lines[0]
    expect(line.slots[0]).toMatchObject({ kind: 'unit', code: '中'.charCodeAt(0) })
    expect(line.slots[1]).toMatchObject({ kind: 'dwc', code: DWC_SLOT_CODE })
    expect(line.slots[4]).toMatchObject({ kind: 'unit', code: 'Z'.charCodeAt(0) })
    expect(line.slots[5]).toMatchObject({ kind: 'empty', code: 0 })
  })

  // 孤立代理逐 UTF-16 unit 保留，绝不迭代码点丢弃。
  it('preserves lone surrogates split across a soft wrap', () => {
    const [first, second] = gridFromCapturedView(fixture('surrogate-at-wrap')).lines
    expect(first.wrapped).toBe(true)
    expect(first.slots[7].code).toBe(0xd83d)
    expect(second.slots[0].code).toBe(0xde00)
  })

  // 捕获样式补齐为完整 SlotStyle：真彩转 rgb，缺省位为 false。
  it('normalizes captured styles', () => {
    expect(gridFromCapturedView(fixture('truecolor')).lines[0].slots[0].style).toMatchObject({
      bold: true,
      fg: { kind: 'rgb', r: 123, g: 45, b: 67 },
      italic: false,
    })
    expect(gridFromCapturedView(fixture('ascii')).lines[0].slots[0].style.fg).toBeNull()
  })

  // 不变量：字形格 + 空槽严格铺满整行。
  it('tiles every fixture line exactly', () => {
    for (const { name, view } of fixtures) {
      for (const gridLine of gridFromCapturedView(view).lines) {
        const fragments = glyphFragments(gridLine)
        const emptyCount = gridLine.slots.filter((slot) => slot.kind === 'empty').length
        const covered = fragments.reduce((sum, f) => sum + f.span, 0)
        expect(covered + emptyCount, name).toBe(view.cols)
      }
    }
  })
})
