/**
 * 只读 Cell-Grid 渲渲染器：把 `TerminalGrid` 镜像绘制为明确的逐槽 DOM。
 *
 * 设计约束：
 * - 固定单元格尺寸 + 逐槽绝对定位，浏览器无法通过 grapheme 合并、ligature 或 bidi 重排
 *   改变列数；每个槽的几何与样式都是显式 DOM；
 * - cell 层负责每槽背景与几何，glyph 层负责前景文字，宽字形用 `overflow: hidden` 裁剪，
 *   保证被覆写的宽字符不会覆盖相邻槽；
 * - 行可虚拟化（`lineStart`/`lineEnd`），容器高度仍按全部行计算，避免为整段历史建满 DOM。
 */

import { effectiveColors, slotDecoration } from './terminal-style'
import { cursorLineIndex, glyphFragments, type TerminalGrid } from './terminal-grid'

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

function buildCell(grid: TerminalGrid, line: number, slotIndex: number, options: RequiredCell): HTMLElement {
  const slot = grid.lines[line].slots[slotIndex]
  const cell = options.doc.createElement('div')
  cell.className = 'terminal-grid__cell'
  cell.dataset.line = String(line)
  cell.dataset.x = String(slotIndex)
  cell.dataset.kind = slot.kind
  const colors = effectiveColors(slot.style)
  const style: Record<string, string> = {
    position: 'absolute',
    left: `${slotIndex * options.cellWidth}px`,
    top: '0',
    width: `${options.cellWidth}px`,
    height: `${options.cellHeight}px`,
  }
  if (colors.bg) {
    style.background = colors.bg
    cell.dataset.bg = colors.bg
  }
  setStyle(cell, style)
  return cell
}

interface RequiredCell {
  readonly doc: Document
  readonly cellWidth: number
  readonly cellHeight: number
}

function buildGlyph(
  line: number,
  fragment: ReturnType<typeof glyphFragments>[number],
  options: RequiredCell,
): HTMLElement {
  const span = options.doc.createElement('span')
  span.className = 'terminal-grid__glyph'
  span.dataset.line = String(line)
  span.dataset.x = String(fragment.x)
  span.dataset.span = String(fragment.span)
  const colors = effectiveColors(fragment.style)
  const decoration = slotDecoration(fragment.style)
  const style: Record<string, string> = {
    position: 'absolute',
    left: `${fragment.x * options.cellWidth}px`,
    top: '0',
    width: `${fragment.span * options.cellWidth}px`,
    height: `${options.cellHeight}px`,
    overflow: 'hidden',
    whiteSpace: 'pre',
  }
  if (colors.fg) {
    style.color = fragment.style.hidden ? 'transparent' : colors.fg
  } else if (fragment.style.hidden) {
    style.color = 'transparent'
  }
  if (decoration.fontWeight) {
    style.fontWeight = decoration.fontWeight
  }
  if (decoration.fontStyle) {
    style.fontStyle = decoration.fontStyle
  }
  if (decoration.textDecoration) {
    style.textDecoration = decoration.textDecoration
  }
  if (decoration.opacity) {
    style.opacity = decoration.opacity
  }
  if (decoration.animation) {
    style.animation = decoration.animation
  }
  setStyle(span, style)
  span.textContent = fragment.text
  return span
}

function buildCursor(
  line: number,
  grid: TerminalGrid,
  options: RequiredCell,
): HTMLElement {
  const cursor = options.doc.createElement('div')
  cursor.className = 'terminal-grid__cursor'
  cursor.dataset.line = String(line)
  cursor.dataset.x = String(grid.cursorX)
  setStyle(cursor, {
    position: 'absolute',
    left: `${grid.cursorX * options.cellWidth}px`,
    top: '0',
    width: `${options.cellWidth}px`,
    height: `${options.cellHeight}px`,
    boxSizing: 'border-box',
    border: '1px solid var(--terminal-fg)',
    pointerEvents: 'none',
  })
  return cursor
}

/**
 * 构建一个独立的终端网格 DOM 子树（不挂载）。
 *
 * 返回的根元素带有 `data-cols`/`data-rows`/`data-history`/`data-alternate` 与渲染行区间，
 * 便于在真实浏览器中复核几何与内容。
 */
export function renderTerminalGrid(
  grid: TerminalGrid,
  options: TerminalGridRenderOptions,
): HTMLElement {
  if (options.cellWidth <= 0 || options.cellHeight <= 0) {
    throw new RangeError('cellWidth/cellHeight 必须为正数')
  }
  const total = grid.lines.length
  const start = Math.max(0, options.lineStart ?? 0)
  const end = Math.min(total, options.lineEnd ?? total)
  const required: RequiredCell = {
    doc: document,
    cellWidth: options.cellWidth,
    cellHeight: options.cellHeight,
  }
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
    width: `${grid.cols * options.cellWidth}px`,
    height: `${total * options.cellHeight}px`,
    fontFamily: options.fontFamily ?? DEFAULT_FONT_FAMILY,
    fontSize: `${options.fontSize ?? Math.round(options.cellHeight * 0.72)}px`,
    lineHeight: `${options.cellHeight}px`,
    color: foreground,
    background,
    fontVariantLigatures: 'none',
    whiteSpace: 'pre',
  })
  root.style.setProperty('--terminal-fg', foreground)
  root.style.setProperty('--terminal-bg', background)
  root.dataset.cellWidth = String(options.cellWidth)
  root.dataset.cellHeight = String(options.cellHeight)

  const showCursor = options.showCursor ?? true
  const cursorLine = cursorLineIndex(grid)

  for (let line = start; line < end; line += 1) {
    const lineEl = document.createElement('div')
    lineEl.className = 'terminal-grid__line'
    lineEl.dataset.line = String(line)
    lineEl.dataset.wrapped = String(grid.lines[line].wrapped)
    lineEl.dataset.screen = line >= grid.history ? 'true' : 'false'
    setStyle(lineEl, {
      position: 'absolute',
      left: '0',
      top: `${line * options.cellHeight}px`,
      width: `${grid.cols * options.cellWidth}px`,
      height: `${options.cellHeight}px`,
    })
    for (let x = 0; x < grid.cols; x += 1) {
      lineEl.appendChild(buildCell(grid, line, x, required))
    }
    for (const fragment of glyphFragments(grid.lines[line])) {
      lineEl.appendChild(buildGlyph(line, fragment, required))
    }
    if (showCursor && line === cursorLine) {
      lineEl.appendChild(buildCursor(line, grid, required))
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
