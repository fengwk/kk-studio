/**
 * SH1 浏览器终端输入纯字节编码器。
 *
 * 严格按照 CONTRACT.md 规范实现键盘、文本、粘贴、鼠标、焦点纯函数编码与分片，
 * 不依赖 DOM 全局状态、VT parser、Unicode width 表或外部终端库。
 */

import type { MouseFormat, MouseMode, TerminalInputModes } from './terminal-view-codec'

export const TERMINAL_INPUT_INVALID_MESSAGE = 'terminal input is invalid'

export const MAX_INPUT_BUFFER_BYTES = 64 * 1024 // 65536
export const INPUT_CHUNK_MAX_BYTES = 4096

const BRACKETED_PASTE_START = new Uint8Array([0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e]) // ESC [ 2 0 0 ~
const BRACKETED_PASTE_END = new Uint8Array([0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e]) // ESC [ 2 0 1 ~

const FOCUS_IN_BYTES = new Uint8Array([0x1b, 0x5b, 0x49]) // ESC [ I
const FOCUS_OUT_BYTES = new Uint8Array([0x1b, 0x5b, 0x4f]) // ESC [ O

const TEXT_ENCODER = new TextEncoder()

/** 终端输入协议固定错误：不回显敏感输入与文本内容。 */
export class TerminalInputError extends Error {
  constructor(message: string = TERMINAL_INPUT_INVALID_MESSAGE) {
    super(message)
    this.name = 'TerminalInputError'
  }
}

export interface TerminalKeyDescriptor {
  readonly key: string
  readonly code?: string
  readonly shift?: boolean
  readonly alt?: boolean
  readonly ctrl?: boolean
  readonly meta?: boolean
  readonly isComposing?: boolean
  readonly altGraph?: boolean
}

export type TerminalMouseAction = 'press' | 'release' | 'move' | 'wheel'
export type TerminalMouseButton = 0 | 1 | 2
export type TerminalWheelDirection = 'up' | 'down'

export interface TerminalMouseEvent {
  readonly action: TerminalMouseAction
  readonly button?: TerminalMouseButton | null
  readonly x: number
  readonly y: number
  readonly shift?: boolean
  readonly alt?: boolean
  readonly ctrl?: boolean
  readonly wheel?: TerminalWheelDirection | null
}

function toAsciiBytes(str: string): Uint8Array {
  const bytes = new Uint8Array(str.length)
  for (let i = 0; i < str.length; i++) {
    bytes[i] = str.charCodeAt(i)
  }
  return bytes
}

function validateAndMeasureUtf8(str: string): number {
  let bytes = 0
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i)
    if (code <= 0x7f) {
      bytes += 1
    } else if (code <= 0x7ff) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      // High surrogate
      if (i + 1 < str.length) {
        const next = str.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          bytes += 4
          i++
          continue
        }
      }
      throw new TerminalInputError()
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      // Lone low surrogate
      throw new TerminalInputError()
    } else {
      bytes += 3
    }
  }
  return bytes
}

const NAVIGATION_KEYS: Record<string, string> = {
  ArrowUp: 'A',
  ArrowDown: 'B',
  ArrowRight: 'C',
  ArrowLeft: 'D',
  Home: 'H',
  End: 'F',
}

const EDIT_KEYS: Record<string, number> = {
  Insert: 2,
  Delete: 3,
  PageUp: 5,
  PageDown: 6,
}

const FUNCTION_KEYS_F1_F4: Record<string, string> = {
  F1: 'P',
  F2: 'Q',
  F3: 'R',
  F4: 'S',
}

const FUNCTION_KEYS_F5_F12: Record<string, number> = {
  F5: 15,
  F6: 17,
  F7: 18,
  F8: 19,
  F9: 20,
  F10: 21,
  F11: 23,
  F12: 24,
}

