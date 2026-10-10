/**
 * TerminalViewport 行为测试（jsdom）。
 *
 * 覆盖：DOM commit 后按当前 stream/version 只 ACK 一次、滚动重绘同版本不重复 ACK、
 * 旧 stream 晚绘作废、跟随底部与历史锚定、按活动屏行换算的 VT 鼠标坐标、
 * 输入编码（printable/IME 最终 input 去重/特殊键/Ctrl+V 默认粘贴/Ctrl+C 原生复制）、
 * encoder 拒绝的本地状态、无 view 时不编码发送、以及卸载取消未执行绘制。
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TerminalController, TerminalSessionSnapshot } from './terminal-controller'
import type { TerminalMirrorState } from './terminal-view-mirror'
import type { TerminalInputModes } from './terminal-view-codec'
import { TerminalViewport } from './TerminalViewport'
import { gridFromCapturedView } from './__fixtures__/captured-view'

const ENV = 'aaaaaaaa-0000-4000-8000-000000000001'
const STREAM_A = 'bbbbbbbb-0000-4000-8000-000000000001'
const STREAM_B = 'bbbbbbbb-0000-4000-8000-000000000002'

const BASE_MODES: TerminalInputModes = {
  applicationCursor: false,
  applicationKeypad: false,
  bracketedPaste: false,
  autoNewLine: false,
  altSendsEscape: true,
  mouseMode: 'NONE',
  mouseFormat: 'XTERM',
}

interface MirrorOptions {
  history?: number
  rows?: number
  cols?: number
  texts?: string[]
  ids?: number[]
  modes?: TerminalInputModes
  streamId?: string
  version?: number
}

function mirror(options: MirrorOptions = {}): TerminalMirrorState {
  const cols = options.cols ?? 4
  const texts = options.texts ?? ['ABC', 'DEF']
  const grid = gridFromCapturedView({
    cols,
    rows: options.rows ?? texts.length,
    cursorX: 0,
    cursorY: 0,
    alternate: false,
    history: options.history ?? 0,
    lines: texts.map((text) => ({ wrapped: false, text })),
  })
  return {
    ...grid,
    terminalId: '11111111-1111-4111-8111-111111111111',
    streamId: options.streamId ?? STREAM_A,
    version: options.version ?? 1,
    cursorVisible: true,
    cursorShape: null,
    inputModeRevision: 0,
    inputModes: options.modes ?? BASE_MODES,
    lines: grid.lines.map((line, index) => ({ ...line, id: options.ids?.[index] ?? index })),
  }
}

function snapshot(overrides: Partial<TerminalSessionSnapshot> = {}): TerminalSessionSnapshot {
  return {
    environmentId: ENV,
    identity: null,
    streamId: null,
    executable: null,
    status: null,
    exitCode: null,
    view: null,
    viewApplied: false,
    writer: null,
    hasControl: false,
    pending: false,
    notice: null,
    ...overrides,
  }
}

interface FakeController {
  applied: ReturnType<typeof vi.fn>
  resize: ReturnType<typeof vi.fn>
  sendInput: ReturnType<typeof vi.fn>
}

function fakeController(resizeResult = true): FakeController {
  return { applied: vi.fn(), resize: vi.fn(() => resizeResult), sendInput: vi.fn(() => true) }
}

const asController = (controller: FakeController) => controller as unknown as TerminalController

function installImmediateRaf() {
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    callback(0)
    return 0
  })
  vi.stubGlobal('cancelAnimationFrame', () => undefined)
}

function installRafQueue() {
  const queue = new Map<number, FrameRequestCallback>()
  const cancelled: number[] = []
  let next = 1
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    const id = next
    next += 1
    queue.set(id, callback)
    return id
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => {
    cancelled.push(id)
    queue.delete(id)
  })
  return {
    cancelled,
    flush() {
      const entries = [...queue.entries()]
      queue.clear()
      for (const [, callback] of entries) {
        callback(0)
      }
    },
    pending() {
      return queue.size
    },
  }
}

const textarea = () => document.querySelector('.terminal-viewport__input') as HTMLTextAreaElement
const scrollElement = () => document.querySelector('.terminal-viewport__scroll') as HTMLElement
const gridRoot = () => document.querySelector('.terminal-grid') as HTMLElement
const nextTick = () => new Promise((resolve) => setTimeout(resolve, 45))

/** 覆盖 jsdom 全局几何，模拟真实滚动容器尺寸。 */
function setViewport(width: number, height: number): void {
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => width })
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => height })
}

