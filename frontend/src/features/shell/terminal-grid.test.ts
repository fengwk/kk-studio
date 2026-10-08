import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { DEFAULT_SLOT_STYLE } from './terminal-style'
import {
  DWC_SLOT_CODE,
  cursorLineIndex,
  glyphFragments,
  gridFromCapturedView,
  type CapturedView,
  type GridLine,
  type GridSlot,
} from './terminal-grid'

const here = path.dirname(fileURLToPath(import.meta.url))
const fixtures = JSON.parse(
  readFileSync(path.join(here, '__fixtures__/captured-views.json'), 'utf8'),
) as Array<{ name: string; view: CapturedView }>

function fixture(name: string): CapturedView {
  const found = fixtures.find((entry) => entry.name === name)
  if (!found) {
    throw new Error(`missing fixture ${name}`)
  }
  return found.view
}

function unit(code: number): GridSlot {
  return { kind: 'unit', code, style: DEFAULT_SLOT_STYLE }
}

const DWC: GridSlot = { kind: 'dwc', code: DWC_SLOT_CODE, style: DEFAULT_SLOT_STYLE }

describe('terminal grid mirror', () => {
  // 16 份真实快照都必须能无损转换：每行槽数严格等于 cols，历史/备用屏元数据保留。
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
    const grid = gridFromCapturedView(fixture('cjk-emoji'))
    const line = grid.lines[0]
    expect(line.slots[0]).toMatchObject({ kind: 'unit', code: '中'.charCodeAt(0) })
    expect(line.slots[1]).toMatchObject({ kind: 'dwc', code: DWC_SLOT_CODE })
    expect(line.slots[4]).toMatchObject({ kind: 'unit', code: 'Z'.charCodeAt(0) })
    expect(line.slots[5].kind).toBe('empty')
    expect(line.slots[5].code).toBe(0)
  })

  // 孤立代理必须逐 UTF-16 unit 保留，绝不迭代码点丢弃。
  it('preserves lone surrogates split across a soft wrap', () => {
    const grid = gridFromCapturedView(fixture('surrogate-at-wrap'))
    const [first, second] = grid.lines
    expect(first.wrapped).toBe(true)
    expect(first.slots[7].code).toBe(0xd83d)
    expect(second.slots[0].code).toBe(0xde00)
    expect(second.slots[1].code).toBe('Z'.charCodeAt(0))
  })

  // 捕获样式补齐为完整 SlotStyle：真彩转为 rgb，缺省位为 false。
  it('normalizes captured styles', () => {
    const grid = gridFromCapturedView(fixture('truecolor'))
    expect(grid.lines[0].slots[0].style).toMatchObject({
      bold: true,
      fg: { kind: 'rgb', r: 123, g: 45, b: 67 },
      italic: false,
    })
    const ascii = gridFromCapturedView(fixture('ascii'))
    expect(ascii.lines[0].slots[0].style.fg).toBeNull()
  })

  it('tracks the cursor line across history', () => {
    expect(cursorLineIndex(gridFromCapturedView(fixture('ascii')))).toBe(0)
    const reflow = gridFromCapturedView(fixture('resize-reflow'))
    expect(cursorLineIndex(reflow)).toBe(4)
    expect(reflow.lines.length).toBe(5)
  })
})

