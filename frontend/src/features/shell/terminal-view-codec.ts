/**
 * 终端结构化画面传输解码器（Wire Decoder）。
 *
 * 严格按照权威协议与 Java TerminalViewUpdateCodec 规范解码 RESET 与 PATCH 消息。
 * 输入为已经过基础结构反序列化的应用帧对象（trusted app frame value），非 raw JSON string。
 * 执行全量严格校验：字段白名单、UUID 规范、数值边界、安全整数、8 色位/flags 字典 Canonical First-Seen 顺序。
 */

import {
  indexedColor,
  rgbFromPacked,
  type CellColor,
  type SlotStyle,
} from './terminal-style'
import {
  DWC_SLOT_CODE,
  type GridLine,
  type GridSlot,
  type SlotKind,
} from './terminal-grid'

export type TerminalViewUpdateKind = 'RESET' | 'PATCH'

export type CursorShape =
  | 'BLINK_BLOCK'
  | 'STEADY_BLOCK'
  | 'BLINK_UNDERLINE'
  | 'STEADY_UNDERLINE'
  | 'BLINK_VERTICAL_BAR'
  | 'STEADY_VERTICAL_BAR'

export type MouseMode =
  | 'NONE'
  | 'NORMAL'
  | 'HILITE'
  | 'BUTTON_MOTION'
  | 'ALL_MOTION'
  | 'FOCUS'

export type MouseFormat =
  | 'XTERM_EXT'
  | 'URXVT'
  | 'SGR'
  | 'XTERM'

export interface TerminalInputModes {
  readonly applicationCursor: boolean
  readonly applicationKeypad: boolean
  readonly bracketedPaste: boolean
  readonly autoNewLine: boolean
  readonly altSendsEscape: boolean
  readonly mouseMode: MouseMode
  readonly mouseFormat: MouseFormat
}

/** 内核观察到的带数值身份的不可变行。 */
export interface MirroredLine extends GridLine {
  readonly id: number
}

/** 活动屏行替换：`row` 为 0..rows-1 内的行号，`line` 为替换后的整行。 */
export interface RowChange {
  readonly row: number
  readonly line: MirroredLine
}

/** 解码后的不可变终端更新模型。 */
export interface TerminalViewUpdate {
  readonly type: TerminalViewUpdateKind
  readonly terminalId: string
  readonly streamId: string
  readonly baseVersion: number | null
  readonly version: number
  readonly cols: number
  readonly rows: number
  readonly alternate: boolean
  readonly history: number
  readonly cursorX: number
  readonly cursorY: number
  readonly cursorVisible: boolean
  readonly cursorShape: CursorShape | null
  readonly inputModeRevision: number
  readonly inputModes: TerminalInputModes
  readonly historyTrim: number
  readonly historyAppend: readonly MirroredLine[]
  readonly screenRows: readonly RowChange[]
}

export const MIN_COLUMNS = 5
export const MIN_ROWS = 2
export const MAX_COLUMNS = 300
export const MAX_ROWS = 100
export const MAX_HISTORY_LINES = 512
export const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER // 9007199254740991

export const TERMINAL_VIEW_INVALID_MESSAGE = 'terminal view update is invalid'
export const TERMINAL_VIEW_MISMATCH_MESSAGE = 'terminal view stream mismatch'

/** 协议与镜像固定错误：不包含 payload/content/id。 */
export class TerminalViewError extends Error {
  constructor(message: string = TERMINAL_VIEW_INVALID_MESSAGE) {
    super(message)
    this.name = 'TerminalViewError'
  }
}

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

const UPDATE_FIELDS = new Set([
  'type',
  'terminalId',
  'streamId',
  'baseVersion',
  'version',
  'cols',
  'rows',
  'alternate',
  'history',
  'cursor',
  'inputModeRevision',
  'inputModes',
  'historyTrim',
  'historyAppend',
  'screenRows',
  'styles',
])

const CURSOR_FIELDS = new Set(['x', 'y', 'visible', 'shape'])

const CURSOR_SHAPES = new Set<CursorShape>([
  'BLINK_BLOCK',
  'STEADY_BLOCK',
  'BLINK_UNDERLINE',
  'STEADY_UNDERLINE',
  'BLINK_VERTICAL_BAR',
  'STEADY_VERTICAL_BAR',
])