const bytesOf = (call: unknown): string =>
  String.fromCharCode(...(call as Uint8Array))

const textOf = (call: unknown): string => new TextDecoder().decode(call as Uint8Array)

describe('TerminalViewport', () => {
  beforeEach(() => {
    installImmediateRaf()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    setViewport(1200, 800)
  })

  it('commits the grid then ACKs the current stream/version exactly once', () => {
    const raf = installRafQueue()
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), viewApplied: false })
    render(<TerminalViewport session={session} controller={asController(controller)} />)
    raf.flush()

    expect(document.querySelector('.terminal-grid')).not.toBeNull()
    expect(controller.applied).toHaveBeenCalledTimes(1)
    expect(controller.applied).toHaveBeenCalledWith(STREAM_A, 1)
  })

  it('does not re-ACK when scrolling redraws the same version', async () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror({ history: 3, rows: 2, texts: ['1', '2', '3', '4', '5'] }) })
    render(<TerminalViewport session={session} controller={asController(controller)} />)
    expect(controller.applied).toHaveBeenCalledTimes(1)

    fireEvent.scroll(scrollElement())
    await nextTick()

    expect(controller.applied).toHaveBeenCalledTimes(1)
  })

  it('never ACKs a stale stream drawn after the session switched', async () => {
    const raf = installRafQueue()
    const controller = fakeController()
    const first = snapshot({ streamId: STREAM_A, view: mirror({ streamId: STREAM_A, version: 1 }) })
    const second = snapshot({ streamId: STREAM_B, view: mirror({ streamId: STREAM_B, version: 3 }) })

    const { rerender } = render(<TerminalViewport session={first} controller={asController(controller)} />)
    rerender(<TerminalViewport session={second} controller={asController(controller)} />)
    raf.flush()
    expect(controller.applied).not.toHaveBeenCalled()

    await nextTick()
    raf.flush()

    expect(controller.applied).toHaveBeenCalledTimes(1)
    expect(controller.applied).toHaveBeenCalledWith(STREAM_B, 3)
  })

  it('cancels a pending frame on unmount', () => {
    const raf = installRafQueue()
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror() })
    const { unmount } = render(<TerminalViewport session={session} controller={asController(controller)} />)
    expect(raf.pending()).toBeGreaterThan(0)
    unmount()

    expect(raf.cancelled.length).toBeGreaterThan(0)
    raf.flush()
    expect(controller.applied).not.toHaveBeenCalled()
  })

  it('follows the bottom by default so the prompt/cursor line is visible', () => {
    setViewport(32, 36)
    const controller = fakeController()
    const texts = Array.from({ length: 52 }, (_, index) => `${index}`)
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ history: 50, rows: 2, cols: 4, texts }),
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    // 52 行 - 2 可见行 = 50 行历史；窗口应覆盖底部而不是最旧历史。
    expect(gridRoot().dataset.lineEnd).toBe('52')
    expect(document.querySelector('.terminal-grid__cursor')).not.toBeNull()
    expect(gridRoot().dataset.lineStart).not.toBe('0')
  })

  it('keeps the pinned history line across a trim instead of jumping', async () => {
    setViewport(32, 36)
    const controller = fakeController()
    const before = snapshot({
      streamId: STREAM_A,
      view: mirror({ history: 2, rows: 2, cols: 4, texts: ['a', 'b', 'c', 'd'], ids: [0, 1, 2, 3] }),
    })
    const { rerender } = render(<TerminalViewport session={before} controller={asController(controller)} />)

    // 用户上滚到第 1 行（绝对行号 1，行 id 1）：不再跟随底部。
    const scroll = scrollElement()
    scroll.scrollTop = 18
    fireEvent.scroll(scroll)
    await nextTick()
    expect(scroll.scrollTop).toBe(18)

    // 历史被裁剪掉最旧一行：行 id 1 现在位于索引 0，视图应锚定到该行。
    const after = snapshot({
      streamId: STREAM_A,
      view: mirror({ history: 1, rows: 2, cols: 4, texts: ['b', 'c', 'd'], ids: [1, 2, 3], version: 2 }),
    })
    rerender(<TerminalViewport session={after} controller={asController(controller)} />)
    await nextTick()

    expect(scroll.scrollTop).toBe(0)
  })

  it('sends printable input through onInput and clears the buffer', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    input.value = 'ls'
    fireEvent.input(input)

    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('ls')
    expect(input.value).toBe('')
  })

  it('sends IME output once and suppresses the trailing final input', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.compositionStart(input)
    input.value = '中'
    fireEvent.input(input)
    expect(controller.sendInput).not.toHaveBeenCalled()

    fireEvent.compositionEnd(input)
    expect(controller.sendInput).toHaveBeenCalledTimes(1)

    // 模拟 Chromium 在 compositionend 之后补发的最终 input（同值、isComposing:false）。
    input.value = '中'
    fireEvent.input(input)
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(input.value).toBe('')
  })

  it('commits consecutive identical compositions as separate inputs', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    for (let round = 0; round < 2; round += 1) {
      fireEvent.compositionStart(input)
      input.value = '中'
      fireEvent.input(input)
      fireEvent.compositionEnd(input)
      // 每一次组合都要跟一次「Chromium 补发的最终 input」。
      input.value = '中'
      fireEvent.input(input)
    }

    expect(controller.sendInput).toHaveBeenCalledTimes(2)
    expect(textOf(controller.sendInput.mock.calls[0]![0])).toBe('中')
    expect(textOf(controller.sendInput.mock.calls[1]![0])).toBe('中')
  })

  it('does not commit a cancelled composition and keeps the next identical input', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.compositionStart(input)
    input.value = '中'
    fireEvent.input(input)
    // 组合被取消：compositionend 携带空字符串。
    input.value = ''
    fireEvent.compositionEnd(input)
    expect(controller.sendInput).not.toHaveBeenCalled()

    // 随后真实输入的同一文本绝不能被当成补发 input 吞掉。
    input.value = '中'
    fireEvent.input(input)
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(textOf(controller.sendInput.mock.calls[0]![0])).toBe('中')
  })

  it('does not suppress a paste that follows a composition commit', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.compositionStart(input)
    input.value = '中'
    fireEvent.input(input)
    fireEvent.compositionEnd(input)
    expect(controller.sendInput).toHaveBeenCalledTimes(1)

    fireEvent.paste(input, { clipboardData: { getData: () => '中' } })
    expect(controller.sendInput).toHaveBeenCalledTimes(2)
    expect(textOf(controller.sendInput.mock.calls[1]![0])).toBe('中')
  })

  it('shows a local status instead of leaking an encoder rejection from keydown', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    // alt + lone surrogate 触发 encoder 的 TerminalInputError：必须本地提示，不抛给调用方。
    expect(() => fireEvent.keyDown(textarea(), { key: '\uD800', altKey: true })).not.toThrow()
    expect(controller.sendInput).not.toHaveBeenCalled()
    expect(screen.getByRole('status').textContent).toContain('未发送')
  })

  it('leaves default printable behaviour to the browser for unencoded keys', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    // 未编码的字符键不得被 preventDefault，随后由 onInput 编码。
    expect(fireEvent.keyDown(input, { key: 'a' })).toBe(true)
    expect(controller.sendInput).not.toHaveBeenCalled()

    input.value = 'a'
    fireEvent.input(input)
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('a')
  })

  it('ACKs a new stream only after its own view arrives', async () => {
    const controller = fakeController()
    const base = snapshot({
      streamId: STREAM_A,
      view: mirror({ streamId: STREAM_A, version: 1 }),
      hasControl: true,
      status: 'RUNNING',
    })
    const { rerender } = render(<TerminalViewport session={base} controller={asController(controller)} />)
    await nextTick()
    expect(controller.applied.mock.calls[0]).toEqual([STREAM_A, 1])

    // 同环境换 stream 但还没有新 RESET：旧屏可只读保留，但绝不能把它 ACK 成新 stream。
    rerender(<TerminalViewport session={{ ...base, streamId: STREAM_B }} controller={asController(controller)} />)
    await nextTick()
    expect(controller.applied).toHaveBeenCalledTimes(1)

    // 新 RESET 到达：只 ACK 新 stream/version。
    rerender(
      <TerminalViewport
        session={{ ...base, streamId: STREAM_B, view: mirror({ streamId: STREAM_B, version: 1 }) }}
        controller={asController(controller)}
      />,
    )
    await nextTick()
    expect(controller.applied).toHaveBeenCalledTimes(2)
    expect(controller.applied.mock.calls[1]).toEqual([STREAM_B, 1])
  })

  it('routes special keys through the encoder without app-level side effects', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    fireEvent.keyDown(textarea(), { key: 'Enter' })
    fireEvent.keyDown(textarea(), { key: 'Escape' })

    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('\r')
    expect(bytesOf(controller.sendInput.mock.calls[1]![0])).toBe('\x1b')
  })

  it('sends Ctrl+C as 0x03 when there is no local selection', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    fireEvent.keyDown(textarea(), { key: 'c', ctrlKey: true })

    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('\x03')
  })

  it('copies the local selection through the native copy path without navigator.clipboard', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror({ cols: 4, texts: ['ABC', 'DEF'] }) })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 0 })
    fireEvent.mouseMove(input, { clientX: 16, clientY: 0, button: 0, buttons: 1 })
    fireEvent.keyDown(input, { key: 'c', ctrlKey: true })

    // 原生复制路径：镜像文本进入 textarea 并被选中，不改用 navigator.clipboard。
    expect(input.value).toBe('AB')
    expect(controller.sendInput).not.toHaveBeenCalled()

    const setData = vi.fn()
    fireEvent.copy(input, { clipboardData: { setData } })

    expect(setData).toHaveBeenCalledWith('text/plain', 'AB')
    expect(input.value).toBe('')
  })

  it('leaves Ctrl+V to the browser paste event instead of encoding 0x16', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.keyDown(input, { key: 'v', ctrlKey: true })
    expect(controller.sendInput).not.toHaveBeenCalled()

    fireEvent.paste(input, { clipboardData: { getData: () => 'hi' } })
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('hi')
  })

  it('maps VT mouse coordinates to the active screen rows only', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({
        history: 2,
        rows: 2,
        cols: 4,
        texts: ['h0', 'h1', 's0', 's1'],
        modes: { ...BASE_MODES, mouseMode: 'NORMAL', mouseFormat: 'SGR' },
      }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    // 点击绝对行 3（= 活动屏第 2 行），槽 1 -> 1-based (2,2)。
    fireEvent.mouseDown(textarea(), { clientX: 8, clientY: 54, button: 0 })

    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('\x1b[<0;2;2M')
  })

  it('does not report history-area mouse clicks to the PTY', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({
        history: 2,
        rows: 2,
        cols: 4,
        texts: ['h0', 'h1', 's0', 's1'],
        modes: { ...BASE_MODES, mouseMode: 'NORMAL', mouseFormat: 'SGR' },
      }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    fireEvent.mouseDown(textarea(), { clientX: 0, clientY: 0, button: 0 })

    expect(controller.sendInput).not.toHaveBeenCalled()
  })

  it('uses the buttons mask for drag reporting so right/middle drags are correct', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ modes: { ...BASE_MODES, mouseMode: 'BUTTON_MOTION', mouseFormat: 'SGR' } }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    fireEvent.mouseMove(textarea(), { clientX: 0, clientY: 0, buttons: 2 })

    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    // 右键按下时 SGR button=2+32=34（移动）。
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toContain('\x1b[<34;')
  })

  it('Shift bypasses VT mouse reporting for local selection', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ modes: { ...BASE_MODES, mouseMode: 'NORMAL', mouseFormat: 'SGR' } }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 0, shiftKey: true })
    fireEvent.mouseMove(input, { clientX: 16, clientY: 0, button: 0, buttons: 1, shiftKey: true })

    expect(controller.sendInput).not.toHaveBeenCalled()
  })

  it('forwards in-screen wheel to the PTY and scrolls locally in history', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ history: 4, rows: 2, cols: 4, texts: ['0', '1', '2', '3', '4', '5'], modes: { ...BASE_MODES, mouseMode: 'NORMAL', mouseFormat: 'SGR' } }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    // 活动屏区域：上报 VT wheel。
    fireEvent.wheel(textarea(), { clientX: 0, clientY: 90, deltaY: -100 })
    expect(controller.sendInput).toHaveBeenCalledTimes(1)

    // 历史区域：本地滚动，不上报。
    const scroll = scrollElement()
    const before = scroll.scrollTop
    fireEvent.wheel(textarea(), { clientX: 0, clientY: 0, deltaY: 100 })
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(scroll.scrollTop).toBeGreaterThan(before)
  })

  it('scrolls horizontally on Shift+wheel', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror({ history: 2, rows: 2, cols: 4, texts: ['a', 'b', 'c', 'd'] }) })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const scroll = scrollElement()
    fireEvent.wheel(textarea(), { deltaX: 40, deltaY: 0, shiftKey: true })

    expect(scroll.scrollLeft).toBe(40)
  })

  it('shows a local status when the encoder rejects an oversized input', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    input.value = 'a'.repeat(70000)
    fireEvent.input(input)

    expect(controller.sendInput).not.toHaveBeenCalled()
    expect(screen.getByRole('status').textContent).toContain('未发送')
  })

  it('does not encode or send when there is no authoritative view', () => {
    const controller = fakeController()
    render(<TerminalViewport session={snapshot()} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.keyDown(input, { key: 'Enter' })
    input.value = 'x'
    fireEvent.input(input)

    expect(controller.sendInput).not.toHaveBeenCalled()
    expect(document.querySelector('.terminal-viewport')?.getAttribute('data-readonly')).toBe('true')
  })

  it('does not propose a fabricated size when layout is unavailable', () => {
    setViewport(0, 0)
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror(),
      hasControl: true,
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
      writer: null,
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    // clientWidth/Height 为 0：绝不提出 5x2 假尺寸。
    expect(controller.resize).not.toHaveBeenCalled()
  })

  it('proposes a resize once when control is acquired and layout is available', () => {
    setViewport(80, 90)
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror(),
      hasControl: true,
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
      writer: { writerEpoch: 'e1', lastWrittenSeq: 0, lastWrittenDigest: null, lastResolvedSeq: 0, lastResolvedDigest: null, lastResolvedOutcome: null, pendingSeq: 0, pendingDigest: null, frozen: false },
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    expect(controller.resize).toHaveBeenCalledWith(10, 5)
  })

  it('keeps textarea focus across a same-stream PATCH', () => {
    const controller = fakeController()
    const base = snapshot({ streamId: STREAM_A, view: mirror({ version: 1 }), hasControl: true, status: 'RUNNING' })
    const { rerender } = render(<TerminalViewport session={base} controller={asController(controller)} />)
    const input = textarea()
    input.focus()
    expect(document.activeElement).toBe(input)

    rerender(<TerminalViewport session={{ ...base, view: mirror({ version: 2 }) }} controller={asController(controller)} />)
    expect(document.activeElement).toBe(input)
  })

  it('marks the viewport read-only when the session is offline', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), status: 'EXITED' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    expect(document.querySelector('.terminal-viewport')?.getAttribute('data-readonly')).toBe('true')
    expect(textarea().readOnly).toBe(true)
  })

  it('selects locally with the mouse when VT reporting is off and draws the highlight', async () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror({ cols: 4, texts: ['ABC', 'DEF'] }) })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 0 })
    fireEvent.mouseMove(input, { clientX: 16, clientY: 0, buttons: 1 })
    await nextTick()

    // 本地选择只画高亮，不向 PTY 发送任何字节。
    expect(document.querySelector('.terminal-grid__selection')).not.toBeNull()
    expect(controller.sendInput).not.toHaveBeenCalled()

    fireEvent.mouseUp(input, { clientX: 16, clientY: 0, button: 0 })
    expect(controller.sendInput).not.toHaveBeenCalled()
  })

  it('encodes focus and blur when the terminal requests focus reporting', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ modes: { ...BASE_MODES, mouseMode: 'FOCUS' } }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.focusIn(input)
    fireEvent.focusOut(input)

    expect(controller.sendInput).toHaveBeenCalledTimes(2)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('\x1b[I')
    expect(bytesOf(controller.sendInput.mock.calls[1]![0])).toBe('\x1b[O')
  })

  it('encodes pointer press/release/move for every button', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ modes: { ...BASE_MODES, mouseMode: 'BUTTON_MOTION', mouseFormat: 'SGR' } }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 1 })
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 2 })
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 0 })
    fireEvent.mouseUp(input, { clientX: 0, clientY: 0, button: 0 })
    fireEvent.mouseMove(input, { clientX: 0, clientY: 0, buttons: 4 })
    fireEvent.mouseMove(input, { clientX: 0, clientY: 0, buttons: 1 })

    // 中键/右键/左键按下 + 释放 + 不同 buttons 掩码的移动，全部走 VT 上报。
    expect(controller.sendInput).toHaveBeenCalledTimes(6)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toContain('\x1b[<1;')
    expect(bytesOf(controller.sendInput.mock.calls[1]![0])).toContain('\x1b[<2;')
    // move 掩码 buttons=4 映射中键，SGR 移动态为 1+32=33。
    expect(bytesOf(controller.sendInput.mock.calls[4]![0])).toContain('\x1b[<33;')
  })

  it('clears a pending native copy buffer on the next real keydown', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ cols: 4, texts: ['ABC', 'DEF'] }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 0 })
    fireEvent.mouseMove(input, { clientX: 16, clientY: 0, buttons: 1 })
    fireEvent.keyDown(input, { key: 'c', ctrlKey: true })
    // 本地复制把镜像文本放进 textarea，等待原生 copy 事件。
    expect(input.value).toBe('AB')

    // 浏览器没有派发 copy：下一次真实按键必须清掉缓冲区，且不得混入输入。
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(input.value).toBe('')
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('\r')
  })

  it('ignores keydown while composing and empty or viewless paste', () => {
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    const { rerender } = render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.keyDown(input, { key: 'a', keyCode: 229 })
    fireEvent.paste(input, { clipboardData: { getData: () => '' } })
    expect(controller.sendInput).not.toHaveBeenCalled()

    // 无权威 view 时粘贴也不编码、不发送。
    rerender(<TerminalViewport session={snapshot()} controller={asController(controller)} />)
    fireEvent.paste(textarea(), { clipboardData: { getData: () => 'hi' } })
    expect(controller.sendInput).not.toHaveBeenCalled()
  })

  it('keeps the mirrored size when it already matches the measured layout', () => {
    setViewport(80, 90)
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ cols: 10, rows: 5, texts: ['a', 'b', 'c', 'd', 'e'] }),
      hasControl: true,
      status: 'RUNNING',
      identity: { daemonInstanceId: 'd', terminalId: 't' },
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    // 80/8=10 列、90/18=5 行，与镜像一致：记录尺寸但不重复提出。
    expect(controller.resize).not.toHaveBeenCalled()
  })

  it('skips fitting when ResizeObserver is unavailable', () => {
    vi.stubGlobal('ResizeObserver', undefined)
    const controller = fakeController()
    const session = snapshot({ streamId: STREAM_A, view: mirror(), hasControl: true, status: 'RUNNING' })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    expect(document.querySelector('.terminal-viewport')).not.toBeNull()
  })

  it('falls back to Ctrl+C 0x03 when the local selection copies to empty text', () => {
    const controller = fakeController()
    const session = snapshot({
      streamId: STREAM_A,
      view: mirror({ cols: 4, texts: ['', ''] }),
      hasControl: true,
      status: 'RUNNING',
    })
    render(<TerminalViewport session={session} controller={asController(controller)} />)

    const input = textarea()
    fireEvent.mouseDown(input, { clientX: 0, clientY: 0, button: 0 })
    fireEvent.mouseMove(input, { clientX: 16, clientY: 0, buttons: 1 })
    fireEvent.keyDown(input, { key: 'c', ctrlKey: true })

    // 选中内容为空：不产生本地复制，仍按终端语义发送 0x03。
    expect(controller.sendInput).toHaveBeenCalledTimes(1)
    expect(bytesOf(controller.sendInput.mock.calls[0]![0])).toBe('\x03')
  })
})
