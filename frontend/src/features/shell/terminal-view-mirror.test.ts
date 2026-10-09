/** TerminalViewMirror 单元测试：代际流栅栏、行对象引用保持与原子状态机校验。 */

import { describe, expect, it } from 'vitest'
import resetFixture from './__fixtures__/reset-update.json'
import patchMetadataFixture from './__fixtures__/patch-metadata.json'
import {
  decodeTerminalViewUpdate,
  TerminalViewError,
  TERMINAL_VIEW_INVALID_MESSAGE,
  TERMINAL_VIEW_MISMATCH_MESSAGE,
  type TerminalViewUpdate,
} from './terminal-view-codec'
import { TerminalViewMirror } from './terminal-view-mirror'

const TERMINAL_ID = '11111111-1111-1111-1111-111111111111'
const STREAM_ID = '22222222-2222-2222-2222-222222222222'
const OTHER_TERMINAL_ID = '33333333-3333-3333-3333-333333333333'
const OTHER_STREAM_ID = '44444444-4444-4444-4444-444444444444'

function clone<T>(val: T): T {
  return JSON.parse(JSON.stringify(val))
}

function makeReset(
  version = 1,
  cols = 6,
  rows = 2,
  history = 1,
  inputModeRevision = 1,
): TerminalViewUpdate {
  const f = clone(resetFixture) as Record<string, unknown>
  f.version = version
  f.cols = cols
  f.rows = rows
  f.history = history
  f.inputModeRevision = inputModeRevision
  f.styles = [[-1, -1, 0]]
  const makeLine = (id: number): unknown[] => [
    id,
    false,
    Array.from({ length: cols }, () => [1, 0, 0]),
  ]
  const historyAppend: unknown[] = []
  for (let i = 0; i < history; i++) {
    historyAppend.push(makeLine(100 + i))
  }
  f.historyAppend = historyAppend
  const screenRows: unknown[] = []
  for (let r = 0; r < rows; r++) {
    screenRows.push({ row: r, line: makeLine(200 + r) })
  }
  f.screenRows = screenRows
  return decodeTerminalViewUpdate(f)
}

function makePatch(baseVersion: number, version: number, overrides?: Record<string, unknown>): TerminalViewUpdate {
  const f = clone(patchMetadataFixture) as Record<string, unknown>
  f.baseVersion = baseVersion
  f.version = version
  f.inputModeRevision = 1
  if (overrides) {
    Object.assign(f, overrides)
  }
  return decodeTerminalViewUpdate(f)
}

