import { describe, expect, it } from 'vitest'
import { gridFromCapturedView, type CapturedView } from './terminal-grid'
import {
  copySelectedText,
  cursorSelectionLine,
  isSelectionEmpty,
  lineEnd,
  normalizeSelection,
  selectionRanges,
  wholeLinesSelection,
} from './terminal-selection'

function view(lines: Array<{ wrapped?: boolean; text: string }>, cols = 8): CapturedView {
  return {
    cols,
    rows: lines.length,
    cursorX: 0,
    cursorY: Math.max(0, lines.length - 1),
    alternate: false,
    history: 0,
    lines: lines.map((line) => ({
      wrapped: line.wrapped ?? false,
      text: line.text,
      styles: Array.from({ length: line.text.length }, () => ({ bold: false, fg: null })),
    })),
  }
}

describe('virtual slot selection model', () => {
  it('normalizes reversed and empty selections', () => {
    expect(normalizeSelection({ anchor: { line: 2, x: 3 }, focus: { line: 1, x: 5 } })).toEqual({
      anchor: { line: 1, x: 5 },
      focus: { line: 2, x: 3 },
    })
    expect(normalizeSelection({ anchor: { line: 1, x: 3 }, focus: { line: 1, x: 3 } })).toEqual({
      anchor: { line: 1, x: 3 },
      focus: { line: 1, x: 3 },
    })
    expect(isSelectionEmpty({ anchor: { line: 0, x: 2 }, focus: { line: 0, x: 2 } })).toBe(true)
    expect(isSelectionEmpty({ anchor: { line: 0, x: 2 }, focus: { line: 1, x: 0 } })).toBe(false)
  })

  it('produces one range per selected line with clamped endpoints', () => {
    const grid = gridFromCapturedView(view([{ text: 'ABC' }, { text: 'DEF' }, { text: 'GHI' }], 4))
    expect(
      selectionRanges(grid, { anchor: { line: 0, x: 1 }, focus: { line: 2, x: 2 } }),
    ).toEqual([
      { line: 0, xFrom: 1, xTo: 4 },
      { line: 1, xFrom: 0, xTo: 4 },
      { line: 2, xFrom: 0, xTo: 2 },
    ])
    expect(selectionRanges(grid, { anchor: { line: 1, x: 2 }, focus: { line: 1, x: 2 } })).toEqual(
      [],
    )
  })
})

describe('pure slot-based copy', () => {
  it('joins soft wraps without LF and hard wraps with LF', () => {
    const soft = gridFromCapturedView(
      view([
        { wrapped: true, text: '12345678' },
        { wrapped: true, text: '90123456' },
        { text: '78' },
      ]),
    )
    expect(copySelectedText(soft, wholeLinesSelection(soft, 0, 2))).toBe('123456789012345678')

    const hard = gridFromCapturedView(view([{ text: 'ABC' }, { text: 'DEF' }], 5))
    expect(copySelectedText(hard, wholeLinesSelection(hard, 0, 1))).toBe('ABC\nDEF')
  })

  it('copies a sub-range by slot endpoints', () => {
    const grid = gridFromCapturedView(view([{ text: 'ABCDE' }], 6))
    expect(copySelectedText(grid, { anchor: { line: 0, x: 1 }, focus: { line: 0, x: 3 } })).toBe('BC')
    expect(copySelectedText(grid, { anchor: { line: 0, x: 3 }, focus: { line: 0, x: 1 } })).toBe('BC')
  })

  // 软换行续行与行尾 EMPTY 填充不进入文本，历史空行保留为空行。
  it('trims trailing padding and keeps history blank lines', () => {
    const grid = gridFromCapturedView(
      view([{ text: '' }, { text: '' }, { text: '' }, { text: 'end' }]),
    )
    expect(copySelectedText(grid, wholeLinesSelection(grid, 0, 3))).toBe('\n\n\nend')
  })

  // DWC 槽不输出字符；FEFF 与孤立代理按 unit 原样保留；NUL 输出空格。
  it('projects slot kinds faithfully', () => {
    const wide = gridFromCapturedView(view([{ text: '中\ue000Z' }], 4))
    expect(copySelectedText(wide, wholeLinesSelection(wide, 0, 0))).toBe('中Z')

    const bom = gridFromCapturedView(view([{ text: 'A\ufeffB' }], 4))
    expect(copySelectedText(bom, wholeLinesSelection(bom, 0, 0))).toBe('A\ufeffB')

    const lone = gridFromCapturedView(view([{ text: '\ud83d' }], 4))
    expect(copySelectedText(lone, wholeLinesSelection(lone, 0, 0))).toBe('\ud83d')

    const nul = gridFromCapturedView(view([{ text: '\u0000B' }], 4))
    expect(copySelectedText(nul, wholeLinesSelection(nul, 0, 0))).toBe(' B')
  })

  it('returns empty text for an empty selection', () => {
    const grid = gridFromCapturedView(view([{ text: 'ABC' }], 8))
    expect(copySelectedText(grid, { anchor: { line: 0, x: 2 }, focus: { line: 0, x: 2 } })).toBe('')
  })

  // 选择恰好在行边界（x==cols）时，该行输出空串但仍按 wrapped 决定是否换行。
  it('handles selections anchored at the line boundary', () => {
    const grid = gridFromCapturedView(
      view([{ wrapped: true, text: '12345678' }, { text: 'AB' }]),
    )
    expect(
      copySelectedText(grid, { anchor: { line: 0, x: 8 }, focus: { line: 1, x: 1 } }),
    ).toBe('A')
  })

  it('exposes cursor line and line helpers', () => {
    const view_: CapturedView = {
      cols: 4,
      rows: 2,
      cursorX: 1,
      cursorY: 1,
      alternate: false,
      history: 3,
      lines: Array.from({ length: 5 }, () => ({ wrapped: false, text: '', styles: [] })),
    }
    const grid = gridFromCapturedView(view_)
    expect(cursorSelectionLine(grid)).toBe(4)
    expect(lineEnd(grid, 0)).toEqual({ line: 0, x: 4 })
  })
})
