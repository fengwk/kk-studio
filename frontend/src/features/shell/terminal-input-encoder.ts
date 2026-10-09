/**
 * 终端浏览器输入字节编码器。
 *
 * 提供按终端输入模式编码键盘、直接文本、剪贴板粘贴、鼠标与焦点事件的纯函数，
 * 以及不超过 4096 字节的分片工具。不持有 DOM 全局状态与外部依赖。
 */

import type { MouseFormat, MouseMode, TerminalInputModes } from './terminal-view-codec'

export const TERMINAL_INPUT_INVALID_MESSAGE = 'terminal input is invalid'

export const MAX_INPUT_BUFFER_BYTES = 64 * 1024 // 65536
export const INPUT_CHUNK_MAX_BYTES = 4096

const BRACKETED_PASTE_START = new Uint8Array([0x1b, 0x5b, 0x32, 0x30, 0x30, 0x7e])
const BRACKETED_PASTE_END = new Uint8Array([0x1b, 0x5b, 0x32, 0x30, 0x31, 0x7e])

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
  const len = str.length
  for (let i = 0; i < len; i++) {
    const code = str.charCodeAt(i)
    if (code <= 0x7f) {
      bytes += 1
    } else if (code <= 0x7ff) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      if (i + 1 < len) {
        const next = str.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          bytes += 4
          i++
          continue
        }
      }
      throw new TerminalInputError()
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new TerminalInputError()
    } else {
      bytes += 3
    }
  }
  return bytes
}

function measureNormalizedPasteUtf8(text: string): number {
  let bytes = 0
  const len = text.length
  for (let i = 0; i < len; i++) {
    const code = text.charCodeAt(i)
    if (code === 0x0d) {
      if (i + 1 < len && text.charCodeAt(i + 1) === 0x0a) {
        i++
      }
      bytes += 1
    } else if (code === 0x0a) {
      bytes += 1
    } else if (code <= 0x7f) {
      bytes += 1
    } else if (code <= 0x7ff) {
      bytes += 2
    } else if (code >= 0xd800 && code <= 0xdbff) {
      if (i + 1 < len) {
        const next = text.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          bytes += 4
          i++
          continue
        }
      }
      throw new TerminalInputError()
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new TerminalInputError()
    } else {
      bytes += 3
    }
  }
  return bytes
}

const NAVIGATION_KEYS = new Map<string, string>([
  ['ArrowUp', 'A'],
  ['ArrowDown', 'B'],
  ['ArrowRight', 'C'],
  ['ArrowLeft', 'D'],
  ['Home', 'H'],
  ['End', 'F'],
])

const EDIT_KEYS = new Map<string, number>([
  ['Insert', 2],
  ['Delete', 3],
  ['PageUp', 5],
  ['PageDown', 6],
])

const FUNCTION_KEYS_F1_F4 = new Map<string, string>([
  ['F1', 'P'],
  ['F2', 'Q'],
  ['F3', 'R'],
  ['F4', 'S'],
])

const FUNCTION_KEYS_F5_F12 = new Map<string, number>([
  ['F5', 15],
  ['F6', 17],
  ['F7', 18],
  ['F8', 19],
  ['F9', 20],
  ['F10', 21],
  ['F11', 23],
  ['F12', 24],
])

const NUMPAD_CODES = new Map<string, string>([
  ['Numpad0', 'p'],
  ['Numpad1', 'q'],
  ['Numpad2', 'r'],
  ['Numpad3', 's'],
  ['Numpad4', 't'],
  ['Numpad5', 'u'],
  ['Numpad6', 'v'],
  ['Numpad7', 'w'],
  ['Numpad8', 'x'],
  ['Numpad9', 'y'],
  ['NumpadDecimal', 'n'],
  ['NumpadDivide', 'o'],
  ['NumpadMultiply', 'j'],
  ['NumpadSubtract', 'm'],
  ['NumpadAdd', 'k'],
  ['NumpadEnter', 'M'],
  ['NumpadEqual', 'X'],
])

/**
 * 映射纯 Ctrl 键到 ASCII 控制码 (0..127)。
 * 仅映射纯 ASCII 字符，避免 Unicode 大小写折叠误匹配。
 */