const NUMPAD_CODES: Record<string, string> = {
  Numpad0: 'p',
  Numpad1: 'q',
  Numpad2: 'r',
  Numpad3: 's',
  Numpad4: 't',
  Numpad5: 'u',
  Numpad6: 'v',
  Numpad7: 'w',
  Numpad8: 'x',
  Numpad9: 'y',
  NumpadDecimal: 'n',
  NumpadDivide: 'o',
  NumpadMultiply: 'j',
  NumpadSubtract: 'm',
  NumpadAdd: 'k',
  NumpadEnter: 'M',
  NumpadEqual: 'X',
}

/**
 * 映射纯 Ctrl 键到 ASCII 控制码 (0..127)。
 * 无法映射的字符返回 null。
 */
function mapCtrlChar(key: string): number | null {
  if (key.length === 1) {
    const upper = key.toUpperCase()
    if (upper >= 'A' && upper <= 'Z') {
      return upper.charCodeAt(0) - 64 // 1..26
    }
    switch (key) {
      case ' ':
      case '@':
      case '2':
        return 0
      case '[':
      case '3':
        return 27
      case '\\':
      case '4':
        return 28
      case ']':
      case '5':
        return 29
      case '^':
      case '6':
        return 30
      case '_':
      case '/':
      case '7':
        return 31
      case '?':
      case '8':
        return 127
      default:
        return null
    }
  }
  return null
}

/**
 * 检查按键是否为无特殊功能语义的普通打印键（包括多字节 UTF-8 字符）。
 */
function isPrintableKey(key: string): boolean {
  if (!key) return false
  // 长度为 1 的字符（排除 ASCII 控制字符 0x00..0x1F 与 0x7F）
  if (key.length === 1) {
    const ch = key.charCodeAt(0)
    return ch >= 0x20 && ch !== 0x7f
  }
  // 可能是合法的代理对（如 Emoji）
  if (key.length === 2) {
    const high = key.charCodeAt(0)
    const low = key.charCodeAt(1)
    return high >= 0xd800 && high <= 0xdbff && low >= 0xdc00 && low <= 0xdfff
  }
  return false
}

/**
 * 将键盘事件描述符编码为终端输入字节。
 */
