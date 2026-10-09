/**
 * TerminalViewUpdateCodec 单元测试。
 *
 * 覆盖：
 * 1. Java 权威测试固件等价性（reset-update.json / patch-update.json / patch-metadata.json）
 * 2. 数值保真：UTF-16 单元、NUL、FEFF、代理项、DWC 槽
 * 3. 颜色模型：默认颜色（-1）、256 色索引（-2..-257 -> 0..255）、RGB 真彩极值（0..16777215）
 * 4. 样式字典：Canonical First-Seen 顺序校验、单例复用、无冗余项、预算前置校验
 * 5. 全字段与边界严格校验：未知字段、缺失字段、非规范 UUID、越界安全整数、行 ID 唯一性
 */

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import resetFixture from './__fixtures__/reset-update.json'
import patchFixture from './__fixtures__/patch-update.json'
import patchMetadataFixture from './__fixtures__/patch-metadata.json'
import {
  decodeTerminalViewUpdate,
  TerminalViewError,
  TERMINAL_VIEW_INVALID_MESSAGE,
  type CursorShape,
  type MouseFormat,
  type MouseMode,
} from './terminal-view-codec'

function clone<T>(val: T): T {
  return JSON.parse(JSON.stringify(val))
}

describe('terminal-view-codec', () => {
  describe('Java 权威固件等价性', () => {
    it('前端固件副本与 Java 原生测试资源文件严格逐字节一致', () => {
      const javaFixtureDir = path.resolve(
        __dirname,
        '../../../../harness/environment/src/test/resources/fun/fengwk/kkstudio/harness/environment/terminal',
      )
      const frontendFixtureDir = path.resolve(__dirname, './__fixtures__')
      const files = ['reset-update.json', 'patch-update.json', 'patch-metadata.json']
      for (const file of files) {
        const javaContent = fs.readFileSync(path.join(javaFixtureDir, file), 'utf-8')
        const frontendContent = fs.readFileSync(path.join(frontendFixtureDir, file), 'utf-8')
        expect(frontendContent).toBe(javaContent)
      }
    })

    it('reset-update.json 解码与 Java 样本一致', () => {
      const decoded = decodeTerminalViewUpdate(resetFixture)
      expect(decoded.type).toBe('RESET')
      expect(decoded.terminalId).toBe('11111111-1111-1111-1111-111111111111')
      expect(decoded.streamId).toBe('22222222-2222-2222-2222-222222222222')
      expect(decoded.baseVersion).toBeNull()
      expect(decoded.version).toBe(7)
      expect(decoded.cols).toBe(6)
      expect(decoded.rows).toBe(2)
      expect(decoded.alternate).toBe(false)
      expect(decoded.history).toBe(1)
      expect(decoded.cursorX).toBe(3)
      expect(decoded.cursorY).toBe(1)
      expect(decoded.cursorVisible).toBe(true)
      expect(decoded.cursorShape).toBe('BLINK_BLOCK')
      expect(decoded.inputModeRevision).toBe(9)
      expect(decoded.inputModes).toEqual({
        applicationCursor: true,
        applicationKeypad: false,
        bracketedPaste: true,
        autoNewLine: false,
        altSendsEscape: true,
        mouseMode: 'BUTTON_MOTION',
        mouseFormat: 'SGR',
      })
      expect(decoded.historyTrim).toBe(0)
      expect(decoded.historyAppend.length).toBe(1)
      expect(decoded.screenRows.length).toBe(2)

      // 历史行 0 槽细节检验
      const historyLine = decoded.historyAppend[0]
      expect(historyLine.id).toBe(10)
      expect(historyLine.wrapped).toBe(false)
      expect(historyLine.slots.length).toBe(6)

      // slot 0: UNIT 'A' (0x41), 缺省样式
      expect(historyLine.slots[0].kind).toBe('unit')
      expect(historyLine.slots[0].code).toBe(0x0041)
      expect(historyLine.slots[0].style.fg).toBeNull()
      expect(historyLine.slots[0].style.bg).toBeNull()
      expect(historyLine.slots[0].style.bold).toBe(false)

      // slot 1: UNIT NUL (0x0000), 缺省样式
      expect(historyLine.slots[1].kind).toBe('unit')
      expect(historyLine.slots[1].code).toBe(0x0000)

      // slot 2: UNIT 0xd800 (高代理项), RGB 红色前景
      expect(historyLine.slots[2].kind).toBe('unit')
      expect(historyLine.slots[2].code).toBe(0xd800)
      expect(historyLine.slots[2].style.fg).toEqual({
        kind: 'rgb',
        r: 255,
        g: 0,
        b: 0,
      })

      // slot 3: UNIT 0xfeff (BOM), 索引色背景 4, bold
      expect(historyLine.slots[3].kind).toBe('unit')
      expect(historyLine.slots[3].code).toBe(0xfeff)
      expect(historyLine.slots[3].style.bg).toEqual({
        kind: 'indexed',
        index: 4,
      })
      expect(historyLine.slots[3].style.bold).toBe(true)

      // slot 4: DWC 0xe000, 索引色 255 前景, RGB 白色背景, flags 255
      expect(historyLine.slots[4].kind).toBe('dwc')
      expect(historyLine.slots[4].code).toBe(0xe000)
      expect(historyLine.slots[4].style.fg).toEqual({
        kind: 'indexed',
        index: 255,
      })
      expect(historyLine.slots[4].style.bg).toEqual({
        kind: 'rgb',
        r: 255,
        g: 255,
        b: 255,
      })
      expect(historyLine.slots[4].style.bold).toBe(true)
      expect(historyLine.slots[4].style.dim).toBe(true)
      expect(historyLine.slots[4].style.italic).toBe(true)
      expect(historyLine.slots[4].style.underline).toBe(true)
      expect(historyLine.slots[4].style.blink).toBe(true)
      expect(historyLine.slots[4].style.inverse).toBe(true)
      expect(historyLine.slots[4].style.hidden).toBe(true)
      expect(historyLine.slots[4].style.strikethrough).toBe(true)

      // slot 5: EMPTY 0, 缺省样式
      expect(historyLine.slots[5].kind).toBe('empty')
      expect(historyLine.slots[5].code).toBe(0)

      // 屏幕行
      expect(decoded.screenRows[0].row).toBe(0)
      expect(decoded.screenRows[0].line.id).toBe(11)
      expect(decoded.screenRows[1].row).toBe(1)
      expect(decoded.screenRows[1].line.id).toBe(12)

      // 深度冻结检验
      expect(Object.isFrozen(decoded)).toBe(true)
      expect(Object.isFrozen(decoded.inputModes)).toBe(true)
      expect(Object.isFrozen(decoded.historyAppend)).toBe(true)
      expect(Object.isFrozen(historyLine)).toBe(true)
      expect(Object.isFrozen(historyLine.slots)).toBe(true)
      expect(Object.isFrozen(historyLine.slots[0])).toBe(true)
      expect(Object.isFrozen(historyLine.slots[0].style)).toBe(true)
    })

    it('patch-update.json 解码与增量语义一致', () => {
      const decoded = decodeTerminalViewUpdate(patchFixture)
      expect(decoded.type).toBe('PATCH')
      expect(decoded.baseVersion).toBe(7)
      expect(decoded.version).toBe(8)
      expect(decoded.cursorVisible).toBe(false)
      expect(decoded.cursorShape).toBeNull()
      expect(decoded.historyTrim).toBe(1)
      expect(decoded.historyAppend.length).toBe(2)
      expect(decoded.screenRows.length).toBe(1)
      expect(decoded.screenRows[0].row).toBe(1)
      expect(decoded.screenRows[0].line.id).toBe(22)
    })

    it('patch-metadata.json 纯元数据 PATCH 正确解码空字典与空行数组', () => {
      const decoded = decodeTerminalViewUpdate(patchMetadataFixture)
      expect(decoded.type).toBe('PATCH')
      expect(decoded.baseVersion).toBe(3)
      expect(decoded.version).toBe(4)
      expect(decoded.historyTrim).toBe(0)
      expect(decoded.historyAppend.length).toBe(0)
      expect(decoded.screenRows.length).toBe(0)
      expect(decoded.cursorX).toBe(0)
      expect(decoded.cursorY).toBe(1)
    })
  })

  describe('数值保真与颜色/样式路径', () => {
    it('覆盖全部 8 个独立的样式装饰位与组合', () => {
      const flags = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80]
      const fixture = clone(resetFixture) as Record<string, unknown>
      const styles = flags.map((f) => [-1, -1, f])
      fixture.styles = styles
      fixture.cols = 8
      fixture.history = 0
      fixture.historyAppend = []
      fixture.screenRows = [
        {
          row: 0,
          line: [100, false, flags.map((_, i) => [0, 65 + i, i])],
        },
        {
          row: 1,
          line: [101, false, flags.map((_, i) => [0, 65 + i, i])],
        },
      ]
      const decoded = decodeTerminalViewUpdate(fixture)
      const slots = decoded.screenRows[0].line.slots
      expect(slots[0].style.bold).toBe(true)
      expect(slots[0].style.dim).toBe(false)
      expect(slots[1].style.dim).toBe(true)
      expect(slots[2].style.italic).toBe(true)
      expect(slots[3].style.underline).toBe(true)
      expect(slots[4].style.blink).toBe(true)
      expect(slots[5].style.inverse).toBe(true)
      expect(slots[6].style.hidden).toBe(true)
      expect(slots[7].style.strikethrough).toBe(true)
    })

    it('覆盖 256 色索引下界 index 0 与上界 index 255', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      // -2 为 index 0, -257 为 index 255
      fixture.styles = [
        [-2, -1, 0],
        [-257, -1, 0],
      ]
      fixture.cols = 5
      fixture.history = 0
      fixture.historyAppend = []
      fixture.screenRows = [
        {
          row: 0,
          line: [100, false, [[0, 65, 0], [0, 66, 1], [1, 0, 0], [1, 0, 0], [1, 0, 0]]],
        },
        {
          row: 1,
          line: [101, false, [[1, 0, 0], [1, 0, 0], [1, 0, 0], [1, 0, 0], [1, 0, 0]]],
        },
      ]
      const decoded = decodeTerminalViewUpdate(fixture)
      expect(decoded.screenRows[0].line.slots[0].style.fg).toEqual({
        kind: 'indexed',
        index: 0,
      })
      expect(decoded.screenRows[0].line.slots[1].style.fg).toEqual({
        kind: 'indexed',
        index: 255,
      })
    })

    it('覆盖真彩 RGB 极值（纯黑 0 与纯白 16777215）', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      fixture.styles = [
        [0, -1, 0],
        [16777215, -1, 0],
      ]
      fixture.cols = 5
      fixture.history = 0
      fixture.historyAppend = []
      fixture.screenRows = [
        {
          row: 0,
          line: [100, false, [[0, 65, 0], [0, 66, 1], [1, 0, 0], [1, 0, 0], [1, 0, 0]]],
        },
        {
          row: 1,
          line: [101, false, [[1, 0, 0], [1, 0, 0], [1, 0, 0], [1, 0, 0], [1, 0, 0]]],
        },
      ]
      const decoded = decodeTerminalViewUpdate(fixture)
      expect(decoded.screenRows[0].line.slots[0].style.fg).toEqual({
        kind: 'rgb',
        r: 0,
        g: 0,
        b: 0,
      })
      expect(decoded.screenRows[0].line.slots[1].style.fg).toEqual({
        kind: 'rgb',
        r: 255,
        g: 255,
        b: 255,
      })
    })

    it('同一样式引用必须复用完全相同的已冻结对象实例', () => {
      const decoded = decodeTerminalViewUpdate(resetFixture)
      const line1 = decoded.historyAppend[0]
      const line2 = decoded.screenRows[0].line
      // historyAppend[0] slot 0 与 screenRows[0] slot 3 均使用 style 0
      expect(line1.slots[0].style).toBe(line2.slots[3].style)
      expect(line1.slots[2].style).toBe(line2.slots[0].style)
    })

    it('所有 6 种 CursorShape 枚举均可正确解码', () => {
      const shapes: CursorShape[] = [
        'BLINK_BLOCK',
        'STEADY_BLOCK',
        'BLINK_UNDERLINE',
        'STEADY_UNDERLINE',
        'BLINK_VERTICAL_BAR',
        'STEADY_VERTICAL_BAR',
      ]
      for (const shape of shapes) {
        const fixture = clone(resetFixture) as Record<string, unknown>
        ;(fixture.cursor as Record<string, unknown>).shape = shape
        const decoded = decodeTerminalViewUpdate(fixture)
        expect(decoded.cursorShape).toBe(shape)
      }
    })

    it('所有 MouseMode 与 MouseFormat 枚举均可正确解码', () => {
      const modes: MouseMode[] = [
        'NONE',
        'NORMAL',
        'HILITE',
        'BUTTON_MOTION',
        'ALL_MOTION',
        'FOCUS',
      ]
      const formats: MouseFormat[] = ['XTERM_EXT', 'URXVT', 'SGR', 'XTERM']
      for (const mouseMode of modes) {
        const fixture = clone(resetFixture) as Record<string, unknown>
        ;(fixture.inputModes as Record<string, unknown>).mouseMode = mouseMode
        const decoded = decodeTerminalViewUpdate(fixture)
        expect(decoded.inputModes.mouseMode).toBe(mouseMode)
      }
      for (const mouseFormat of formats) {
        const fixture = clone(resetFixture) as Record<string, unknown>
        ;(fixture.inputModes as Record<string, unknown>).mouseFormat = mouseFormat
        const decoded = decodeTerminalViewUpdate(fixture)
        expect(decoded.inputModes.mouseFormat).toBe(mouseFormat)
      }
    })

    it('支持最大安全整数 MAX_SAFE_INTEGER（版本、行 ID、inputModeRevision）', () => {
      const maxSafe = Number.MAX_SAFE_INTEGER
      const fixture = clone(resetFixture) as Record<string, unknown>
      fixture.version = maxSafe
      fixture.inputModeRevision = maxSafe
      const historyLine = (fixture.historyAppend as unknown[][])[0]
      historyLine[0] = maxSafe
      const screenRow0 = (fixture.screenRows as Array<{ row: number; line: unknown[] }>)[0]
      screenRow0.line[0] = maxSafe - 1
      const screenRow1 = (fixture.screenRows as Array<{ row: number; line: unknown[] }>)[1]
      screenRow1.line[0] = maxSafe - 2

      const decoded = decodeTerminalViewUpdate(fixture)
      expect(decoded.version).toBe(maxSafe)
      expect(decoded.inputModeRevision).toBe(maxSafe)
      expect(decoded.historyAppend[0].id).toBe(maxSafe)
      expect(decoded.screenRows[0].line.id).toBe(maxSafe - 1)
      expect(decoded.screenRows[1].line.id).toBe(maxSafe - 2)
    })
  })

  describe('解码负向校验与固定错误信息', () => {
    it('顶层输入不是非空对象时拒绝', () => {
      const invalids = [null, undefined, 123, 'str', true, []]
      for (const val of invalids) {
        expect(() => decodeTerminalViewUpdate(val)).toThrowError(
          new TerminalViewError(TERMINAL_VIEW_INVALID_MESSAGE),
        )
      }
    })

    it('顶层存在未知字段时拒绝', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      fixture.extra = 'unknown'
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
    })

    it('顶层缺失任何必需字段时拒绝', () => {
      const fields = [
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
      ]
      for (const f of fields) {
        const fixture = clone(resetFixture) as Record<string, unknown>
        delete fixture[f]
        expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
      }
    })

    it('type 不是 RESET 或 PATCH 时拒绝', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      fixture.type = 'INVALID'
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
    })

    it('非规范 lowercase UUID 被拒绝', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      // 大写
      fixture.terminalId = '11111111-1111-1111-1111-11111111111A'
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
      // 非法字符
      fixture.terminalId = '11111111-1111-1111-1111-11111111111g'
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
      // 缺少连字符
      fixture.terminalId = '111111111111111111111111111111111111'
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
    })

    it('RESET 声明了 baseVersion 或 historyTrim != 0 时拒绝', () => {
      const f1 = clone(resetFixture) as Record<string, unknown>
      f1.baseVersion = 1
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      const f2 = clone(resetFixture) as Record<string, unknown>
      f2.historyTrim = 1
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)
    })

    it('PATCH 缺少正数 baseVersion 或 version <= baseVersion 时拒绝', () => {
      const f1 = clone(patchFixture) as Record<string, unknown>
      f1.baseVersion = null
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      const f2 = clone(patchFixture) as Record<string, unknown>
      f2.baseVersion = 8
      f2.version = 8
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)
    })

    it('尺寸/历史超出固定预算常量时拒绝', () => {
      const f1 = clone(resetFixture) as Record<string, unknown>
      f1.cols = 4 // MIN_COLUMNS = 5
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      const f2 = clone(resetFixture) as Record<string, unknown>
      f2.cols = 301 // MAX_COLUMNS = 300
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)

      const f3 = clone(resetFixture) as Record<string, unknown>
      f3.rows = 1 // MIN_ROWS = 2
      expect(() => decodeTerminalViewUpdate(f3)).toThrow(TerminalViewError)

      const f4 = clone(resetFixture) as Record<string, unknown>
      f4.rows = 101 // MAX_ROWS = 100
      expect(() => decodeTerminalViewUpdate(f4)).toThrow(TerminalViewError)

      const f5 = clone(resetFixture) as Record<string, unknown>
      f5.history = 513 // MAX_HISTORY_LINES = 512
      expect(() => decodeTerminalViewUpdate(f5)).toThrow(TerminalViewError)
    })

    it('cursor 坐标越界或未知字段时拒绝', () => {
      const f1 = clone(resetFixture) as Record<string, unknown>
      ;(f1.cursor as Record<string, unknown>).x = 7 // cols=6, x 最大为 6 (pending wrap)
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      const f2 = clone(resetFixture) as Record<string, unknown>
      ;(f2.cursor as Record<string, unknown>).y = 2 // rows=2, y 最大为 1
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)

      const f3 = clone(resetFixture) as Record<string, unknown>
      ;(f3.cursor as Record<string, unknown>).shape = 'UNKNOWN'
      expect(() => decodeTerminalViewUpdate(f3)).toThrow(TerminalViewError)

      const f4 = clone(resetFixture) as Record<string, unknown>
      ;(f4.cursor as Record<string, unknown>).extra = true
      expect(() => decodeTerminalViewUpdate(f4)).toThrow(TerminalViewError)
    })

    it('inputModes 字段未知或类型非法时拒绝', () => {
      const f1 = clone(resetFixture) as Record<string, unknown>
      ;(f1.inputModes as Record<string, unknown>).extra = 1
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      const f2 = clone(resetFixture) as Record<string, unknown>
      ;(f2.inputModes as Record<string, unknown>).mouseMode = 'INVALID'
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)
    })

    it('alternate 屏携带历史或追加时拒绝', () => {
      const f1 = clone(resetFixture) as Record<string, unknown>
      f1.alternate = true
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)
    })

    it('超出槽总量的 styles 字典在展开前即被拒绝（预算防护）', () => {
      const f1 = clone(patchMetadataFixture) as Record<string, unknown>
      // patch-metadata 无任何槽数据，slotBudget 为 0，styles 非空必须立刻被拦截
      f1.styles = [[-1, -1, 0]]
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)
    })

    it('styles 字典未引用（冗余）或未按 Canonical First-Seen 顺序时拒绝', () => {
      // 存在未引用的冗余样式
      const f1 = clone(resetFixture) as Record<string, unknown>
      ;(f1.styles as unknown[]).push([-1, -1, 0])
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      // 字典内存在重复声明且槽引用了后序条目
      const f2 = clone(resetFixture) as Record<string, unknown>
      // 将原样式 0 的定义复制一份作为追加项
      const style0 = (f2.styles as unknown[][])[0]
      ;(f2.styles as unknown[]).push([...style0])
      // 修改某槽使用追加的索引
      const hLine = (f2.historyAppend as unknown[][])[0]
      const slots = hLine[2] as unknown[][]
      slots[0] = [0, 65, (f2.styles as unknown[]).length - 1]
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)
    })

    it('槽编码规则违例时拒绝：EMPTY code != 0 或 DWC code != 0xe000', () => {
      const f1 = clone(resetFixture) as Record<string, unknown>
      const hLine = (f1.historyAppend as unknown[][])[0]
      const slots = hLine[2] as unknown[][]
      // EMPTY 但 code 不为 0
      slots[5] = [1, 32, 0]
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      const f2 = clone(resetFixture) as Record<string, unknown>
      const hLine2 = (f2.historyAppend as unknown[][])[0]
      const slots2 = hLine2[2] as unknown[][]
      // DWC 但 code 不为 0xe000
      slots2[4] = [2, 0xe001, 3]
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)
    })

    it('消息内行 ID 重复时拒绝', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      // 使 screenRows[0] 与 screenRows[1] 使用相同行 ID
      const screenRows = fixture.screenRows as Array<{ row: number; line: unknown[] }>
      screenRows[1].line[0] = screenRows[0].line[0]
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
    })

    it('screenRows 替换重复行号时拒绝', () => {
      const fixture = clone(resetFixture) as Record<string, unknown>
      const screenRows = fixture.screenRows as Array<{ row: number; line: unknown[] }>
      screenRows[1].row = 0
      expect(() => decodeTerminalViewUpdate(fixture)).toThrow(TerminalViewError)
    })

    it('详细边界与格式防御校验', () => {
      // 字段总数相同但含有未知字段替换
      const f1 = clone(resetFixture) as Record<string, unknown>
      delete f1.streamId
      f1.unknownStreamId = '22222222-2222-2222-2222-222222222222'
      expect(() => decodeTerminalViewUpdate(f1)).toThrow(TerminalViewError)

      // streamId 非法
      const f2 = clone(resetFixture) as Record<string, unknown>
      f2.streamId = 'invalid-uuid'
      expect(() => decodeTerminalViewUpdate(f2)).toThrow(TerminalViewError)

      // 颜色数值在有效范围外
      const f3 = clone(resetFixture) as Record<string, unknown>
      f3.styles = [[-258, -1, 0]]
      expect(() => decodeTerminalViewUpdate(f3)).toThrow(TerminalViewError)

      const f3b = clone(resetFixture) as Record<string, unknown>
      f3b.styles = [[16777216, -1, 0]]
      expect(() => decodeTerminalViewUpdate(f3b)).toThrow(TerminalViewError)

      // cursor 不是对象或字段类型错误
      const f4 = clone(resetFixture) as Record<string, unknown>
      f4.cursor = 'not-obj'
      expect(() => decodeTerminalViewUpdate(f4)).toThrow(TerminalViewError)

      const f4b = clone(resetFixture) as Record<string, unknown>
      ;(f4b.cursor as Record<string, unknown>).visible = 'yes'
      expect(() => decodeTerminalViewUpdate(f4b)).toThrow(TerminalViewError)

      // inputModes 不是对象或布尔字段非布尔
      const f5 = clone(resetFixture) as Record<string, unknown>
      f5.inputModes = 'not-obj'
      expect(() => decodeTerminalViewUpdate(f5)).toThrow(TerminalViewError)

      const f5b = clone(resetFixture) as Record<string, unknown>
      ;(f5b.inputModes as Record<string, unknown>).applicationCursor = 'true'
      expect(() => decodeTerminalViewUpdate(f5b)).toThrow(TerminalViewError)

      const f5c = clone(resetFixture) as Record<string, unknown>
      ;(f5c.inputModes as Record<string, unknown>).mouseFormat = 'UNKNOWN'
      expect(() => decodeTerminalViewUpdate(f5c)).toThrow(TerminalViewError)

      // historyTrim 非整数
      const f6 = clone(resetFixture) as Record<string, unknown>
      f6.historyTrim = '0'
      expect(() => decodeTerminalViewUpdate(f6)).toThrow(TerminalViewError)

      // styles 格式错误或元素非法
      const f7 = clone(resetFixture) as Record<string, unknown>
      f7.styles = ['not-array']
      expect(() => decodeTerminalViewUpdate(f7)).toThrow(TerminalViewError)

      const f7b = clone(resetFixture) as Record<string, unknown>
      f7b.styles = [[-1, -1]]
      expect(() => decodeTerminalViewUpdate(f7b)).toThrow(TerminalViewError)

      const f7c = clone(resetFixture) as Record<string, unknown>
      f7c.styles = [[-1, -1, 256]]
      expect(() => decodeTerminalViewUpdate(f7c)).toThrow(TerminalViewError)

      // line 格式非法：非数组、ID非法、wrapped非布尔、slots长度不符
      const f8 = clone(resetFixture) as Record<string, unknown>
      ;(f8.historyAppend as unknown[])[0] = 'not-line'
      expect(() => decodeTerminalViewUpdate(f8)).toThrow(TerminalViewError)

      const f8b = clone(resetFixture) as Record<string, unknown>
      ;((f8b.historyAppend as unknown[][])[0])[0] = 0 // id < 1
      expect(() => decodeTerminalViewUpdate(f8b)).toThrow(TerminalViewError)

      const f8c = clone(resetFixture) as Record<string, unknown>
      ;((f8c.historyAppend as unknown[][])[0])[1] = 'not-bool'
      expect(() => decodeTerminalViewUpdate(f8c)).toThrow(TerminalViewError)

      const f8d = clone(resetFixture) as Record<string, unknown>
      ;((f8d.historyAppend as unknown[][])[0])[2] = [[0, 65, 0]] // slots 长度不足
      expect(() => decodeTerminalViewUpdate(f8d)).toThrow(TerminalViewError)

      // slot 格式非法：非数组、kind非法、code非法、styleIndex越界
      const f9 = clone(resetFixture) as Record<string, unknown>
      ;((f9.historyAppend as unknown[][])[0])[2] = [
        'not-slot',
        [0, 65, 0],
        [0, 65, 0],
        [0, 65, 0],
        [0, 65, 0],
        [0, 65, 0],
      ]
      expect(() => decodeTerminalViewUpdate(f9)).toThrow(TerminalViewError)

      const f9b = clone(resetFixture) as Record<string, unknown>
      const slots9b = ((f9b.historyAppend as unknown[][])[0])[2] as unknown[][]
      slots9b[0] = [3, 65, 0] // kind 3 非法
      expect(() => decodeTerminalViewUpdate(f9b)).toThrow(TerminalViewError)

      const f9c = clone(resetFixture) as Record<string, unknown>
      const slots9c = ((f9c.historyAppend as unknown[][])[0])[2] as unknown[][]
      slots9c[0] = [0, 65536, 0] // code > 65535
      expect(() => decodeTerminalViewUpdate(f9c)).toThrow(TerminalViewError)

      const f9d = clone(resetFixture) as Record<string, unknown>
      const slots9d = ((f9d.historyAppend as unknown[][])[0])[2] as unknown[][]
      slots9d[0] = [0, 65, 999] // styleIndex 越界
      expect(() => decodeTerminalViewUpdate(f9d)).toThrow(TerminalViewError)

      // screenRows item 格式非法：非对象、row非法
      const f10 = clone(resetFixture) as Record<string, unknown>
      ;(f10.screenRows as unknown[])[0] = 'not-item'
      expect(() => decodeTerminalViewUpdate(f10)).toThrow(TerminalViewError)

      const f10b = clone(resetFixture) as Record<string, unknown>
      ;((f10b.screenRows as Array<Record<string, unknown>>)[0]).row = 99
      expect(() => decodeTerminalViewUpdate(f10b)).toThrow(TerminalViewError)
    })
  })
})
