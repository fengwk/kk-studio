/**
 * 终端逐槽样式模型与颜色投影。
 *
 * 这是终端单元格样式的唯一权威表示：镜像（`TerminalGrid`）按槽保存 `SlotStyle`，
 * 渲染器只根据它生成 DOM 颜色与装饰。本模块不含任何宽度推理或字符规范化。
 */

/** 单元格前景/背景颜色。`rgb` 为显式真彩，`indexed` 为 0-255 调色板索引。 */
export type CellColor =
  | { readonly kind: 'rgb'; readonly r: number; readonly g: number; readonly b: number }
  | { readonly kind: 'indexed'; readonly index: number }

/** 单个 UTF-16 槽的完整样式。所有布尔位都是显式独立字段，避免依赖位序。 */
export interface SlotStyle {
  readonly fg: CellColor | null
  readonly bg: CellColor | null
  readonly bold: boolean
  readonly dim: boolean
  readonly italic: boolean
  readonly underline: boolean
  readonly blink: boolean
  readonly inverse: boolean
  readonly hidden: boolean
  readonly strikethrough: boolean
}

/** 缺省样式：默认前景/背景，无装饰。冻结以避免被就地修改。 */
export const DEFAULT_SLOT_STYLE: SlotStyle = Object.freeze({
  fg: null,
  bg: null,
  bold: false,
  dim: false,
  italic: false,
  underline: false,
  blink: false,
  inverse: false,
  hidden: false,
  strikethrough: false,
})

/** 从 SGR 真彩打包整数（0xRRGGBB）构造颜色；越界抛错而不是静默截断。 */
export function rgbFromPacked(packed: number): CellColor {
  if (!Number.isInteger(packed) || packed < 0 || packed > 0xffffff) {
    throw new RangeError(`真彩颜色必须是 0..0xFFFFFF 的整数，当前为 ${packed}`)
  }
  return {
    kind: 'rgb',
    r: (packed >> 16) & 0xff,
    g: (packed >> 8) & 0xff,
    b: packed & 0xff,
  }
}

/** 从索引构造颜色；索引必须是 0..255。 */
export function indexedColor(index: number): CellColor {
  if (!Number.isInteger(index) || index < 0 || index > 255) {
    throw new RangeError(`索引颜色必须是 0..255 的整数，当前为 ${index}`)
  }
  return { kind: 'indexed', index }
}

const ANSI_16 = [
  [0, 0, 0],
  [205, 0, 0],
  [0, 205, 0],
  [205, 205, 0],
  [0, 0, 238],
  [205, 0, 205],
  [0, 205, 205],
  [229, 229, 229],
  [127, 127, 127],
  [255, 0, 0],
  [0, 255, 0],
  [255, 255, 0],
  [92, 92, 255],
  [255, 0, 255],
  [0, 255, 255],
  [255, 255, 255],
] as const

const CUBE_LEVELS = [0, 95, 135, 175, 215, 255] as const

/** 标准 256 色 xterm 调色板；索引颜色是固定投影，不是产品可配置项。 */
export function indexedPaletteRgb(index: number): readonly [number, number, number] {
  if (!Number.isInteger(index) || index < 0 || index > 255) {
    throw new RangeError(`索引颜色必须是 0..255 的整数，当前为 ${index}`)
  }
  if (index < 16) {
    return ANSI_16[index]
  }
  if (index < 232) {
    const n = index - 16
    return [
      CUBE_LEVELS[Math.floor(n / 36)],
      CUBE_LEVELS[Math.floor((n % 36) / 6)],
      CUBE_LEVELS[n % 6],
    ]
  }
  const gray = 8 + (index - 232) * 10
  return [gray, gray, gray]
}

function rgbCss(r: number, g: number, b: number): string {
  return `rgb(${r} ${g} ${b})`
}

/** 颜色投影为 CSS 颜色；索引颜色使用固定 256 色调色板。 */
export function colorToCss(color: CellColor): string {
  if (color.kind === 'rgb') {
    return rgbCss(color.r, color.g, color.b)
  }
  const [r, g, b] = indexedPaletteRgb(color.index)
  return rgbCss(r, g, b)
}

/**
 * 计算槽的有效前景/背景与 inverse 交换。
 *
 * 返回 `null` 表示“使用容器默认值”，渲染器据此回退到 `--terminal-fg` /
 * `--terminal-bg`。inverse 时未显式指定的那一侧取对侧默认值。
 */
export function effectiveColors(style: SlotStyle): {
  readonly fg: string | null
  readonly bg: string | null
} {
  const fg = style.fg ? colorToCss(style.fg) : null
  const bg = style.bg ? colorToCss(style.bg) : null
  if (!style.inverse) {
    return { fg, bg }
  }
  return {
    fg: bg ?? 'var(--terminal-bg)',
    bg: fg ?? 'var(--terminal-fg)',
  }
}

/** 渲染器使用的字体/装饰样式投影；字段为 CSS 值，`null` 表示不设置。 */
export interface SlotDecoration {
  readonly fontWeight: string | null
  readonly fontStyle: string | null
  readonly textDecoration: string | null
  readonly opacity: string | null
  readonly animation: string | null
}

/**
 * 装饰投影：bold/italic/underline/strikethrough/dim/blink。
 * `hidden` 不在这里处理（隐藏需要把前景变透明但保留背景，由渲染器控制颜色）。
 */
export function slotDecoration(style: SlotStyle): SlotDecoration {
  const decorations: string[] = []
  if (style.underline) {
    decorations.push('underline')
  }
  if (style.strikethrough) {
    decorations.push('line-through')
  }
  return {
    fontWeight: style.bold ? '700' : null,
    fontStyle: style.italic ? 'italic' : null,
    textDecoration: decorations.length > 0 ? decorations.join(' ') : null,
    opacity: style.dim ? '0.55' : null,
    animation: style.blink ? 'terminal-grid-blink 1s steps(1, end) infinite' : null,
  }
}