export function encodeTerminalKey(
  descriptor: TerminalKeyDescriptor,
  modes: TerminalInputModes,
): Uint8Array | null {
  // composition/AltGraph/meta 交给输入法/浏览器，不产生键字节
  if (descriptor.isComposing || descriptor.altGraph || descriptor.meta) {
    return null
  }

  const { key, code = '' } = descriptor
  const shift = Boolean(descriptor.shift)
  const alt = Boolean(descriptor.alt)
  const ctrl = Boolean(descriptor.ctrl)

  // xterm 修饰符参数：m = 1 + shift*1 + alt*2 + ctrl*4
  const m = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0)

  // 1. 小键盘（Numpad）处理
  const numpadFinal = NUMPAD_CODES[code] ?? NUMPAD_CODES[key]
  if (numpadFinal !== undefined) {
    if (code === 'NumpadEnter' || key === 'NumpadEnter') {
      if (modes.applicationKeypad) {
        if (m === 1) {
          return toAsciiBytes('\x1bOM')
        }
        return toAsciiBytes(`\x1b[1;${m}M`)
      }
      // 非 applicationKeypad 时，NumpadEnter 仍按普通 Enter 处理（下述分支）
    } else if (modes.applicationKeypad) {
      if (m === 1) {
        return toAsciiBytes(`\x1bO${numpadFinal}`)
      }
      return toAsciiBytes(`\x1b[1;${m}${numpadFinal}`)
    } else {
      // 非 application mode 普通 numpad 交 text
      if (!ctrl && !alt) {
        return null
      }
      // 若带 ctrl 或 alt 则继续由后续对应修饰键逻辑处理
    }
  }

  // 2. 回车（Enter）
  if (key === 'Enter' || code === 'NumpadEnter') {
    const baseBytes = modes.autoNewLine ? [0x0d, 0x0a] : [0x0d]
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, ...baseBytes])
    }
    return new Uint8Array(baseBytes)
  }

  // 3. 退格（Backspace）
  if (key === 'Backspace') {
    // Backspace DEL, Ctrl+Backspace BS
    const byte = ctrl ? 0x08 : 0x7f
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, byte])
    }
    return new Uint8Array([byte])
  }

  // 4. 制表键（Tab）
  if (key === 'Tab') {
    if (shift) {
      // ShiftTab: ESC [ Z
      if (alt && modes.altSendsEscape) {
        return toAsciiBytes('\x1b\x1b[Z')
      }
      return toAsciiBytes('\x1b[Z')
    }
    // 普通 Tab: HT (0x09)
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, 0x09])
    }
    return new Uint8Array([0x09])
  }

  // 5. 退出键（Escape）
  if (key === 'Escape') {
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, 0x1b])
    }
    return new Uint8Array([0x1b])
  }

  // 6. 导航键（ArrowUp/Down/Right/Left, Home, End）
  const navFinal = NAVIGATION_KEYS[key]
  if (navFinal !== undefined) {
    if (m === 1) {
      if (key === 'Home' || key === 'End') {
        return toAsciiBytes(modes.applicationKeypad ? `\x1bO${navFinal}` : `\x1b[${navFinal}`)
      }
      return toAsciiBytes(modes.applicationCursor ? `\x1bO${navFinal}` : `\x1b[${navFinal}`)
    }
    // 参数化导航不额外叠 ESC
    return toAsciiBytes(`\x1b[1;${m}${navFinal}`)
  }

  // 7. 页面与编辑键（Insert, Delete, PageUp, PageDown）
  const editCode = EDIT_KEYS[key]
  if (editCode !== undefined) {
    if (m === 1) {
      return toAsciiBytes(`\x1b[${editCode}~`)
    }
    return toAsciiBytes(`\x1b[${editCode};${m}~`)
  }

  // 8. 功能键（F1..F4）
  const fnFinal1to4 = FUNCTION_KEYS_F1_F4[key]
  if (fnFinal1to4 !== undefined) {
    if (m === 1) {
      return toAsciiBytes(`\x1bO${fnFinal1to4}`)
    }
    return toAsciiBytes(`\x1b[1;${m}${fnFinal1to4}`)
  }

  // 9. 功能键（F5..F12）
  const fnCode5to12 = FUNCTION_KEYS_F5_F12[key]
  if (fnCode5to12 !== undefined) {
    if (m === 1) {
      return toAsciiBytes(`\x1b[${fnCode5to12}~`)
    }
    return toAsciiBytes(`\x1b[${fnCode5to12};${m}~`)
  }

  // 10. Ctrl 组合键处理
  if (ctrl) {
    const ctrlVal = mapCtrlChar(key)
    if (ctrlVal !== null) {
      if (alt && modes.altSendsEscape) {
        return new Uint8Array([0x1b, ctrlVal])
      }
      return new Uint8Array([ctrlVal])
    }
    // Ctrl 无法映射的打印键不生成 bytes
    return null
  }

  // 11. Alt 打印键
  if (alt) {
    if (isPrintableKey(key)) {
      if (modes.altSendsEscape) {
        validateAndMeasureUtf8(key)
        const utf8 = TEXT_ENCODER.encode(key)
        const result = new Uint8Array(1 + utf8.length)
        result[0] = 0x1b
        result.set(utf8, 1)
        return result
      }
      // altSendsEscape 为 false 时交文字提交
      return null
    }
  }

  // 12. 普通无 Ctrl/Alt 可打印 key 不在 keydown 产生 bytes，交 text 提交避免 IME/keydown 双写
  if (!ctrl && !alt && isPrintableKey(key)) {
    return null
  }

  // 其余未产生字节的非打印控制键（如单独 Shift, CapsLock, NumLock 等）
  return null
}

/**
 * 编码直接文本输入，严格 UTF-8，拒绝 lone surrogate，不做 Unicode normalization。
 */
export function encodeTerminalText(text: string): Uint8Array | null {
  if (text.length === 0) {
    return null
  }
  validateAndMeasureUtf8(text)
  return TEXT_ENCODER.encode(text)
}

