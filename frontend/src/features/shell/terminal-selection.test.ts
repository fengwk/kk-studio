import { describe, expect, it } from 'vitest'
import { DEFAULT_SLOT_STYLE, type SlotStyle } from './terminal-style'
import { DWC_SLOT_CODE, type GridLine, type GridSlot, type TerminalGrid } from './terminal-grid'
import {
  copySelectedText,
  isSelectionEmpty,
  normalizeSelection,
  selectionRanges,
  type GridSelection,
} from './terminal-selection'

function unit(code: number, style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'unit', code, style }
}
function makeLine(text: string, cols: number, wrapped = false): GridLine {
  const slots: GridSlot[] = []
  for (let x = 0; x < cols; x += 1) {
    if (x < text.length) {
      const code = text.charCodeAt(x)
      slots.push({ kind: code === DWC_SLOT_CODE ? 'dwc' : 'unit', code, style: DEFAULT_SLOT_STYLE })
    } else {
      slots.push({ kind: 'empty', code: 0, style: DEFAULT_SLOT_STYLE })
    }
  }
  return { wrapped, slots }
}
function grid(cols: number, lines: GridLine[], extra: Partial<TerminalGrid> = {}): TerminalGrid {
  return {
    cols,
    rows: lines.length,
    cursorX: 0,
    cursorY: 0,
    alternate: false,
    history: 0,
    lines,
    ...extra,
  }
}
const whole = (from: number, to: number, cols: number): GridSelection => ({
  anchor: { line: from, x: 0 },
  focus: { line: to, x: cols },
})

describe('virtual slot selection model', () => {
  it('normalizes reversed and detects empty selections', () => {
    expect(normalizeSelection({ anchor: { line: 2, x: 3 }, focus: { line: 1, x: 5 } })).toEqual({
      anchor: { line: 1, x: 5 },
      focus: { line: 2, x: 3 },
    })
    expect(isSelectionEmpty({ anchor: { line: 0, x: 2 }, focus: { line: 0, x: 2 } })).toBe(true)
    expect(isSelectionEmpty({ anchor: { line: 0, x: 2 }, focus: { line: 1, x: 0 } })).toBe(false)
  })

  it('produces one range per selected line with clamped endpoints', () => {
    const target = grid(4, [makeLine('ABC', 4), makeLine('DEF', 4), makeLine('GHI', 4)])
    expect(
      selectionRanges(target, { anchor: { line: 0, x: 1 }, focus: { line: 2, x: 2 } }),
    ).toEqual([
      { line: 0, xFrom: 1, xTo: 4 },
      { line: 1, xFrom: 0, xTo: 4 },
      { line: 2, xFrom: 0, xTo: 2 },
    ])
    expect(selectionRanges(target, { anchor: { line: 1, x: 2 }, focus: { line: 1, x: 2 } })).toEqual([])
  })

  // 历史裁剪后越界端点被 clamp 到当前镜像的合法行/x。
  it('clamps out-of-range endpoints to the current mirror', () => {
    const target = grid(4, [makeLine('AB', 4), makeLine('CD', 4)])
    expect(selectionRanges(target, { anchor: { line: -3, x: -5 }, focus: { line: 9, x: 40 } })).toEqual([
      { line: 0, xFrom: 0, xTo: 4 },
      { line: 1, xFrom: 0, xTo: 4 },
    ])
    expect(copySelectedText(target, { anchor: { line: 7, x: 9 }, focus: { line: 8, x: 9 } })).toBe('')
  })
})

describe('pure slot-based copy', () => {
  it('joins soft wraps without LF and hard wraps with LF', () => {
    const soft = grid(8, [makeLine('12345678', 8, true), makeLine('90123456', 8, true), makeLine('78', 8)])
    expect(copySelectedText(soft, whole(0, 2, 8))).toBe('123456789012345678')
    const hard = grid(5, [makeLine('ABC', 5), makeLine('DEF', 5)])
    expect(copySelectedText(hard, whole(0, 1, 5))).toBe('ABC\nDEF')
  })

  it('copies a sub-range by slot endpoints', () => {
    const target = grid(6, [makeLine('ABCDE', 6)])
    expect(copySelectedText(target, { anchor: { line: 0, x: 1 }, focus: { line: 0, x: 3 } })).toBe('BC')
    expect(copySelectedText(target, { anchor: { line: 0, x: 3 }, focus: { line: 0, x: 1 } })).toBe('BC')
  })

  it('trims trailing padding and keeps history blank lines', () => {
    const target = grid(4, [makeLine('', 4), makeLine('', 4), makeLine('', 4), makeLine('end', 4)])
    expect(copySelectedText(target, whole(0, 3, 4))).toBe('\n\n\nend')
  })

  // 选择终点落在下一行行首时不产生幻影 LF。
  it('does not emit a phantom LF when the selection ends at a line start', () => {
    const target = grid(4, [makeLine('AB', 4), makeLine('CD', 4)])
    expect(copySelectedText(target, { anchor: { line: 0, x: 0 }, focus: { line: 1, x: 0 } })).toBe('AB')
  })

  // DWC 不输出字；FEFF 与孤立代理原样保留；NUL 输出空格。
  it('projects slot kinds faithfully', () => {
    const wide = grid(4, [
      {
        wrapped: false,
        slots: [
          unit('中'.charCodeAt(0)),
          { kind: 'dwc', code: DWC_SLOT_CODE, style: DEFAULT_SLOT_STYLE },
          unit(90),
          { kind: 'empty', code: 0, style: DEFAULT_SLOT_STYLE },
        ],
      },
    ])
    expect(copySelectedText(wide, whole(0, 0, 4))).toBe('中Z')
    const bom = grid(4, [makeLine('A\ufeffB', 4)])
    expect(copySelectedText(bom, whole(0, 0, 4))).toBe('A\ufeffB')
    const lone = grid(4, [makeLine('\ud83d', 4)])
    expect(copySelectedText(lone, whole(0, 0, 4))).toBe('\ud83d')
    const nul = grid(4, [makeLine('\u0000B', 4)])
    expect(copySelectedText(nul, whole(0, 0, 4))).toBe(' B')
  })

  it('returns empty text for an empty selection', () => {
    const target = grid(8, [makeLine('ABC', 8)])
    expect(copySelectedText(target, { anchor: { line: 0, x: 2 }, focus: { line: 0, x: 2 } })).toBe('')
  })

  it('handles selections anchored at the line boundary', () => {
    const target = grid(8, [makeLine('12345678', 8, true), makeLine('AB', 8)])
    expect(copySelectedText(target, { anchor: { line: 0, x: 8 }, focus: { line: 1, x: 1 } })).toBe('A')
  })
})