function mapCtrlChar(key: string): number | null {
  if (key.length === 1) {
    const code = key.charCodeAt(0)
    if ((code >= 65 && code <= 90) || (code >= 97 && code <= 122)) {
      return code & 0x1f
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

function isPrintableKey(key: string): boolean {
  if (!key) return false
  if (key.length === 1) {
    const ch = key.charCodeAt(0)
    return ch >= 0x20 && ch !== 0x7f
  }
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
  if (descriptor.isComposing || descriptor.altGraph || descriptor.meta) {
    return null
  }

  const { key, code = '' } = descriptor
  const shift = Boolean(descriptor.shift)
  const alt = Boolean(descriptor.alt)
  const ctrl = Boolean(descriptor.ctrl)

  const m = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0)

  const numpadFinal = NUMPAD_CODES.get(code) ?? NUMPAD_CODES.get(key)
  if (numpadFinal !== undefined) {
    if (code === 'NumpadEnter' || key === 'NumpadEnter') {
      if (modes.applicationKeypad) {
        if (m === 1) {
          return toAsciiBytes('\x1bOM')
        }
        return toAsciiBytes(`\x1b[1;${m}M`)
      }
    } else if (modes.applicationKeypad) {
      if (m === 1) {
        return toAsciiBytes(`\x1bO${numpadFinal}`)
      }
      return toAsciiBytes(`\x1b[1;${m}${numpadFinal}`)
    } else {
      if (!ctrl && !alt) {
        return null
      }
    }
  }

  if (key === 'Enter' || code === 'NumpadEnter') {
    const baseBytes = modes.autoNewLine ? [0x0d, 0x0a] : [0x0d]
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, ...baseBytes])
    }
    return new Uint8Array(baseBytes)
  }

  if (key === 'Backspace') {
    const byte = ctrl ? 0x08 : 0x7f
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, byte])
    }
    return new Uint8Array([byte])
  }

  if (key === 'Tab') {
    if (shift) {
      if (alt && modes.altSendsEscape) {
        return toAsciiBytes('\x1b\x1b[Z')
      }
      return toAsciiBytes('\x1b[Z')
    }
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, 0x09])
    }
    return new Uint8Array([0x09])
  }

  if (key === 'Escape') {
    if (alt && modes.altSendsEscape) {
      return new Uint8Array([0x1b, 0x1b])
    }
    return new Uint8Array([0x1b])
  }

  const navFinal = NAVIGATION_KEYS.get(key)
  if (navFinal !== undefined) {
    if (m === 1) {
      if (key === 'Home' || key === 'End') {
        return toAsciiBytes(modes.applicationKeypad ? `\x1bO${navFinal}` : `\x1b[${navFinal}`)
      }
      return toAsciiBytes(modes.applicationCursor ? `\x1bO${navFinal}` : `\x1b[${navFinal}`)
    }
    return toAsciiBytes(`\x1b[1;${m}${navFinal}`)
  }

  const editCode = EDIT_KEYS.get(key)
  if (editCode !== undefined) {
    if (m === 1) {
      return toAsciiBytes(`\x1b[${editCode}~`)
    }
    return toAsciiBytes(`\x1b[${editCode};${m}~`)
  }

  const fnFinal1to4 = FUNCTION_KEYS_F1_F4.get(key)
  if (fnFinal1to4 !== undefined) {
    if (m === 1) {
      return toAsciiBytes(`\x1bO${fnFinal1to4}`)
    }
    return toAsciiBytes(`\x1b[1;${m}${fnFinal1to4}`)
  }

  const fnCode5to12 = FUNCTION_KEYS_F5_F12.get(key)
  if (fnCode5to12 !== undefined) {
    if (m === 1) {
      return toAsciiBytes(`\x1b[${fnCode5to12}~`)
    }
    return toAsciiBytes(`\x1b[${fnCode5to12};${m}~`)
  }

  if (ctrl) {
    const ctrlVal = mapCtrlChar(key)
    if (ctrlVal !== null) {
      if (alt && modes.altSendsEscape) {
        return new Uint8Array([0x1b, ctrlVal])
      }
      return new Uint8Array([ctrlVal])
    }
    return null
  }

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
      return null
    }
  }

  if (!ctrl && !alt && isPrintableKey(key)) {
    return null
  }

  return null
}

/**
 * 编码直接文本输入，严格 UTF-8，拒绝 lone surrogate，检查 64KiB 预算，不做 Unicode normalization。
 */
export function encodeTerminalText(text: string): Uint8Array | null {
  if (text.length === 0) {
    return null
  }
  const byteCount = validateAndMeasureUtf8(text)
  if (byteCount > MAX_INPUT_BUFFER_BYTES) {
    throw new TerminalInputError()
  }
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

  const overhead = modes.bracketedPaste
    ? BRACKETED_PASTE_START.length + BRACKETED_PASTE_END.length
    : 0

  const textByteCount = measureNormalizedPasteUtf8(text)
  const totalBytes = textByteCount + overhead

  if (totalBytes > MAX_INPUT_BUFFER_BYTES) {
    throw new TerminalInputError()
  }

  const normalized = text.replace(/\r\n|\n/g, '\r')
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

  if (x < 1 || x > cols || y < 1 || y > rows) {
    return null
  }

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

  const modBits =
    (event.shift ? 4 : 0) | (event.alt ? 8 : 0) | (event.ctrl ? 16 : 0)

  const format: MouseFormat = modes.mouseFormat

  if (format === 'SGR') {
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
      baseButton = event.button!
    }

    const cb = baseButton + modBits
    const finalChar = action === 'release' ? 'm' : 'M'
    return toAsciiBytes(`\x1b[<${cb};${x};${y}${finalChar}`)
  }

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
    baseButton = 3
  }

  const cb = baseButton + modBits

  if (format === 'URXVT') {
    return toAsciiBytes(`\x1b[${cb + 32};${x};${y}M`)
  }

  if (format === 'XTERM') {
    if (x > 223 || y > 223 || cb + 32 > 255) {
      return null
    }
    return new Uint8Array([0x1b, 0x5b, 0x4d, cb + 32, x + 32, y + 32])
  }

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
 * 编码焦点事件，返回独立的防御性副本。
 */
export function encodeTerminalFocus(
  focused: boolean,
  modes: TerminalInputModes,
): Uint8Array | null {
  if (typeof focused !== 'boolean') {
    throw new TerminalInputError()
  }

  if (modes.mouseMode === 'FOCUS') {
    return focused
      ? new Uint8Array([0x1b, 0x5b, 0x49])
      : new Uint8Array([0x1b, 0x5b, 0x4f])
  }

  return null
}
