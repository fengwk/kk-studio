/**
 * 只读 Cell-Grid 渲染器：把 `TerminalGrid` 镜像绘制为逐槽 DOM。
 *
 * - 固定单元格 + 逐槽绝对定位，浏览器无法通过 grapheme 合并、ligature 或 bidi 重排改变列数；
 * - 每个字形按逐槽样式分段裁剪：同一逻辑字形的不同槽各自应用自身的 fg/装饰/hidden，
 *   相邻同样式槽合并为一段，避免重复 DOM；
 * - cell 层承载每槽背景（含 inverse），glyph 层承载前景文字并 `overflow: hidden` 裁剪；
 * - 行可窗口虚拟化，容器高度仍按全部行计算。
 */

import { effectiveColors, slotDecoration, slotStyleKey } from './terminal-style'
import { cursorLineIndex, glyphFragments, type GridLine, type TerminalGrid } from './terminal-grid'

export interface TerminalGridRenderOptions {
  /** 单元格宽度（CSS px）。 */
  readonly cellWidth: number
  /** 单元格高度（CSS px）。 */
  readonly cellHeight: number
  /** 终端等宽字体族。 */
  readonly fontFamily?: string
  /** 字号（CSS px）。 */
  readonly fontSize?: number
  /** 默认前景色。 */
  readonly foreground?: string
  /** 默认背景色。 */
  readonly background?: string
  /** 起始渲染行（含，默认 0）。 */
  readonly lineStart?: number
  /** 结束渲染行（不含，默认为全部行）。 */
  readonly lineEnd?: number
  /** 是否绘制光标（默认 true）。 */
  readonly showCursor?: boolean
}

const DEFAULT_FONT_FAMILY =
  "'Noto Sans Mono CJK JP', 'DejaVu Sans Mono', 'Roboto Mono', monospace"
const DEFAULT_FOREGROUND = '#e6e6e6'
const DEFAULT_BACKGROUND = '#101412'

