/**
 * 虚拟槽选择模型与纯文本复制。
 *
 * 选择端点直接落在镜像的槽坐标（行号 + 槽下标）上，不经过 DOM 的 Range 或浏览器
 * 字符度量，因此 DWC、FEFF、孤立代理的列语义与镜像完全一致。
 */

import { cursorLineIndex, type TerminalGrid } from './terminal-grid'

/** 槽端点：`line` 为镜像中的绝对行号，`x` 为槽下标（0..cols）。 */
export interface GridPoint {
  readonly line: number
  readonly x: number
}

/** 选择：anchor 为起点，focus 为终点，两者都是槽端点。 */
export interface GridSelection {
  readonly anchor: GridPoint
  readonly focus: GridPoint
}

/** 规范化后的单行区间 `[xFrom, xTo)`。 */
export interface SelectionRange {
  readonly line: number
  readonly xFrom: number
  readonly xTo: number
}

function comparePoint(a: GridPoint, b: GridPoint): number {
  if (a.line !== b.line) {
    return a.line - b.line
  }
  return a.x - b.x
}

/** 把选择规范化为 anchor <= focus（阅读顺序）。 */
export function normalizeSelection(selection: GridSelection): GridSelection {
  if (comparePoint(selection.anchor, selection.focus) <= 0) {
    return selection
  }
  return { anchor: selection.focus, focus: selection.anchor }
}

/** 选择是否为空（同一槽端点）。 */
export function isSelectionEmpty(selection: GridSelection): boolean {
  return (
    selection.anchor.line === selection.focus.line && selection.anchor.x === selection.focus.x
  )
}

/** 选择覆盖的行区间列表，供高亮或复制使用。空选择返回空数组。 */
export function selectionRanges(
  grid: TerminalGrid,
  selection: GridSelection,
): SelectionRange[] {
  if (isSelectionEmpty(selection)) {
    return []
  }
  const { anchor: start, focus: end } = normalizeSelection(selection)
  const ranges: SelectionRange[] = []
  for (let line = start.line; line <= end.line; line += 1) {
    const xFrom = line === start.line ? start.x : 0
    const xTo = line === end.line ? end.x : grid.cols
    ranges.push({ line, xFrom, xTo })
  }
  return ranges
}

/**
 * 把一行区间投影为文本。
 *
 * 规则来自槽 kind：DWC 不输出字；EMPTY 与 NUL 输出空格；其余 unit 原样输出
 * （保留 FEFF 与孤立代理）。行尾由 EMPTY 填充产生的空格被裁掉，避免把布局填充当内容。
 */
function segmentText(grid: TerminalGrid, line: number, xFrom: number, xTo: number): string {
  const slots = grid.lines[line].slots
  const chars: string[] = []
  const fromEmpty: boolean[] = []
  for (let x = xFrom; x < xTo; x += 1) {
    const slot = slots[x]
    if (!slot) {
      break
    }
    if (slot.kind === 'dwc') {
      continue
    }
    if (slot.kind === 'empty') {
      chars.push(' ')
      fromEmpty.push(true)
      continue
    }
    chars.push(slot.code === 0 ? ' ' : String.fromCharCode(slot.code))
    fromEmpty.push(false)
  }
  let end = chars.length
  while (end > 0 && fromEmpty[end - 1]) {
    end -= 1
  }
  return chars.slice(0, end).join('')
}

/**
 * 从镜像按槽端点构造纯文本。
 *
 * 软换行（wrapped）的行之间不加 LF，硬换行加 LF。这不是任意 OS 剪贴板的孤代理可逆承诺：
 * text/plain 只保证与结构化镜像的槽一致。
 */
export function copySelectedText(grid: TerminalGrid, selection: GridSelection): string {
  const ranges = selectionRanges(grid, selection)
  if (ranges.length === 0 && isSelectionEmpty(selection)) {
    return ''
  }
  const parts: string[] = []
  for (let i = 0; i < ranges.length; i += 1) {
    const range = ranges[i]
    parts.push(segmentText(grid, range.line, range.xFrom, range.xTo))
    if (i < ranges.length - 1) {
      parts.push(grid.lines[range.line].wrapped ? '' : '\n')
    }
  }
  return parts.join('')
}

/** 选择整行的便捷端点。 */
export function lineStart(line: number): GridPoint {
  return { line, x: 0 }
}

export function lineEnd(grid: TerminalGrid, line: number): GridPoint {
  return { line, x: grid.cols }
}

/** 覆盖从 `from` 到 `to` 的整行（含端点）的选择。 */
export function wholeLinesSelection(
  grid: TerminalGrid,
  from: number,
  to: number,
): GridSelection {
  return { anchor: lineStart(from), focus: lineEnd(grid, to) }
}

/** 光标所在的绝对行号，便于调用方构造选择。 */
export function cursorSelectionLine(grid: TerminalGrid): number {
  return cursorLineIndex(grid)
}