const INPUT_MODE_FIELDS = new Set([
  'applicationCursor',
  'applicationKeypad',
  'bracketedPaste',
  'autoNewLine',
  'altSendsEscape',
  'mouseMode',
  'mouseFormat',
])

const MOUSE_MODES = new Set<MouseMode>([
  'NONE',
  'NORMAL',
  'HILITE',
  'BUTTON_MOTION',
  'ALL_MOTION',
  'FOCUS',
])

const MOUSE_FORMATS = new Set<MouseFormat>([
  'XTERM_EXT',
  'URXVT',
  'SGR',
  'XTERM',
])

const SCREEN_ROW_FIELDS = new Set(['row', 'line'])

/** 递归深度冻结对象与数组。 */
export function deepFreeze<T>(obj: T): Readonly<T> {
  if (obj === null || typeof obj !== 'object' || Object.isFrozen(obj)) {
    return obj
  }
  Object.freeze(obj)
  for (const key of Object.keys(obj)) {
    const val = (obj as Record<string, unknown>)[key]
    if (val !== null && typeof val === 'object' && !Object.isFrozen(val)) {
      deepFreeze(val)
    }
  }
  return obj
}

function rejectUnknownAndRequireFields(
  obj: Record<string, unknown>,
  allowed: Set<string>,
): void {
  const keys = Object.keys(obj)
  if (keys.length !== allowed.size) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }
  for (const key of keys) {
    if (!allowed.has(key)) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
  }
}

function requireSafePositiveInteger(value: unknown): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < 1 ||
    value > MAX_SAFE_INTEGER
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }
  return value
}

function decodeColor(value: number): CellColor | null {
  if (value === -1) {
    return null
  }
  if (value <= -2 && value >= -257) {
    return indexedColor(-2 - value)
  }
  if (value >= 0 && value <= 0xffffff) {
    return rgbFromPacked(value)
  }
  throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
}

/**
 * 解码并严格校验 trusted app frame value 为只读不可变 TerminalViewUpdate。
 */