function setStyle(element: HTMLElement, style: Record<string, string>): void {
  for (const [key, value] of Object.entries(style)) {
    element.style.setProperty(key.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`), value)
  }
}

function ensureBlinkKeyframes(doc: Document): void {
  if (doc.getElementById('terminal-grid-keyframes')) {
    return
  }
  const style = doc.createElement('style')
  style.id = 'terminal-grid-keyframes'
  style.textContent =
    '@keyframes terminal-grid-blink { 0%, 50% { visibility: visible; } 51%, 100% { visibility: hidden; } }'
  doc.head.appendChild(style)
}

/** 不随祖先 bidi/ligature 设置变化的隔离样式。 */
function isolation(): Record<string, string> {
  return {
    direction: 'ltr',
    unicodeBidi: 'isolate',
    fontVariantLigatures: 'none',
    whiteSpace: 'pre',
  }
}

function buildCell(slotIndex: number, line: number, line0: GridLine, cellWidth: number, cellHeight: number): HTMLElement {
  const slot = line0.slots[slotIndex]
  const cell = document.createElement('div')
  cell.className = 'terminal-grid__cell'
  cell.dataset.line = String(line)
  cell.dataset.x = String(slotIndex)
  cell.dataset.kind = slot.kind
  const colors = effectiveColors(slot.style)
  setStyle(cell, {
    position: 'absolute',
    left: `${slotIndex * cellWidth}px`,
    top: '0',
    width: `${cellWidth}px`,
    height: `${cellHeight}px`,
    ...(colors.bg ? { background: colors.bg } : {}),
  })
  if (colors.bg) {
    cell.dataset.bg = colors.bg
  }
  return cell
}

/**
 * 按覆盖槽的逐槽样式把片段切分为绘制段，每段被裁剪到自己覆盖的单元格。
 * 段保持完整字形文本与固定 origin（负 text-indent 回退），只裁剪显示区域。
 */
function buildGlyph(line: number, fragment: ReturnType<typeof glyphFragments>[number], cellWidth: number, cellHeight: number): HTMLElement[] {
  const elements: HTMLElement[] = []
  let i = 0
  while (i < fragment.styles.length) {
    const key = slotStyleKey(fragment.styles[i])
    let j = i + 1
    while (j < fragment.styles.length && slotStyleKey(fragment.styles[j]) === key) {
      j += 1
    }
    const runStart = fragment.x + i
    const runLength = j - i
    const style = fragment.styles[i]
    const colors = effectiveColors(style)
    const decoration = slotDecoration(style)
    const element = document.createElement('div')
    element.className = 'terminal-grid__glyph'
    element.dataset.line = String(line)
    element.dataset.glyphX = String(fragment.x)
    element.dataset.glyphSpan = String(fragment.span)
    element.dataset.x = String(runStart)
    element.dataset.span = String(runLength)
    element.dataset.text = fragment.text
    setStyle(element, {
      position: 'absolute',
      left: `${runStart * cellWidth}px`,
      top: '0',
      width: `${runLength * cellWidth}px`,
      height: `${cellHeight}px`,
      overflow: 'hidden',
      display: 'block',
      textIndent: `${(fragment.x - runStart) * cellWidth}px`,
      ...isolation(),
      ...(style.hidden ? { color: 'transparent' } : colors.fg ? { color: colors.fg } : {}),
      ...(decoration.fontWeight ? { fontWeight: decoration.fontWeight } : {}),
      ...(decoration.fontStyle ? { fontStyle: decoration.fontStyle } : {}),
      ...(decoration.textDecoration ? { textDecoration: decoration.textDecoration } : {}),
      ...(decoration.opacity ? { opacity: decoration.opacity } : {}),
      ...(decoration.animation ? { animation: decoration.animation } : {}),
    })
    element.textContent = fragment.text
    elements.push(element)
    i = j
  }
  return elements
}

function buildCursor(line: number, grid: TerminalGrid, cellWidth: number, cellHeight: number): HTMLElement {
  const cursor = document.createElement('div')
  cursor.className = 'terminal-grid__cursor'
  // pending wrap（cursorX == cols）仍保留在镜像中，但可见光标落在右边界最后一格。
  const column = Math.max(0, Math.min(grid.cursorX, grid.cols - 1))
  cursor.dataset.line = String(line)
  cursor.dataset.x = String(grid.cursorX)
  cursor.dataset.column = String(column)
  setStyle(cursor, {
    position: 'absolute',
    left: `${column * cellWidth}px`,
    top: '0',
    width: `${cellWidth}px`,
    height: `${cellHeight}px`,
    boxSizing: 'border-box',
    border: '1px solid var(--terminal-fg)',
    pointerEvents: 'none',
  })
  return cursor
}

/** 构建一个独立的终端网格 DOM 子树（不挂载）。 */
export function renderTerminalGrid(
  grid: TerminalGrid,
  options: TerminalGridRenderOptions,
): HTMLElement {
  const { cellWidth, cellHeight } = options
  if (
    !Number.isFinite(cellWidth) ||
    !Number.isFinite(cellHeight) ||
    cellWidth <= 0 ||
    cellHeight <= 0
  ) {
    throw new RangeError('cellWidth/cellHeight 必须是有限正数')
  }
  const total = grid.lines.length
  const start = Math.max(0, options.lineStart ?? 0)
  const end = Math.min(total, options.lineEnd ?? total)
  const root = document.createElement('div')
  root.className = 'terminal-grid'
  root.dataset.testid = 'terminal-grid'
  root.dataset.cols = String(grid.cols)
  root.dataset.rows = String(grid.rows)
  root.dataset.history = String(grid.history)
  root.dataset.alternate = String(grid.alternate)
  root.dataset.lineStart = String(start)
  root.dataset.lineEnd = String(end)
  ensureBlinkKeyframes(document)
  const foreground = options.foreground ?? DEFAULT_FOREGROUND
  const background = options.background ?? DEFAULT_BACKGROUND
  setStyle(root, {
    position: 'relative',
    width: `${grid.cols * cellWidth}px`,
    height: `${total * cellHeight}px`,
    fontFamily: options.fontFamily ?? DEFAULT_FONT_FAMILY,
    fontSize: `${options.fontSize ?? Math.round(cellHeight * 0.72)}px`,
    lineHeight: `${cellHeight}px`,
    color: foreground,
    background,
    ...isolation(),
  })
  root.style.setProperty('--terminal-fg', foreground)
  root.style.setProperty('--terminal-bg', background)

  const showCursor = options.showCursor ?? true
  const cursorLine = cursorLineIndex(grid)

  for (let line = start; line < end; line += 1) {
    const line0 = grid.lines[line]
    const lineEl = document.createElement('div')
    lineEl.className = 'terminal-grid__line'
    lineEl.dataset.line = String(line)
    lineEl.dataset.wrapped = String(line0.wrapped)
    lineEl.dataset.screen = line >= grid.history ? 'true' : 'false'
    setStyle(lineEl, {
      position: 'absolute',
      left: '0',
      top: `${line * cellHeight}px`,
      width: `${grid.cols * cellWidth}px`,
      height: `${cellHeight}px`,
    })
    for (let x = 0; x < grid.cols; x += 1) {
      lineEl.appendChild(buildCell(x, line, line0, cellWidth, cellHeight))
    }
    for (const fragment of glyphFragments(line0)) {
      for (const element of buildGlyph(line, fragment, cellWidth, cellHeight)) {
        lineEl.appendChild(element)
      }
    }
    if (showCursor && line === cursorLine) {
      lineEl.appendChild(buildCursor(line, grid, cellWidth, cellHeight))
    }
    root.appendChild(lineEl)
  }
  return root
}

/** 把网格渲染进容器，替换其全部子节点。 */
export function applyTerminalGrid(
  container: HTMLElement,
  grid: TerminalGrid,
  options: TerminalGridRenderOptions,
): HTMLElement {
  const root = renderTerminalGrid(grid, options)
  container.replaceChildren(root)
  return root
}
