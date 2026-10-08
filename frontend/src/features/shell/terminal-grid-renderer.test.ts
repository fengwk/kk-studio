import { describe, expect, it } from 'vitest'
import { DEFAULT_SLOT_STYLE, type SlotStyle } from './terminal-style'
import {
  DWC_SLOT_CODE,
  type GridLine,
  type GridSlot,
  type TerminalGrid,
} from './terminal-grid'
import { applyTerminalGrid, renderTerminalGrid } from './terminal-grid-renderer'

function st(patch: Partial<SlotStyle>): SlotStyle {
  return { ...DEFAULT_SLOT_STYLE, ...patch }
}
const RED = st({ fg: { kind: 'rgb', r: 255, g: 0, b: 0 } })
const BLUE = st({ fg: { kind: 'rgb', r: 0, g: 0, b: 255 } })

function unit(code: number, style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'unit', code, style }
}
function dwc(style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'dwc', code: DWC_SLOT_CODE, style }
}
function empty(style: SlotStyle = DEFAULT_SLOT_STYLE): GridSlot {
  return { kind: 'empty', code: 0, style }
}
function makeLine(text: string, cols: number, styleAt: (i: number) => SlotStyle): GridLine {
  const slots: GridSlot[] = []
  for (let x = 0; x < cols; x += 1) {
    if (x < text.length) {
      const code = text.charCodeAt(x)
      slots.push({ kind: code === DWC_SLOT_CODE ? 'dwc' : 'unit', code, style: styleAt(x) })
    } else {
      slots.push(empty())
    }
  }
  return { wrapped: false, slots }
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

const OPTIONS = { cellWidth: 10, cellHeight: 20 }
const glyphs = (root: HTMLElement) =>
  Array.from(root.querySelectorAll<HTMLElement>('.terminal-grid__glyph'))

describe('terminal grid renderer DOM', () => {
  it('renders fixed cells and positioned glyphs for every slot', () => {
    const root = renderTerminalGrid(grid(4, [makeLine('ABC', 4, () => DEFAULT_SLOT_STYLE)]), OPTIONS)
    expect(root.dataset.testid).toBe('terminal-grid')
    expect(root.style.width).toBe('40px')
    expect(root.style.height).toBe('20px')
    expect(root.querySelectorAll('.terminal-grid__cell')).toHaveLength(4)
    const list = glyphs(root)
    expect(list).toHaveLength(3)
    expect(list[1].style.left).toBe('10px')
    expect(list[1].style.width).toBe('10px')
    expect(list[1].style.overflow).toBe('hidden')
    expect(list[1].textContent).toBe('B')
  })

  // 被覆写的宽字符只占 1 格并裁剪；正常 DWC 前导跨 2 格。
  it('clips an overwritten wide lead and spans a DWC lead', () => {
    const overwritten = renderTerminalGrid(
      grid(4, [makeLine('中X', 4, () => DEFAULT_SLOT_STYLE)]),
      OPTIONS,
    )
    const first = glyphs(overwritten)[0]
    expect(first.style.width).toBe('10px')
    expect(first.textContent).toBe('中')

    const wide = renderTerminalGrid(
      grid(4, [{ wrapped: false, slots: [unit('中'.charCodeAt(0)), dwc(), unit(90), empty()] }]),
      OPTIONS,
    )
    const wideList = glyphs(wide)
    expect(wideList[0].dataset.span).toBe('2')
    expect(wideList[0].style.width).toBe('20px')
    expect(wideList[1].textContent).toBe('Z')
  })

  // 合绘字形的不同槽各自应用自身前景：按逐槽样式分段，同样式段合并、不同样式段各自裁剪。
  it('applies per-slot styles to each covered cell of a combined glyph', () => {
    const same = glyphs(
      renderTerminalGrid(
        grid(2, [{ wrapped: false, slots: [unit(0xd83d), unit(0xde00)] }]),
        OPTIONS,
      ),
    )
    expect(same).toHaveLength(1)
    expect(same[0].dataset.span).toBe('2')

    const different = glyphs(
      renderTerminalGrid(
        grid(2, [{ wrapped: false, slots: [unit(0xd83d, RED), unit(0xde00, BLUE)] }]),
        OPTIONS,
      ),
    )
    expect(different).toHaveLength(2)
    expect(different[0]).toMatchObject({ textContent: '😀' })
    expect(different[0].dataset.x).toBe('0')
    expect(different[0].dataset.glyphSpan).toBe('2')
    expect(different[0].style.textIndent).toBe('0px')
    expect(different[0].style.color).toBe('rgb(255, 0, 0)')
    expect(different[1].dataset.x).toBe('1')
    expect(different[1].style.textIndent).toBe('-10px')
    expect(different[1].style.color).toBe('rgb(0, 0, 255)')
  })

  // 逐槽背景保留在 cell 层；EMPTY 槽的装饰用空格字形承载。
  it('projects per-slot background and decorated empty slots', () => {
    const root = renderTerminalGrid(
      grid(3, [
        {
          wrapped: false,
          slots: [unit(65), empty(st({ bg: { kind: 'indexed', index: 4 } })), empty(st({ underline: true, fg: { kind: 'rgb', r: 1, g: 2, b: 3 } }))],
        },
      ]),
      OPTIONS,
    )
    const cells = Array.from(root.querySelectorAll<HTMLElement>('.terminal-grid__cell'))
    expect(cells[1].dataset.kind).toBe('empty')
    expect(cells[1].style.background).toBe('rgb(0, 0, 238)')

    const list = glyphs(root)
    expect(list).toHaveLength(2)
    expect(list[1].textContent).toBe(' ')
    expect(list[1].style.textDecoration).toBe('underline')
    expect(list[1].style.color).toBe('rgb(1, 2, 3)')
  })

  it('isolates glyphs from ancestor bidi and ligature settings', () => {
    const root = renderTerminalGrid(grid(2, [makeLine('A', 2, () => DEFAULT_SLOT_STYLE)]), OPTIONS)
    expect(root.style.direction).toBe('ltr')
    expect(root.style.unicodeBidi).toBe('isolate')
    expect(root.style.fontVariantLigatures).toBe('none')
    const glyph = glyphs(root)[0]
    expect(glyph.style.direction).toBe('ltr')
    expect(glyph.style.unicodeBidi).toBe('isolate')
    expect(glyph.style.fontVariantLigatures).toBe('none')
  })

  it('keeps FEFF and hostile text as inert text nodes', () => {
    const root = renderTerminalGrid(
      grid(24, [makeLine('A\ufeffB<script>x</script>', 24, () => DEFAULT_SLOT_STYLE)]),
      OPTIONS,
    )
    expect(root.querySelectorAll('script')).toHaveLength(0)
    expect(glyphs(root).map((g) => g.textContent).join('')).toContain('<script>x</script>')
    expect(glyphs(root)[1].textContent).toBe('\ufeff')
  })

  // pending wrap（cursorX==cols）保留镜像坐标，但可见光标落在最后一格。
  it('clamps the visible cursor to the last cell and marks screen rows', () => {
    const root = renderTerminalGrid(
      grid(8, [makeLine('12345678', 8, () => DEFAULT_SLOT_STYLE)], { cursorX: 8, cursorY: 0 }),
      OPTIONS,
    )
    const cursor = root.querySelector<HTMLElement>('.terminal-grid__cursor') as HTMLElement
    expect(cursor.dataset.x).toBe('8')
    expect(cursor.dataset.column).toBe('7')
    expect(cursor.style.left).toBe('70px')
    expect((root.querySelector('.terminal-grid__line') as HTMLElement).dataset.screen).toBe('true')

    const withoutCursor = renderTerminalGrid(
      grid(8, [makeLine('12345678', 8, () => DEFAULT_SLOT_STYLE)], { cursorX: 8 }),
      { ...OPTIONS, showCursor: false },
    )
    expect(withoutCursor.querySelector('.terminal-grid__cursor')).toBeNull()
  })

  it('virtualizes line ranges while keeping total height', () => {
    const root = renderTerminalGrid(
      grid(4, Array.from({ length: 6 }, () => makeLine('', 4, () => DEFAULT_SLOT_STYLE))),
      { ...OPTIONS, lineStart: 2, lineEnd: 4 },
    )
    expect(root.style.height).toBe('120px')
    const lines = Array.from(root.querySelectorAll<HTMLElement>('.terminal-grid__line'))
    expect(lines.map((line) => line.dataset.line)).toEqual(['2', '3'])
    expect(lines[0].style.top).toBe('40px')
  })

  // 尺寸必须是有限正数，NaN/Infinity/0/负数都显式失败。
  it('rejects non-finite and non-positive cell sizes', () => {
    const target = grid(2, [makeLine('A', 2, () => DEFAULT_SLOT_STYLE)])
    expect(() => renderTerminalGrid(target, { cellWidth: 0, cellHeight: 20 })).toThrow(RangeError)
    expect(() => renderTerminalGrid(target, { cellWidth: -1, cellHeight: 20 })).toThrow(RangeError)
    expect(() => renderTerminalGrid(target, { cellWidth: Number.NaN, cellHeight: 20 })).toThrow(RangeError)
    expect(() => renderTerminalGrid(target, { cellWidth: 10, cellHeight: Number.POSITIVE_INFINITY })).toThrow(RangeError)
  })

  it('applies into a container by replacing existing children', () => {
    const container = document.createElement('div')
    container.appendChild(document.createElement('span'))
    const root = applyTerminalGrid(container, grid(2, [makeLine('A', 2, () => DEFAULT_SLOT_STYLE)]), OPTIONS)
    expect(container.children).toHaveLength(1)
    expect(container.firstElementChild).toBe(root)
  })
})