/**
 * 编码剪贴板粘贴文本，规范换行，支持 Bracketed Paste 模式，严格预算校验与分配。
 */
export function encodeTerminalPaste(
  text: string,
  modes: TerminalInputModes,
): Uint8Array | null {
  if (text.length === 0) {
    return null
  }

  // CRLF/LF → CR，保留原 CR
  const normalized = text.replace(/\r\n|\n/g, '\r')
  const textByteCount = validateAndMeasureUtf8(normalized)
  const overhead = modes.bracketedPaste
    ? BRACKETED_PASTE_START.length + BRACKETED_PASTE_END.length
    : 0
  const totalBytes = textByteCount + overhead

  if (totalBytes > MAX_INPUT_BUFFER_BYTES) {
    throw new TerminalInputError()
  }

  const result = new Uint8Array(totalBytes)
  let offset = 0

  if (modes.bracketedPaste) {
    result.set(BRACKETED_PASTE_START, offset)
    offset += BRACKETED_PASTE_START.length
  }

  const textBytes = TEXT_ENCODER.encode(normalized)
  result.set(textBytes, offset)
  offset += textBytes.length

  if (modes.bracketedPaste) {
    result.set(BRACKETED_PASTE_END, offset)
  }

  return result
}

/**
 * 深复制拆分终端输入字节块，保证每段 <= 4096 字节，总量 <= 64KiB。
 */
export function splitTerminalInput(bytes: Uint8Array): Uint8Array[] {
  if (bytes.byteLength > MAX_INPUT_BUFFER_BYTES) {
    throw new TerminalInputError()
  }
  if (bytes.byteLength === 0) {
    return []
  }

  const chunks: Uint8Array[] = []
  for (let offset = 0; offset < bytes.byteLength; offset += INPUT_CHUNK_MAX_BYTES) {
    const end = Math.min(offset + INPUT_CHUNK_MAX_BYTES, bytes.byteLength)
    chunks.push(bytes.slice(offset, end))
  }
  return chunks
}

/**
 * 将 Unicode 码点以 UTF-8 格式写入字节数组（用于 XTERM_EXT 1005 格式）。
 */
function appendUtf8Codepoint(target: number[], codePoint: number): void {
  if (codePoint <= 0x7f) {
    target.push(codePoint)
  } else {
    target.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f))
  }
}

/**
 * 编码鼠标事件为终端协议字节。
 */