describe('glyph fragments from topology only', () => {
  const line = (slots: GridSlot[], wrapped = false): GridLine => ({ wrapped, slots })

  it('emits one fragment per unit and skips empty padding', () => {
    const fragments = glyphFragments(line([unit(65), unit(66), unit(67)]))
    expect(fragments.map((f) => [f.x, f.span, f.text])).toEqual([
      [0, 1, 'A'],
      [1, 1, 'B'],
      [2, 1, 'C'],
    ])
  })

  // 宽字符只有被 DWC 续格证明时才占 2 格；被覆写（后随普通 unit）时各占 1 格。
  it('derives wide span from a following DWC and clips an overwritten lead to one cell', () => {
    const wide = glyphFragments(line([unit('中'.charCodeAt(0)), DWC]))
    expect(wide).toHaveLength(1)
    expect(wide[0]).toMatchObject({ x: 0, span: 2, text: '中' })

    const overwritten = glyphFragments(line([unit('中'.charCodeAt(0)), unit('X'.charCodeAt(0))]))
    expect(overwritten.map((f) => [f.x, f.span, f.text])).toEqual([
      [0, 1, '中'],
      [1, 1, 'X'],
    ])
  })

  // 同行 high+low 代理合绘为 2 格字形，但绝不跨行合成。
  it('pairs same-line surrogates and never pairs across a wrap', () => {
    const paired = glyphFragments(line([unit(0xd83d), unit(0xde00), unit(90)]))
    expect(paired.map((f) => [f.x, f.span, f.text])).toEqual([
      [0, 2, '😀'],
      [2, 1, 'Z'],
    ])
    const grid = gridFromCapturedView(fixture('surrogate-at-wrap'))
    const firstGlyphs = glyphFragments(grid.lines[0])
    expect(firstGlyphs[firstGlyphs.length - 1]).toMatchObject({ text: '\ud83d', span: 1 })
    expect(glyphFragments(grid.lines[1])[0].text).toBe('\ude00')
  })

  // ZWJ/selector/组合字符按 Jedi 实际槽独立成格，不 join、不 normalize。
  it('keeps ZWJ, selector and combining marks as independent cells', () => {
    const zwj = glyphFragments(gridFromCapturedView(fixture('zwj-selector')).lines[0])
    expect(zwj.map((f) => f.x)).toEqual([0, 2, 3, 5, 6])
    expect(zwj.map((f) => f.span)).toEqual([2, 1, 2, 1, 1])
    const combining = glyphFragments(gridFromCapturedView(fixture('noncomposing')).lines[0])
    expect(combining.map((f) => f.text)).toEqual(['q', '\u0301', 'Z'])
  })

  // FEFF 保留为 unit 占 1 格；NUL 与空尾槽保持空格语义。
  it('keeps FEFF as its own cell and renders NUL as a space', () => {
    const bom = glyphFragments(gridFromCapturedView(fixture('bom')).lines[0])
    expect(bom.map((f) => f.text)).toEqual(['A', '\ufeff', 'B'])
    expect(glyphFragments(line([unit(0), unit(66)])).map((f) => f.text)).toEqual([' ', 'B'])
  })

  // 连续多个 DWC 续格（异常拓扑）取连续长度作为 span，保持内容与列数一致。
  it('spans consecutive DWC continuations', () => {
    const fragments = glyphFragments(line([unit(88), DWC, DWC, unit(89)]))
    expect(fragments.map((f) => [f.x, f.span, f.text])).toEqual([
      [0, 3, 'X'],
      [3, 1, 'Y'],
    ])
  })

  it('ignores orphan DWC without a lead', () => {
    expect(glyphFragments(line([DWC, unit(65)]))).toEqual([
      { x: 1, span: 1, text: 'A', style: DEFAULT_SLOT_STYLE },
    ])
  })

  // 不变量：字形格 + 空槽 + 续格严格铺满整行，且片段互不重叠、按列递增。
  it('tiles every fixture line exactly and without overlap', () => {
    for (const { name, view } of fixtures) {
      const grid = gridFromCapturedView(view)
      for (const gridLine of grid.lines) {
        const fragments = glyphFragments(gridLine)
        const emptyCount = gridLine.slots.filter((slot) => slot.kind === 'empty').length
        const covered = fragments.reduce((sum, f) => sum + f.span, 0)
        expect(covered + emptyCount, name).toBe(view.cols)
        let expectedX = 0
        for (const fragment of fragments) {
          expect(fragment.x, name).toBeGreaterThanOrEqual(expectedX)
          expectedX = fragment.x + fragment.span
        }
      }
    }
  })
})