export function decodeTerminalViewUpdate(value: unknown): TerminalViewUpdate {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }
  const root = value as Record<string, unknown>
  rejectUnknownAndRequireFields(root, UPDATE_FIELDS)

  const type = root.type
  if (type !== 'RESET' && type !== 'PATCH') {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const terminalId = root.terminalId
  if (typeof terminalId !== 'string' || !UUID_REGEX.test(terminalId)) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const streamId = root.streamId
  if (typeof streamId !== 'string' || !UUID_REGEX.test(streamId)) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  let baseVersion: number | null
  if (type === 'RESET') {
    if (root.baseVersion !== null) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    baseVersion = null
  } else {
    baseVersion = requireSafePositiveInteger(root.baseVersion)
  }

  const version = requireSafePositiveInteger(root.version)
  if (type === 'PATCH' && version <= baseVersion!) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const cols = root.cols
  if (
    typeof cols !== 'number' ||
    !Number.isSafeInteger(cols) ||
    cols < MIN_COLUMNS ||
    cols > MAX_COLUMNS
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const rows = root.rows
  if (
    typeof rows !== 'number' ||
    !Number.isSafeInteger(rows) ||
    rows < MIN_ROWS ||
    rows > MAX_ROWS
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const alternate = root.alternate
  if (typeof alternate !== 'boolean') {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const history = root.history
  if (
    typeof history !== 'number' ||
    !Number.isSafeInteger(history) ||
    history < 0 ||
    history > MAX_HISTORY_LINES
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  // cursor 校验
  if (
    typeof root.cursor !== 'object' ||
    root.cursor === null ||
    Array.isArray(root.cursor)
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }
  const cursorObj = root.cursor as Record<string, unknown>
  rejectUnknownAndRequireFields(cursorObj, CURSOR_FIELDS)

  const cursorX = cursorObj.x
  if (
    typeof cursorX !== 'number' ||
    !Number.isSafeInteger(cursorX) ||
    cursorX < 0 ||
    cursorX > cols
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const cursorY = cursorObj.y
  if (
    typeof cursorY !== 'number' ||
    !Number.isSafeInteger(cursorY) ||
    cursorY < 0 ||
    cursorY > rows - 1
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const cursorVisible = cursorObj.visible
  if (typeof cursorVisible !== 'boolean') {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const shapeVal = cursorObj.shape
  let cursorShape: CursorShape | null
  if (shapeVal === null) {
    cursorShape = null
  } else if (typeof shapeVal === 'string' && CURSOR_SHAPES.has(shapeVal as CursorShape)) {
    cursorShape = shapeVal as CursorShape
  } else {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const inputModeRevision = requireSafePositiveInteger(root.inputModeRevision)

  // inputModes 校验
  if (
    typeof root.inputModes !== 'object' ||
    root.inputModes === null ||
    Array.isArray(root.inputModes)
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }
  const modesObj = root.inputModes as Record<string, unknown>
  rejectUnknownAndRequireFields(modesObj, INPUT_MODE_FIELDS)

  if (
    typeof modesObj.applicationCursor !== 'boolean' ||
    typeof modesObj.applicationKeypad !== 'boolean' ||
    typeof modesObj.bracketedPaste !== 'boolean' ||
    typeof modesObj.autoNewLine !== 'boolean' ||
    typeof modesObj.altSendsEscape !== 'boolean'
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  if (
    typeof modesObj.mouseMode !== 'string' ||
    !MOUSE_MODES.has(modesObj.mouseMode as MouseMode)
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  if (
    typeof modesObj.mouseFormat !== 'string' ||
    !MOUSE_FORMATS.has(modesObj.mouseFormat as MouseFormat)
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const inputModes: TerminalInputModes = Object.freeze({
    applicationCursor: modesObj.applicationCursor,
    applicationKeypad: modesObj.applicationKeypad,
    bracketedPaste: modesObj.bracketedPaste,
    autoNewLine: modesObj.autoNewLine,
    altSendsEscape: modesObj.altSendsEscape,
    mouseMode: modesObj.mouseMode as MouseMode,
    mouseFormat: modesObj.mouseFormat as MouseFormat,
  })

  const historyTrim = root.historyTrim
  if (
    typeof historyTrim !== 'number' ||
    !Number.isSafeInteger(historyTrim) ||
    historyTrim < 0 ||
    historyTrim > MAX_HISTORY_LINES
  ) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  if (!Array.isArray(root.historyAppend) || !Array.isArray(root.screenRows) || !Array.isArray(root.styles)) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  const historyAppendRaw = root.historyAppend
  const screenRowsRaw = root.screenRows
  const stylesRaw = root.styles

  if (type === 'RESET') {
    if (historyTrim !== 0) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (historyAppendRaw.length !== history) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (screenRowsRaw.length !== rows) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
  } else {
    if (historyAppendRaw.length > history) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (screenRowsRaw.length > rows) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
  }

  if (alternate) {
    if (history !== 0 || historyTrim !== 0 || historyAppendRaw.length !== 0) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
  }

  // 展开槽数组前先按声明的 history/rows 预算校验，字典大小也不得超过本消息实际接受的槽总量
  const slotBudget = (historyAppendRaw.length + screenRowsRaw.length) * cols
  if (stylesRaw.length > slotBudget) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  // 解码 styles 字典
  const declaredStyles: SlotStyle[] = new Array(stylesRaw.length)
  const styleKeys: string[] = new Array(stylesRaw.length)

  for (let i = 0; i < stylesRaw.length; i++) {
    const entry = stylesRaw[i]
    if (!Array.isArray(entry) || entry.length !== 3) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const fgVal = entry[0]
    const bgVal = entry[1]
    const flagsVal = entry[2]
    if (
      typeof fgVal !== 'number' ||
      !Number.isSafeInteger(fgVal) ||
      typeof bgVal !== 'number' ||
      !Number.isSafeInteger(bgVal) ||
      typeof flagsVal !== 'number' ||
      !Number.isSafeInteger(flagsVal) ||
      flagsVal < 0 ||
      flagsVal > 255
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const fg = decodeColor(fgVal)
    const bg = decodeColor(bgVal)
    const style: SlotStyle = Object.freeze({
      fg,
      bg,
      bold: (flagsVal & 0x01) !== 0,
      dim: (flagsVal & 0x02) !== 0,
      italic: (flagsVal & 0x04) !== 0,
      underline: (flagsVal & 0x08) !== 0,
      blink: (flagsVal & 0x10) !== 0,
      inverse: (flagsVal & 0x20) !== 0,
      hidden: (flagsVal & 0x40) !== 0,
      strikethrough: (flagsVal & 0x80) !== 0,
    })
    declaredStyles[i] = style
    styleKeys[i] = `${fgVal}:${bgVal}:${flagsVal}`
  }

  // Canonical first-seen 字典校验与跟踪
  const canonicalStyles: SlotStyle[] = []
  const canonicalIndexByKey = new Map<string, number>()

  function resolveStyle(styleIndex: unknown): SlotStyle {
    if (
      typeof styleIndex !== 'number' ||
      !Number.isSafeInteger(styleIndex) ||
      styleIndex < 0 ||
      styleIndex >= declaredStyles.length
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const style = declaredStyles[styleIndex]
    const key = styleKeys[styleIndex]
    let canonicalIndex = canonicalIndexByKey.get(key)
    if (canonicalIndex === undefined) {
      canonicalIndex = canonicalStyles.length
      canonicalStyles.push(style)
      canonicalIndexByKey.set(key, canonicalIndex)
    }
    if (canonicalIndex !== styleIndex) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    return style
  }

  function decodeLine(lineNode: unknown): MirroredLine {
    if (!Array.isArray(lineNode) || lineNode.length !== 3) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const id = lineNode[0]
    if (
      typeof id !== 'number' ||
      !Number.isSafeInteger(id) ||
      id < 1 ||
      id > MAX_SAFE_INTEGER
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const wrapped = lineNode[1]
    if (typeof wrapped !== 'boolean') {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const slotsNode = lineNode[2]
    if (!Array.isArray(slotsNode) || slotsNode.length !== cols) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const slots: GridSlot[] = new Array(cols)
    for (let c = 0; c < cols; c++) {
      const slotNode = slotsNode[c]
      if (!Array.isArray(slotNode) || slotNode.length !== 3) {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      const kindVal = slotNode[0]
      const code = slotNode[1]
      const styleIdx = slotNode[2]
      if (
        typeof kindVal !== 'number' ||
        !Number.isSafeInteger(kindVal) ||
        typeof code !== 'number' ||
        !Number.isSafeInteger(code) ||
        code < 0 ||
        code > 0xffff
      ) {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      let kind: SlotKind
      if (kindVal === 0) {
        kind = 'unit'
      } else if (kindVal === 1) {
        kind = 'empty'
        if (code !== 0) {
          throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
        }
      } else if (kindVal === 2) {
        kind = 'dwc'
        if (code !== DWC_SLOT_CODE) {
          throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
        }
      } else {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      const style = resolveStyle(styleIdx)
      slots[c] = Object.freeze({ kind, code, style })
    }
    return Object.freeze({
      id,
      wrapped,
      slots: Object.freeze(slots),
    })
  }

  const historyAppend: MirroredLine[] = new Array(historyAppendRaw.length)
  for (let i = 0; i < historyAppendRaw.length; i++) {
    historyAppend[i] = decodeLine(historyAppendRaw[i])
  }

  const screenRows: RowChange[] = new Array(screenRowsRaw.length)
  const replacedRows = new Set<number>()
  for (let i = 0; i < screenRowsRaw.length; i++) {
    const itemNode = screenRowsRaw[i]
    if (
      typeof itemNode !== 'object' ||
      itemNode === null ||
      Array.isArray(itemNode)
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    const itemObj = itemNode as Record<string, unknown>
    rejectUnknownAndRequireFields(itemObj, SCREEN_ROW_FIELDS)
    const row = itemObj.row
    if (
      typeof row !== 'number' ||
      !Number.isSafeInteger(row) ||
      row < 0 ||
      row >= rows
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (replacedRows.has(row)) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    replacedRows.add(row)
    const line = decodeLine(itemObj.line)
    screenRows[i] = Object.freeze({ row, line })
  }

  // 严格要求 styles 字典无冗余未引用项
  if (canonicalStyles.length !== declaredStyles.length) {
    throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
  }

  // 消息内历史与屏幕行 id 整体唯一
  const activeIds = new Set<number>()
  for (const line of historyAppend) {
    if (activeIds.has(line.id)) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    activeIds.add(line.id)
  }
  for (const change of screenRows) {
    if (activeIds.has(change.line.id)) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    activeIds.add(change.line.id)
  }

  const update: TerminalViewUpdate = {
    type,
    terminalId,
    streamId,
    baseVersion,
    version,
    cols,
    rows,
    alternate,
    history,
    cursorX,
    cursorY,
    cursorVisible,
    cursorShape,
    inputModeRevision,
    inputModes,
    historyTrim,
    historyAppend: Object.freeze(historyAppend),
    screenRows: Object.freeze(screenRows),
  }

  return deepFreeze(update)
}
