import { describe, expect, it } from 'vitest'
import { DEFAULT_SLOT_STYLE, type SlotStyle } from './terminal-style'
import {
  DWC_SLOT_CODE,
  cursorLineIndex,
  glyphFragments,
  isHighSurrogate,
  isLowSurrogate,
  type GridLine,
  type GridSlot,
  type TerminalGrid,
} from './terminal-grid'

function st(patch: Partial<SlotStyle>): SlotStyle {
  return { ...DEFAULT_SLOT_STYLE, ...patch }
}

function unit(code: number, style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'unit', code, style }
}

function dwc(style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'dwc', code: DWC_SLOT_CODE, style }
}

function empty(style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'empty', code: 0, style }
}

function line(slots: GridSlot[], wrapped = false): GridLine {
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

describe('surrogate helpers', () => {
  it('classifies high and low surrogates', () => {
    expect(isHighSurrogate(0xd83d)).toBe(true)
    expect(isHighSurrogate(0xde00)).toBe(false)
    expect(isLowSurrogate(0xde00)).toBe(true)
    expect(isLowSurrogate(0x41)).toBe(false)
  })
})

describe('glyph fragments from topology only', () => {
  it('emits one fragment per unit and skips padding', () => {
    const fragments = glyphFragments(line([unit(65), unit(66), unit(67), empty()]))
    expect(fragments.map((f) => [f.x, f.span, f.text, f.styles.length])).toEqual([
      [0, 1, 'A', 1],
      [1, 1, 'B', 1],
      [2, 1, 'C', 1],
    ])
  })

  // 宽字符只有被 DWC 续格证明时才占 2 格；被覆写（后随普通 unit）时各占 1 格。
  it('derives wide span from a following DWC and clips an overwritten lead to one cell', () => {
    const wide = glyphFragments(line([unit('中'.charCodeAt(0)), dwc()]))
    expect(wide).toHaveLength(1)
    expect(wide[0]).toMatchObject({ x: 0, span: 2, text: '中' })
    expect(wide[0].styles).toHaveLength(2)

    const overwritten = glyphFragments(line([unit('中'.charCodeAt(0)), unit(88)]))
    expect(overwritten.map((f) => [f.x, f.span, f.text])).toEqual([
      [0, 1, '中'],
      [1, 1, 'X'],
    ])
  })

  // 合绘片段必须携带每个覆盖槽的样式，供渲染器逐槽应用前景/装饰。
  it('keeps per-slot styles of every covered cell', () => {
    const red = st({ fg: { kind: 'rgb', r: 255, g: 0, b: 0 }, bold: true })
    const blue = st({ fg: { kind: 'rgb', r: 0, g: 0, b: 255 }, underline: true })
    const pair = glyphFragments(line([unit(0xd83d, red), unit(0xde00, blue)]))
    expect(pair).toHaveLength(1)
    expect(pair[0].text).toBe('😀')
    expect(pair[0].styles).toEqual([red, blue])

    const wide = glyphFragments(line([unit(88, red), dwc(blue)]))
    expect(wide[0].styles).toEqual([red, blue])
  })

  // 同行 high+low 代理合绘为 2 格字形，但绝不跨行合成。
  it('pairs same-line surrogates and never across lines', () => {
    const paired = glyphFragments(line([unit(0xd83d), unit(0xde00), unit(90)]))
    expect(paired.map((f) => [f.x, f.span, f.text])).toEqual([
      [0, 2, '😀'],
      [2, 1, 'Z'],
    ])
    expect(glyphFragments(line([unit(0xd83d)]))[0]).toMatchObject({ text: '\ud83d', span: 1 })
    expect(glyphFragments(line([unit(0xde00), unit(90)]))[0]).toMatchObject({ text: '\ude00', span: 1 })
  })

  // ZWJ、variation selector、组合字符按实际槽独立成格，不 join。
  it('keeps ZWJ, selector and combining marks as independent cells', () => {
    const zwj = glyphFragments(
      line([
        unit(0xd83d), unit(0xdc69), unit(0x200d), unit(0xd83d), unit(0xdcbb), unit(0xfe0f), unit(90),
      ]),
    )
    expect(zwj.map((f) => [f.x, f.span])).toEqual([
      [0, 2],
      [2, 1],
      [3, 2],
      [5, 1],
      [6, 1],
    ])
    expect(glyphFragments(line([unit(113), unit(0x301), unit(90)])).map((f) => f.text)).toEqual([
      'q',
      '\u0301',
      'Z',
    ])
  })

  it('keeps FEFF as its own cell and renders NUL as a space', () => {
    expect(glyphFragments(line([unit(65), unit(0xfeff), unit(66)])).map((f) => f.text)).toEqual([
      'A',
      '\ufeff',
      'B',
    ])
    expect(glyphFragments(line([unit(0), unit(66)])).map((f) => f.text)).toEqual([' ', 'B'])
  })

  it('spans consecutive DWC continuations', () => {
    const fragments = glyphFragments(line([unit(88), dwc(), dwc(), unit(89)]))
    expect(fragments.map((f) => [f.x, f.span, f.text, f.styles.length])).toEqual([
      [0, 3, 'X', 3],
      [3, 1, 'Y', 1],
    ])
  })

  it('ignores orphan DWC without a lead', () => {
    expect(glyphFragments(line([dwc(), unit(65)]))).toEqual([
      { x: 1, span: 1, text: 'A', styles: [DEFAULT_SLOT_STYLE] },
    ])
  })

  // EMPTY 槽自身样式保留：仅当需要落笔的装饰（下划/删除线）才生成空格字形。
  it('emits an ink fragment only for decorated empty slots', () => {
    expect(glyphFragments(line([unit(65), empty()]))).toHaveLength(1)
    const underlined = st({ underline: true, fg: { kind: 'rgb', r: 1, g: 2, b: 3 } })
    const fragments = glyphFragments(line([unit(65), empty(underlined)]))
    expect(fragments).toHaveLength(2)
    expect(fragments[1]).toEqual({ x: 1, span: 1, text: ' ', styles: [underlined] })
  })
})

describe('mirror coverage invariants', () => {
  // 片段互不重叠、按列递增；unit 全覆盖，dwc 由前导片段覆盖，纯 empty 不覆盖。
  it('tiles slots without overlap and covers all drawable slots', () => {
    const slots: GridSlot[] = [
      unit('中'.charCodeAt(0)),
      dwc(),
      unit(0xd83d),
      unit(0xde00),
      empty(st({ underline: true })),
      empty(),
      unit(65),
    ]
    const fragments = glyphFragments(line(slots))
    const covered = new Set<number>()
    let expectedX = 0
    for (const fragment of fragments) {
      expect(fragment.x).toBeGreaterThanOrEqual(expectedX)
      for (let k = fragment.x; k < fragment.x + fragment.span; k += 1) {
        expect(covered.has(k)).toBe(false)
        covered.add(k)
      }
      expectedX = fragment.x + fragment.span
    }
    slots.forEach((slot, x) => {
      if (slot.kind === 'unit' || slot.kind === 'dwc') {
        expect(covered.has(x), `drawable slot ${x}`).toBe(true)
      }
      if (slot.kind === 'empty' && !(slot.style.underline || slot.style.strikethrough)) {
        expect(covered.has(x), `bare empty ${x}`).toBe(false)
      }
    })
  })

  it('derives the cursor line from history and cursorY', () => {
    expect(cursorLineIndex(grid(4, [line([unit(65)])], { history: 3, cursorY: 1 }))).toBe(4)
  })
})