describe('terminal-view-mirror', () => {
  describe('代际流栅栏与 beginStream / clear 边界', () => {
    it('初始状态为 null，未调用 beginStream 时拒绝 apply', () => {
      const mirror = new TerminalViewMirror()
      expect(mirror.current()).toBeNull()

      const reset = decodeTerminalViewUpdate(resetFixture)
      expect(() => mirror.apply(reset)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_MISMATCH_MESSAGE),
      )
      expect(mirror.current()).toBeNull()
    })

    it('beginStream 拒绝非规范 UUID', () => {
      const mirror = new TerminalViewMirror()
      expect(() => mirror.beginStream('INVALID', STREAM_ID)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(() => mirror.beginStream(TERMINAL_ID, 'INVALID')).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
    })

    it('beginStream 建立期望边界后接受初始 RESET', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      expect(mirror.current()).toBeNull()

      const reset = decodeTerminalViewUpdate(resetFixture)
      const { state, dirtyRows } = mirror.apply(reset)
      expect(state.terminalId).toBe(TERMINAL_ID)
      expect(state.streamId).toBe(STREAM_ID)
      expect(state.version).toBe(7)
      expect(state.lines.length).toBe(3) // 1 history + 2 screen
      expect(dirtyRows).toEqual([0, 1, 2])
      expect(mirror.current()).toBe(state)
    })

    it('初始更新如果是 PATCH 则拒绝且不修改状态', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)

      const patch = decodeTerminalViewUpdate(patchMetadataFixture)
      expect(() => mirror.apply(patch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBeNull()
    })

    it('错流（mismatched terminalId 或 streamId）更新被拦截且状态不被篡改', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      const reset = decodeTerminalViewUpdate(resetFixture)
      const { state } = mirror.apply(reset)

      // 错 terminalId
      const f1 = clone(patchMetadataFixture) as Record<string, unknown>
      f1.terminalId = OTHER_TERMINAL_ID
      f1.baseVersion = 7
      f1.version = 8
      const badTerminalPatch = decodeTerminalViewUpdate(f1)
      expect(() => mirror.apply(badTerminalPatch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_MISMATCH_MESSAGE),
      )
      expect(mirror.current()).toBe(state)

      // 错 streamId
      const f2 = clone(patchMetadataFixture) as Record<string, unknown>
      f2.streamId = OTHER_STREAM_ID
      f2.baseVersion = 7
      f2.version = 8
      const badStreamPatch = decodeTerminalViewUpdate(f2)
      expect(() => mirror.apply(badStreamPatch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_MISMATCH_MESSAGE),
      )
      expect(mirror.current()).toBe(state)

      // 错 terminal 的 RESET 也必须拒绝，不能任意切换流
      const otherResetRaw = clone(resetFixture) as Record<string, unknown>
      otherResetRaw.terminalId = OTHER_TERMINAL_ID
      const otherReset = decodeTerminalViewUpdate(otherResetRaw)
      expect(() => mirror.apply(otherReset)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_MISMATCH_MESSAGE),
      )
      expect(mirror.current()).toBe(state)
    })

    it('clear 显式清理状态并重置流，后续 apply 必须重新 beginStream', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(decodeTerminalViewUpdate(resetFixture))
      expect(mirror.current()).not.toBeNull()

      mirror.clear()
      expect(mirror.current()).toBeNull()

      // 未重新 beginStream 时拒绝
      expect(() => mirror.apply(decodeTerminalViewUpdate(resetFixture))).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_MISMATCH_MESSAGE),
      )
    })
  })

  describe('后序匹配流 RESET 校验', () => {
    it('匹配流后序 RESET 版本必须严格大于当前版本', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(5))
      const current = mirror.current()!

      // 相同版本（重放/重叠）拒绝
      expect(() => mirror.apply(makeReset(5))).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)

      // 倒退版本拒绝
      expect(() => mirror.apply(makeReset(4))).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)

      // 严格递增版本被接受
      const later = makeReset(6)
      const { state, dirtyRows } = mirror.apply(later)
      expect(state.version).toBe(6)
      expect(dirtyRows).toEqual([0, 1, 2])
      expect(mirror.current()).toBe(state)
    })

    it('后序 RESET 支持尺寸重设（Resize RESET）与 Alternate 切换', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(1, 6, 2, 1))

      // 调整为 8 列 3 行 0 历史，alternate 为 true
      const rawAltReset = clone(resetFixture) as Record<string, unknown>
      rawAltReset.version = 2
      rawAltReset.cols = 8
      rawAltReset.rows = 3
      rawAltReset.alternate = true
      rawAltReset.history = 0
      rawAltReset.historyAppend = []
      rawAltReset.styles = [[-1, -1, 0]]
      rawAltReset.screenRows = [
        { row: 0, line: [301, false, Array.from({ length: 8 }, () => [1, 0, 0])] },
        { row: 1, line: [302, false, Array.from({ length: 8 }, () => [1, 0, 0])] },
        { row: 2, line: [303, false, Array.from({ length: 8 }, () => [1, 0, 0])] },
      ]
      const altReset = decodeTerminalViewUpdate(rawAltReset)
      const { state, dirtyRows } = mirror.apply(altReset)
      expect(state.cols).toBe(8)
      expect(state.rows).toBe(3)
      expect(state.alternate).toBe(true)
      expect(state.history).toBe(0)
      expect(dirtyRows).toEqual([0, 1, 2])
    })

    it('后序 RESET 的 inputModeRevision 不得小于当前值（单调性）', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      const r1Raw = clone(resetFixture) as Record<string, unknown>
      r1Raw.version = 1
      r1Raw.inputModeRevision = 10
      mirror.apply(decodeTerminalViewUpdate(r1Raw))
      const current = mirror.current()!

      // 尝试递增版本但降低 revision
      const r2Raw = clone(resetFixture) as Record<string, unknown>
      r2Raw.version = 2
      r2Raw.inputModeRevision = 9
      const r2 = decodeTerminalViewUpdate(r2Raw)
      expect(() => mirror.apply(r2)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })
  })

  describe('PATCH 增量代际与一致性校验', () => {
    it('PATCH baseVersion 必须严格等于 current.version（杜绝版本断层）', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(5))
      const current = mirror.current()!

      // baseVersion 越过当前版本（断层）
      const gapPatch = makePatch(6, 7)
      expect(() => mirror.apply(gapPatch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)

      // baseVersion 落后当前版本（陈旧）
      const stalePatch = makePatch(4, 6)
      expect(() => mirror.apply(stalePatch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })

    it('PATCH 必须保持当前 cols、rows 与 alternate 不变', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(5, 6, 2, 0))
      const current = mirror.current()!

      // cols 不符
      const badCols = makePatch(5, 6, { cols: 7 })
      expect(() => mirror.apply(badCols)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)

      // rows 不符
      const badRows = makePatch(5, 6, { rows: 3 })
      expect(() => mirror.apply(badRows)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)

      // alternate 不符
      const badAlt = makePatch(5, 6, { alternate: true })
      expect(() => mirror.apply(badAlt)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })

    it('PATCH inputModeRevision 不得递减', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      const rRaw = clone(resetFixture) as Record<string, unknown>
      rRaw.version = 1
      rRaw.inputModeRevision = 5
      mirror.apply(decodeTerminalViewUpdate(rRaw))
      const current = mirror.current()!

      const badRev = makePatch(1, 2, { inputModeRevision: 4 })
      expect(() => mirror.apply(badRev)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })

    it('PATCH 历史裁剪超限或历史长度算式不符时拒绝', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(1, 6, 2, 2)) // 当前 history = 2
      const current = mirror.current()!

      // historyTrim > current.history (trim 3 > 2)
      const badTrimRaw = clone(patchMetadataFixture) as Record<string, unknown>
      badTrimRaw.baseVersion = 1
      badTrimRaw.version = 2
      badTrimRaw.historyTrim = 3
      badTrimRaw.history = 0
      const badTrim = decodeTerminalViewUpdate(badTrimRaw)
      expect(() => mirror.apply(badTrim)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)

      // oldHistory(2) - trim(1) + append(0) != declared history(2)
      const badMathRaw = clone(patchMetadataFixture) as Record<string, unknown>
      badMathRaw.baseVersion = 1
      badMathRaw.version = 2
      badMathRaw.historyTrim = 1
      badMathRaw.history = 2 // 期望为 1
      const badMath = decodeTerminalViewUpdate(badMathRaw)
      expect(() => mirror.apply(badMath)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })
  })

  describe('行对象引用保持与历史裁剪/追加语义', () => {
    it('未触及的屏幕行与未裁剪的历史行保持严格原对象引用（toBe 恒等性）', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)

      // RESET: 2 行历史 [10, 11] + 2 行屏幕 [20, 21]
      const resetRaw = clone(resetFixture) as Record<string, unknown>
      resetRaw.version = 1
      resetRaw.inputModeRevision = 1
      resetRaw.history = 2
      resetRaw.styles = [[-1, -1, 0]]
      const makeLine = (id: number): unknown[] => [id, false, Array.from({ length: 6 }, () => [1, 0, 0])]
      resetRaw.historyAppend = [makeLine(10), makeLine(11)]
      resetRaw.screenRows = [
        { row: 0, line: makeLine(20) },
        { row: 1, line: makeLine(21) },
      ]
      const { state: s0 } = mirror.apply(decodeTerminalViewUpdate(resetRaw))
      const h0 = s0.lines[0]
      const h1 = s0.lines[1]
      const row1 = s0.lines[3]

      // PATCH 1: 仅替换屏幕 row 0 为新行 30，历史不变
      const patch1Raw = clone(patchMetadataFixture) as Record<string, unknown>
      patch1Raw.baseVersion = 1
      patch1Raw.version = 2
      patch1Raw.inputModeRevision = 1
      patch1Raw.history = 2
      patch1Raw.historyTrim = 0
      patch1Raw.historyAppend = []
      patch1Raw.styles = [[-1, -1, 0]]
      patch1Raw.screenRows = [{ row: 0, line: makeLine(30) }]
      const { state: s1, dirtyRows: d1 } = mirror.apply(decodeTerminalViewUpdate(patch1Raw))

      // 历史行与未修改的 screen row 1 保持对象恒等
      expect(s1.lines[0]).toBe(h0)
      expect(s1.lines[1]).toBe(h1)
      expect(s1.lines[2].id).toBe(30)
      expect(s1.lines[3]).toBe(row1) // row 1 完全未变，引用恒等！
      // 仅 row 0 被改动，绝对索引为 history(2) + 0 = 2
      expect(d1).toEqual([2])

      // PATCH 2: 从队首裁剪 1 行历史，追加 1 行历史 [40]，屏幕不改
      const patch2Raw = clone(patchMetadataFixture) as Record<string, unknown>
      patch2Raw.baseVersion = 2
      patch2Raw.version = 3
      patch2Raw.inputModeRevision = 1
      patch2Raw.history = 2 // 2 - 1 + 1 = 2
      patch2Raw.historyTrim = 1
      patch2Raw.historyAppend = [makeLine(40)]
      patch2Raw.styles = [[-1, -1, 0]]
      patch2Raw.screenRows = []
      const { state: s2, dirtyRows: d2 } = mirror.apply(decodeTerminalViewUpdate(patch2Raw))

      // h0 已被裁剪；s2 的首行应为原 h1 且引用保持
      expect(s2.lines[0]).toBe(h1)
      expect(s2.lines[1].id).toBe(40)
      expect(s2.lines[2].id).toBe(30)
      expect(s2.lines[3]).toBe(row1)
      // 发生历史位移，所有绝对位置均标记 dirty
      expect(d2).toEqual([0, 1, 2, 3])
    })
  })

  describe('全局活动行 ID 碰撞拒绝', () => {
    it('屏幕行替换使用已存在于未修改历史行中的 ID 时被拒绝', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(1, 6, 2, 1)) // 历史行 ID 为 100，屏幕行 ID 为 200, 201
      const current = mirror.current()!

      // PATCH 尝试将 screen row 0 替换为 ID 100（与保留的历史行冲突）
      const raw = clone(patchMetadataFixture) as Record<string, unknown>
      raw.baseVersion = 1
      raw.version = 2
      raw.history = 1
      raw.historyTrim = 0
      raw.historyAppend = []
      raw.styles = [[-1, -1, 0]]
      raw.screenRows = [
        { row: 0, line: [100, false, Array.from({ length: 6 }, () => [1, 0, 0])] },
      ]
      const badPatch = decodeTerminalViewUpdate(raw)
      expect(() => mirror.apply(badPatch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })

    it('历史追加行使用已存在于未修改屏幕行中的 ID 时被拒绝', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(1, 6, 2, 0)) // 历史 0，屏幕行 ID 为 200, 201
      const current = mirror.current()!

      // PATCH 追加历史行，其 ID 为 200（与未修改的 screen row 0 冲突）
      const raw = clone(patchMetadataFixture) as Record<string, unknown>
      raw.baseVersion = 1
      raw.version = 2
      raw.history = 1
      raw.historyTrim = 0
      raw.historyAppend = [[200, false, Array.from({ length: 6 }, () => [1, 0, 0])]]
      raw.styles = [[-1, -1, 0]]
      raw.screenRows = []
      const badPatch = decodeTerminalViewUpdate(raw)
      expect(() => mirror.apply(badPatch)).toThrowError(
        new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
      )
      expect(mirror.current()).toBe(current)
    })

    it('相同空白行内容但 ID 相同的行被拒绝，不同 ID 的空白行被接纳', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)

      // 两个不同 ID 的空白行：合法
      const validReset = clone(resetFixture) as Record<string, unknown>
      validReset.version = 1
      validReset.history = 0
      validReset.historyAppend = []
      validReset.styles = [[-1, -1, 0]]
      validReset.screenRows = [
        { row: 0, line: [501, false, Array.from({ length: 6 }, () => [1, 0, 0])] },
        { row: 1, line: [502, false, Array.from({ length: 6 }, () => [1, 0, 0])] },
      ]
      mirror.apply(decodeTerminalViewUpdate(validReset))
      expect(mirror.current()!.lines.length).toBe(2)

      // 两个相同 ID 的空白行：在 decode 阶段即被唯一性拒绝
      const invalidReset = clone(resetFixture) as Record<string, unknown>
      invalidReset.version = 2
      invalidReset.history = 0
      invalidReset.historyAppend = []
      invalidReset.styles = [[-1, -1, 0]]
      invalidReset.screenRows = [
        { row: 0, line: [600, false, Array.from({ length: 6 }, () => [1, 0, 0])] },
        { row: 1, line: [600, false, Array.from({ length: 6 }, () => [1, 0, 0])] },
      ]
      expect(() => decodeTerminalViewUpdate(invalidReset)).toThrow(TerminalViewError)
    })
  })

  describe('dirtyRows 精确计算', () => {
    it('纯元数据 PATCH（无历史变动且无屏幕行修改）dirtyRows 为 []', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(3, 6, 2, 0, 4))

      const patchMeta = decodeTerminalViewUpdate(patchMetadataFixture) // baseVersion: 3, version: 4
      const { state, dirtyRows } = mirror.apply(patchMeta)
      expect(state.version).toBe(4)
      expect(dirtyRows).toEqual([])
      expect(state.cursorX).toBe(0)
      expect(state.cursorY).toBe(1)
    })

    it('多行同时替换时按升序排列 dirtyRows', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      mirror.apply(makeReset(1, 6, 3, 1)) // 1 历史 + 3 屏幕 (rows: 0, 1, 2 -> 绝对索引 1, 2, 3)

      const makeLine = (id: number): unknown[] => [id, false, Array.from({ length: 6 }, () => [1, 0, 0])]
      const raw = clone(patchMetadataFixture) as Record<string, unknown>
      raw.baseVersion = 1
      raw.version = 2
      raw.inputModeRevision = 1
      raw.rows = 3
      raw.history = 1
      raw.historyTrim = 0
      raw.historyAppend = []
      raw.styles = [[-1, -1, 0]]
      // 逆序提供 row 2 与 row 0
      raw.screenRows = [
        { row: 2, line: makeLine(302) },
        { row: 0, line: makeLine(300) },
      ]
      const { dirtyRows } = mirror.apply(decodeTerminalViewUpdate(raw))
      expect(dirtyRows).toEqual([1, 3]) // 绝对索引 1 (row 0) 与 3 (row 2)
    })

    it('直接传入 typed update 时的版本与 ID 边界保护', () => {
      const mirror = new TerminalViewMirror()
      mirror.beginStream(TERMINAL_ID, STREAM_ID)
      const r = makeReset(5)
      mirror.apply(r)

      // version <= baseVersion
      const badVersionPatch = {
        ...r,
        type: 'PATCH' as const,
        baseVersion: 5,
        version: 5,
      }
      expect(() => mirror.apply(badVersionPatch as unknown as TerminalViewUpdate)).toThrow(
        TerminalViewError,
      )

      // RESET 内重复行 ID
      const badReset = {
        ...r,
        version: 6,
        lines: r.lines,
        screenRows: [
          { row: 0, line: { ...r.screenRows[0].line, id: 999 } },
          { row: 1, line: { ...r.screenRows[1].line, id: 999 } },
        ],
      }
      expect(() => mirror.apply(badReset as unknown as TerminalViewUpdate)).toThrow(
        TerminalViewError,
      )
    })
  })
})
