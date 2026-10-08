import { describe, expect, it } from 'vitest'
import { gridFromCapturedView, type CapturedView, type CapturedSlotStyle } from './terminal-grid'
import { applyTerminalGrid, renderTerminalGrid } from './terminal-grid-renderer'

interface LineSpec {
  wrapped?: boolean
  text: string
  styles?: CapturedSlotStyle[]
}

function view(lines: LineSpec[], cols = 8, extra: Partial<CapturedView> = {}): CapturedView {
  return {
    cols,
    rows: lines.length,
    cursorX: 0,
    cursorY: 0,
    alternate: false,
    history: 0,
    lines: lines.map((line) => ({
      wrapped: line.wrapped ?? false,
      text: line.text,
      styles: line.styles ?? Array.from({ length: line.text.length }, () => ({ bold: false, fg: null })),
    })),
    ...extra,
  }
}

const OPTIONS = { cellWidth: 10, cellHeight: 20 }

describe('terminal grid renderer DOM', () => {
  it('renders explicit fixed cells and positioned glyphs for every slot', () => {
    const grid = gridFromCapturedView(view([{ text: 'ABC' }], 4))
    const root = renderTerminalGrid(grid, OPTIONS)
    expect(root.dataset.testid).toBe('terminal-grid')
    expect(root.dataset.cols).toBe('4')
    expect(root.style.width).toBe('40px')
    expect(root.style.height).toBe('20px')

    const line = root.querySelector('.terminal-grid__line') as HTMLElement
    expect(line.dataset.wrapped).toBe('false')
    expect(root.querySelectorAll('.terminal-grid__cell')).toHaveLength(4)

    const glyphs = root.querySelectorAll<HTMLElement>('.terminal-grid__glyph')
    expect(glyphs).toHaveLength(3)
    expect(glyphs[1].style.left).toBe('10px')
    expect(glyphs[1].style.width).toBe('10px')
    expect(glyphs[1].style.overflow).toBe('hidden')
    expect(glyphs[1].textContent).toBe('B')
  })

  // 宽字形按拓扑裁剪：被覆写的宽字符只占 1 格且 overflow hidden，不会覆盖相邻槽。
  it('clips an overwritten wide lead to one cell and spans a DWC lead across two', () => {
    const overwritten = renderTerminalGrid(
      gridFromCapturedView(view([{ text: '中X' }], 4)),
      OPTIONS,
    )
    const first = overwritten.querySelector<HTMLElement>('.terminal-grid__glyph') as HTMLElement
    expect(first.style.width).toBe('10px')
    expect(first.textContent).toBe('中')

    const wide = renderTerminalGrid(gridFromCapturedView(view([{ text: '中\ue000Z' }], 4)), OPTIONS)
    const wideGlyphs = wide.querySelectorAll<HTMLElement>('.terminal-grid__glyph')
    expect(wideGlyphs[0].dataset.span).toBe('2')
    expect(wideGlyphs[0].style.width).toBe('20px')
    expect(wideGlyphs[1].textContent).toBe('Z')
  })

  // 每个槽独立 style/background：cell 层保留逐槽背景，glyph 层只负责前景。
  it('projects per-slot styles onto cells and glyphs', () => {
    const grid = gridFromCapturedView(
      view([
        {
          text: 'AB',
          styles: [
            { bold: true, fg: 0x112233 },
            { bg: null, bgIndex: 4, underline: true },
          ],
        },
      ], 4),
    )
    const root = renderTerminalGrid(grid, OPTIONS)
    const glyphs = root.querySelectorAll<HTMLElement>('.terminal-grid__glyph')
    expect(glyphs[0].style.fontWeight).toBe('700')
    expect(glyphs[0].style.color).toBe('rgb(17, 34, 51)')
    expect(glyphs[1].style.textDecoration).toBe('underline')

    const cells = root.querySelectorAll<HTMLElement>('.terminal-grid__cell')
    expect(cells[1].dataset.bg).toBe('rgb(0 0 238)')

    const hidden = renderTerminalGrid(
      gridFromCapturedView(view([{ text: 'Q', styles: [{ hidden: true }] }], 2)),
      OPTIONS,
    )
    expect(hidden.querySelector<HTMLElement>('.terminal-grid__glyph')?.style.color).toBe('transparent')

    const inverse = renderTerminalGrid(
      gridFromCapturedView(view([{ text: 'Q', styles: [{ inverse: true }] }], 2)),
      OPTIONS,
    )
    const inverseGlyph = inverse.querySelector<HTMLElement>('.terminal-grid__glyph') as HTMLElement
    expect(inverseGlyph.style.color).toBe('var(--terminal-bg)')
    expect(inverse.querySelector<HTMLElement>('.terminal-grid__cell')?.style.background).toBe(
      'var(--terminal-fg)',
    )
  })

  // FEFF 保留为字形文本（自然不可见）；HTML/脚本内容只作为 text node，绝不 innerHTML。
  it('keeps FEFF and hostile text as inert text nodes', () => {
    const grid = gridFromCapturedView(
      view([{ text: 'A\ufeffB<script>x</script>' }], 24),
    )
    const root = renderTerminalGrid(grid, OPTIONS)
    expect(root.querySelectorAll('script')).toHaveLength(0)
    const glyphs = Array.from(root.querySelectorAll<HTMLElement>('.terminal-grid__glyph'))
    expect(glyphs.map((g) => g.textContent).join('')).toContain('<script>x</script>')
    expect(glyphs[1].textContent).toBe('\ufeff')
  })

  it('renders the cursor and marks wrapped lines and screen rows', () => {
    const grid = gridFromCapturedView(
      view(
        [
          { wrapped: true, text: '12345678' },
          { text: 'AB' },
        ],
        8,
        { history: 0, cursorX: 8, cursorY: 1, rows: 2 },
      ),
    )
    const root = renderTerminalGrid(grid, OPTIONS)
    const lines = root.querySelectorAll<HTMLElement>('.terminal-grid__line')
    expect(lines).toHaveLength(2)
    expect(lines[0].dataset.screen).toBe('true')
    const cursor = root.querySelector<HTMLElement>('.terminal-grid__cursor') as HTMLElement
    expect(cursor.dataset.line).toBe('1')
    expect(cursor.dataset.x).toBe('8')
    expect(cursor.style.left).toBe('80px')

    const withoutCursor = renderTerminalGrid(grid, { ...OPTIONS, showCursor: false })
    expect(withoutCursor.querySelector('.terminal-grid__cursor')).toBeNull()
  })

  // 行虚拟化：只渲染可见行区间，但容器高度仍按全部行计算。
  it('virtualizes line ranges while keeping total height', () => {
    const grid = gridFromCapturedView(
      view(Array.from({ length: 6 }, () => ({ text: '' })), 4),
    )
    const root = renderTerminalGrid(grid, { ...OPTIONS, lineStart: 2, lineEnd: 4 })
    expect(root.dataset.lineStart).toBe('2')
    expect(root.dataset.lineEnd).toBe('4')
    expect(root.style.height).toBe('120px')
    const lines = Array.from(root.querySelectorAll<HTMLElement>('.terminal-grid__line'))
    expect(lines.map((line) => line.dataset.line)).toEqual(['2', '3'])
    expect(lines[0].style.top).toBe('40px')
  })

  it('rejects non-positive cell sizes', () => {
    const grid = gridFromCapturedView(view([{ text: 'A' }], 2))
    expect(() => renderTerminalGrid(grid, { cellWidth: 0, cellHeight: 20 })).toThrow(RangeError)
    expect(() => renderTerminalGrid(grid, { cellWidth: 10, cellHeight: -1 })).toThrow(RangeError)
  })

  it('applies into a container by replacing existing children', () => {
    const grid = gridFromCapturedView(view([{ text: 'A' }], 2))
    const container = document.createElement('div')
    container.appendChild(document.createElement('span'))
    const root = applyTerminalGrid(container, grid, OPTIONS)
    expect(container.children).toHaveLength(1)
    expect(container.firstElementChild).toBe(root)
  })
})
