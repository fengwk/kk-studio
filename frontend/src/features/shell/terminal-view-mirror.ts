/**
 * 终端原子镜像与代际栅栏（Atomic Terminal View Mirror）。
 *
 * 维护单终端的权威只读数值镜像：
 * - 强制代际栅栏：beginStream 显式声明期望 terminalId/streamId，拒绝未授权或错流切换；
 * - 严格 RESET / PATCH 状态机校验，全量构建并验证候选状态后再原子赋值；
 * - 任何不符合约束的更新均抛出固定 TerminalViewError 并不修改已有状态与引用；
 * - 拓扑变化（historyTrim / historyAppend）全屏标记 dirtyRows，纯行替换定向标记绝对行号；
 * - 未触及的不可变 MirroredLine 保持原对象引用，不产生全量克隆或日志开销。
 */

import type { TerminalGrid } from './terminal-grid'
import {
  TERMINAL_VIEW_INVALID_MESSAGE,
  TERMINAL_VIEW_MISMATCH_MESSAGE,
  TerminalViewError,
  type CursorShape,
  type MirroredLine,
  type TerminalInputModes,
  type TerminalViewUpdate,
} from './terminal-view-codec'

export type { MirroredLine } from './terminal-view-codec'

/** 终端数值镜像状态，扩展自现有 TerminalGrid。 */
export interface TerminalMirrorState extends TerminalGrid {
  readonly terminalId: string
  readonly streamId: string
  readonly version: number
  readonly cursorVisible: boolean
  readonly cursorShape: CursorShape | null
  readonly inputModeRevision: number
  readonly inputModes: TerminalInputModes
  readonly lines: readonly MirroredLine[]
}

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

export class TerminalViewMirror {
  private _expectedTerminalId: string | null = null
  private _expectedStreamId: string | null = null
  private _current: TerminalMirrorState | null = null

  /**
   * 显式开启新数据流，清理旧状态并建立代际边界。
   */
  beginStream(terminalId: string, streamId: string): void {
    if (
      typeof terminalId !== 'string' ||
      !UUID_REGEX.test(terminalId) ||
      typeof streamId !== 'string' ||
      !UUID_REGEX.test(streamId)
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    this._expectedTerminalId = terminalId
    this._expectedStreamId = streamId
    this._current = null
  }

  /**
   * 返回当前权威画面镜像，未初始化或已 clear 时返回 null。
   */
  current(): TerminalMirrorState | null {
    return this._current
  }

  /**
   * 清理当前镜像状态。
   */
  clear(): void {
    this._current = null
    this._expectedTerminalId = null
    this._expectedStreamId = null
  }

  /**
   * 原子应用更新并返回最新状态与受影响绝对行号。
   */
  apply(update: TerminalViewUpdate): {
    readonly state: TerminalMirrorState
    readonly dirtyRows: readonly number[]
  } {
    if (
      this._expectedTerminalId === null ||
      this._expectedStreamId === null ||
      update.terminalId !== this._expectedTerminalId ||
      update.streamId !== this._expectedStreamId
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_MISMATCH_MESSAGE)
    }

    if (this._current === null) {
      if (update.type !== 'RESET') {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      return this.applyReset(update)
    }

    if (update.type === 'RESET') {
      if (update.version <= this._current.version) {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      if (update.inputModeRevision < this._current.inputModeRevision) {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      return this.applyReset(update)
    }

    // PATCH
    if (update.baseVersion !== this._current.version) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (update.version <= update.baseVersion) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (
      update.cols !== this._current.cols ||
      update.rows !== this._current.rows ||
      update.alternate !== this._current.alternate
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (update.inputModeRevision < this._current.inputModeRevision) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (update.historyTrim > this._current.history) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }
    if (
      this._current.history - update.historyTrim + update.historyAppend.length !==
      update.history
    ) {
      throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
    }

    return this.applyPatch(update)
  }

  private applyReset(update: TerminalViewUpdate): {
    readonly state: TerminalMirrorState
    readonly dirtyRows: readonly number[]
  } {
    const screenLines: MirroredLine[] = new Array(update.rows)
    for (const change of update.screenRows) {
      screenLines[change.row] = change.line
    }

    const candidateLines: MirroredLine[] = [
      ...update.historyAppend,
      ...screenLines,
    ]

    const activeIds = new Set<number>()
    for (const line of candidateLines) {
      if (activeIds.has(line.id)) {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      activeIds.add(line.id)
    }

    const candidateState: TerminalMirrorState = Object.freeze({
      terminalId: update.terminalId,
      streamId: update.streamId,
      version: update.version,
      cols: update.cols,
      rows: update.rows,
      cursorX: update.cursorX,
      cursorY: update.cursorY,
      cursorVisible: update.cursorVisible,
      cursorShape: update.cursorShape,
      alternate: update.alternate,
      history: update.history,
      inputModeRevision: update.inputModeRevision,
      inputModes: update.inputModes,
      lines: Object.freeze(candidateLines),
    })

    const dirtyRows = Object.freeze(
      Array.from({ length: candidateLines.length }, (_, i) => i),
    )

    this._current = candidateState
    return { state: candidateState, dirtyRows }
  }

  private applyPatch(update: TerminalViewUpdate): {
    readonly state: TerminalMirrorState
    readonly dirtyRows: readonly number[]
  } {
    const current = this._current!

    const retainedHistory = current.lines.slice(
      update.historyTrim,
      current.history,
    )
    const newHistory = [...retainedHistory, ...update.historyAppend]

    const retainedScreen = current.lines.slice(
      current.history,
      current.history + current.rows,
    )
    const newScreen = retainedScreen.slice()
    for (const change of update.screenRows) {
      newScreen[change.row] = change.line
    }

    const candidateLines: MirroredLine[] = [...newHistory, ...newScreen]

    // 全量最终行 ID 唯一性校验（涵盖历史行与未修改的屏幕行）
    const activeIds = new Set<number>()
    for (const line of candidateLines) {
      if (activeIds.has(line.id)) {
        throw new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE)
      }
      activeIds.add(line.id)
    }

    const candidateState: TerminalMirrorState = Object.freeze({
      terminalId: update.terminalId,
      streamId: update.streamId,
      version: update.version,
      cols: update.cols,
      rows: update.rows,
      cursorX: update.cursorX,
      cursorY: update.cursorY,
      cursorVisible: update.cursorVisible,
      cursorShape: update.cursorShape,
      alternate: update.alternate,
      history: update.history,
      inputModeRevision: update.inputModeRevision,
      inputModes: update.inputModes,
      lines: Object.freeze(candidateLines),
    })

    let dirtyRows: readonly number[]
    if (update.historyTrim > 0 || update.historyAppend.length > 0) {
      // 历史拓扑位移使绝对行号映射偏移，全部行均为 dirty
      dirtyRows = Object.freeze(
        Array.from({ length: candidateLines.length }, (_, i) => i),
      )
    } else {
      // 无拓扑偏移，仅新替换的屏幕行绝对行号为 dirty
      const changedIndices = update.screenRows
        .map((c) => update.history + c.row)
        .sort((a, b) => a - b)
      dirtyRows = Object.freeze(changedIndices)
    }

    this._current = candidateState
    return { state: candidateState, dirtyRows }
  }
}