export function encodeTerminalMouse(
  event: TerminalMouseEvent,
  modes: TerminalInputModes,
  cols: number,
  rows: number,
): Uint8Array | null {
  // 1. 严格尺寸校验
  if (
    typeof cols !== 'number' ||
    !Number.isInteger(cols) ||
    cols <= 0 ||
    typeof rows !== 'number' ||
    !Number.isInteger(rows) ||
    rows <= 0
  ) {
    throw new TerminalInputError()
  }

  if (!event || typeof event !== 'object') {
    throw new TerminalInputError()
  }

  const { action, x, y } = event

  // 2. 严格字段校验
  if (
    action !== 'press' &&
    action !== 'release' &&
    action !== 'move' &&
    action !== 'wheel'
  ) {
    throw new TerminalInputError()
  }

  if (typeof x !== 'number' || !Number.isInteger(x) || typeof y !== 'number' || !Number.isInteger(y)) {
    throw new TerminalInputError()
  }

  if (action === 'press' || action === 'release') {
    if (event.button !== 0 && event.button !== 1 && event.button !== 2) {
      throw new TerminalInputError()
    }
    if (event.wheel != null) {
      throw new TerminalInputError()
    }
  } else if (action === 'wheel') {
    if (event.wheel !== 'up' && event.wheel !== 'down') {
      throw new TerminalInputError()
    }
  } else if (action === 'move') {
    if (
      event.button !== 0 &&
      event.button !== 1 &&
      event.button !== 2 &&
      event.button !== null &&
      event.button !== undefined
    ) {
      throw new TerminalInputError()
    }
    if (event.wheel != null) {
      throw new TerminalInputError()
    }
  }

  // 3. 权威 screen 槽越界检测（1-based 权威坐标，越界返回 null，不 clamp 不 truncate）
  if (x < 1 || x > cols || y < 1 || y > rows) {
    return null
  }

  // 4. 终端 mouseMode 模式过滤
  const mouseMode: MouseMode = modes.mouseMode
  if (mouseMode === 'NONE' || mouseMode === 'FOCUS') {
    return null
  }
  if (mouseMode === 'NORMAL') {
    if (action === 'move') {
      return null
    }
  } else if (mouseMode === 'HILITE') {
    if (action !== 'press') {
      return null
    }
  } else if (mouseMode === 'BUTTON_MOTION') {
    if (action === 'move' && (event.button === null || event.button === undefined)) {
      return null
    }
  }
  // ALL_MOTION 允许全部 press / release / wheel / move

  // 5. 计算修饰键偏置
  const modBits =
    (event.shift ? 4 : 0) | (event.alt ? 8 : 0) | (event.ctrl ? 16 : 0)

  const format: MouseFormat = modes.mouseFormat

  // 6. SGR (1006) 格式编码
  if (format === 'SGR') {
    let baseButton: number
    if (action === 'wheel') {
      baseButton = event.wheel === 'up' ? 64 : 65
    } else if (action === 'move') {
      if (event.button !== null && event.button !== undefined) {
        baseButton = event.button + 32
      } else {
        baseButton = 3 + 32 // 35: motion without button
      }
    } else if (action === 'press') {
      baseButton = event.button!
    } else {
      // SGR release 仍保留真实 button
      baseButton = event.button!
    }

    const cb = baseButton + modBits
    const finalChar = action === 'release' ? 'm' : 'M'
    return toAsciiBytes(`\x1b[<${cb};${x};${y}${finalChar}`)
  }

  // 7. Legacy 格式 (URXVT, XTERM, XTERM_EXT) 基础 button
  let baseButton: number
  if (action === 'wheel') {
    baseButton = event.wheel === 'up' ? 64 : 65
  } else if (action === 'move') {
    if (event.button !== null && event.button !== undefined) {
      baseButton = event.button + 32
    } else {
      baseButton = 3 + 32
    }
  } else if (action === 'press') {
    baseButton = event.button!
  } else {
    // legacy release = 3
    baseButton = 3
  }

  const cb = baseButton + modBits

  // 8. URXVT (1015) 格式编码
  if (format === 'URXVT') {
    return toAsciiBytes(`\x1b[${cb + 32};${x};${y}M`)
  }

  // 9. XTERM 格式编码（最多 223，单字节不可表达返回 null）
  if (format === 'XTERM') {
    if (x > 223 || y > 223 || cb + 32 > 255) {
      return null
    }
    return new Uint8Array([0x1b, 0x5b, 0x4d, cb + 32, x + 32, y + 32])
  }

  // 10. XTERM_EXT (1005) 格式编码（最多 2015）
  if (x > 2015 || y > 2015 || cb + 32 > 2047) {
    return null
  }
  const byteList: number[] = [0x1b, 0x5b, 0x4d]
  appendUtf8Codepoint(byteList, cb + 32)
  appendUtf8Codepoint(byteList, x + 32)
  appendUtf8Codepoint(byteList, y + 32)
  return new Uint8Array(byteList)
}

/**
 * 编码焦点事件：仅在 mouseMode === 'FOCUS' 时生成 ESC [ I 或 ESC [ O。
 */
export function encodeTerminalFocus(
  focused: boolean,
  modes: TerminalInputModes,
): Uint8Array | null {
  if (typeof focused !== 'boolean') {
    throw new TerminalInputError()
  }

  if (modes.mouseMode === 'FOCUS') {
    return focused ? FOCUS_IN_BYTES : FOCUS_OUT_BYTES
  }

  return null
}
