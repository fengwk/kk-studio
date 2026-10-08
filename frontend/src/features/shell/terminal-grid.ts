/**
 * 只读终端数值镜像与逐槽字形推导。
 *
 * 权威数据是 numeric UTF-16 unit + 槽 kind（unit/empty/dwc）+ 逐槽样式 + wrapped。
 * 这里不做 Unicode 宽度推理、字形合并或字符规范化：字形片段完全由该行实际槽拓扑决定。
 */

import type { SlotStyle } from './terminal-style'

/** JediTerm 用 U+E000 表示双宽字符的续格（CharUtils.DWC）。 */
export const DWC_SLOT_CODE = 0xe000

export type SlotKind = 'unit' | 'empty' | 'dwc'

/** 单个单元格槽；`code` 始终是该槽的 UTF-16 code unit。 */
export interface GridSlot {
  readonly kind: SlotKind
  readonly code: number
  readonly style: SlotStyle
}

/** 一行；`slots` 恰好 `cols` 长，尾部空槽为 `empty` 且各自携带样式。 */
export interface GridLine {
  readonly wrapped: boolean
  readonly slots: readonly GridSlot[]
}

/** 只读终端镜像。`lines` 为历史行 + 屏幕行，顺序与权威捕获一致。 */
export interface TerminalGrid {
  readonly cols: number
  readonly rows: number
  readonly cursorX: number
  readonly cursorY: number
  readonly alternate: boolean
  readonly history: number
  readonly lines: readonly GridLine[]
}

/** 高代理（0xD800-0xDBFF）。 */
export function isHighSurrogate(code: number): boolean {
  return code >= 0xd800 && code <= 0xdbff
}

/** 低代理（0xDC00-0xDFFF）。 */
export function isLowSurrogate(code: number): boolean {
  return code >= 0xdc00 && code <= 0xdfff
}

/**
 * 一个绘制字形片段。位置/宽度只来自槽拓扑，`styles` 为覆盖槽的逐槽样式（长度等于 span）。
 *
 * - 普通 unit：1 格；
 * - 同行成对 high+low 代理：2 格（同一逻辑字符）；
 * - 后随 DWC 的 unit：1 + 连续 DWC 数（正常为 2 格）；
 * - 需要可见装饰（下划/删除线）的 empty 槽：1 格空格，用于承载装饰。
 */
export interface GlyphFragment {
  readonly x: number
  readonly span: number
  readonly text: string
  readonly styles: readonly SlotStyle[]
}

/** EMPTY 槽没有字形，只有需要落笔的装饰才生成片段。 */
function emptyNeedsInk(style: SlotStyle): boolean {
  return style.underline || style.strikethrough
}

function unitText(code: number): string {
  // NUL 保持明确的空格语义；其余 unit（含 FEFF、孤立代理）原样保留。
  return code === 0 ? ' ' : String.fromCharCode(code)
}

/**
 * 由一行实际槽拓扑推导字形片段。
 *
 * 不依赖任何宽度表：宽字符必须由后续 DWC 续格证明其为 2 格，否则按 1 格绘制并裁剪，
 * 从而保证“覆写 DWC 后 中 与 X 各占 1 格”且中不覆盖相邻 X。
 */
export function glyphFragments(line: GridLine): GlyphFragment[] {
  const slots = line.slots
  const out: GlyphFragment[] = []
  let x = 0
  while (x < slots.length) {
    const slot = slots[x]
    if (slot.kind === 'empty') {
      if (emptyNeedsInk(slot.style)) {
        out.push({ x, span: 1, text: ' ', styles: [slot.style] })
      }
      x += 1
      continue
    }
    if (slot.kind === 'dwc') {
      // 续格由前导消费；孤立续格不产生字形。
      x += 1
      continue
    }
    const next = slots[x + 1]
    if (next && next.kind === 'unit' && isHighSurrogate(slot.code) && isLowSurrogate(next.code)) {
      out.push({
        x,
        span: 2,
        text: String.fromCharCode(slot.code, next.code),
        styles: [slot.style, next.style],
      })
      x += 2
      continue
    }
    if (next && next.kind === 'dwc') {
      const styles: SlotStyle[] = [slot.style]
      let cursor = x + 1
      while (cursor < slots.length && slots[cursor].kind === 'dwc') {
        styles.push(slots[cursor].style)
        cursor += 1
      }
      out.push({ x, span: styles.length, text: unitText(slot.code), styles })
      x = cursor
      continue
    }
    out.push({ x, span: 1, text: unitText(slot.code), styles: [slot.style] })
    x += 1
  }
  return out
}

/** 光标所在行的绝对行号（历史行 + 屏幕行）。 */
export function cursorLineIndex(grid: TerminalGrid): number {
  return grid.history + grid.cursorY
}
