/**
 * 全局终端只读视口 + 输入层。
 *
 * - 数值镜像由 `renderTerminalGrid` 逐 UTF-16 unit 绘制；可见行窗口虚拟化，容器仍按全部行计高；
 * - 绘制串行化并用 stream/version 代际守卫：只绘制与当前会话一致的流，旧帧作废；
 *   真实 DOM commit 后仅对当前 stream/version 发送一次 applied（滚动重绘同一版本不重复 ACK）；
 * - 默认跟随底部（prompt/cursor 可见）；用户上滚历史后不再强制跳回，历史 trim 按行身份锚定；
 * - 输入层是覆盖在网格上的透明 textarea：文字/IME 走 onInput/compositionend，特殊键/控件键走
 *   既有 encoder，粘贴走 bracketed encoder；Ctrl/Cmd+V 保留浏览器默认粘贴事件；
 * - Ctrl/Cmd+C 有本地槽选择时走唯一原生复制路径（镜像文本写入 textarea 选择 + onCopy），
 *   不依赖 secure-context 的 navigator.clipboard；
 * - 仅活动屏行（减去 history）参与 VT 鼠标上报，历史区与 Shift 走本地槽选择；
 * - ResizeObserver / hasControl 获得时显式测量，只在握有控制权且运行中时提出合法尺寸；
 * - 无权威镜像时不编码、不发送（简单拒绝），旧末屏仍可只读查看。
 */

import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ClipboardEvent,
  type CompositionEvent,
  type FormEvent,
  type KeyboardEvent,
  type MouseEvent,
} from 'react'
import { useI18n } from '@/shared/i18n'
import type { TerminalController, TerminalSessionSnapshot } from './terminal-controller'
import type { TerminalMirrorState } from './terminal-view-mirror'
import { renderTerminalGrid } from './terminal-grid-renderer'
import {
  copySelectedText,
  isSelectionEmpty,
  selectionRanges,
  type GridSelection,
} from './terminal-selection'
import {
  encodeTerminalFocus,
  encodeTerminalKey,
  encodeTerminalMouse,
  encodeTerminalPaste,
  encodeTerminalText,
  TerminalInputError,
  type TerminalMouseAction,
  type TerminalMouseButton,
  type TerminalWheelDirection,
} from './terminal-input-encoder'
import { MAX_COLUMNS, MAX_ROWS, MIN_COLUMNS, MIN_ROWS } from './terminal-view-codec'
import './terminal.css'

/** 固定单元格几何；与 Renderer 逐槽定位一致，窗口按此换算行列。 */
export const TERMINAL_CELL_WIDTH = 8
export const TERMINAL_CELL_HEIGHT = 18

/** 绘制节流：最多 30Hz。 */
const DRAW_MIN_INTERVAL_MS = 1000 / 30
/** 可见窗口上下额外预绘行数。 */
const OVERSCAN_ROWS = 4

/** 交给 encoder 之外仍需拦截浏览器默认行为的特殊键（非 composing）。 */
const BROWSER_DEFAULT_KEYS = new Set([
  'Escape',
  'Tab',
  'ArrowUp',
  'ArrowDown',
  'ArrowLeft',
  'ArrowRight',
  'Home',
  'End',
  'PageUp',
  'PageDown',
  'Insert',
  'Delete',
])

interface GridPoint {
  line: number
  x: number
}

interface StoredSelection {
  selection: GridSelection
  anchorId: number
  focusId: number
}

interface PinnedLine {
  lineId: number
  offset: number
}

export interface TerminalViewportProps {
  session: TerminalSessionSnapshot
  controller: TerminalController
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

function selectionValid(grid: TerminalMirrorState, stored: StoredSelection | null): boolean {
  if (stored === null) {
    return false
  }
  const anchor = grid.lines[stored.selection.anchor.line]
  const focus = grid.lines[stored.selection.focus.line]
  return anchor?.id === stored.anchorId && focus?.id === stored.focusId
}

function selectionHighlight(grid: TerminalMirrorState, stored: StoredSelection | null): HTMLElement[] {
  if (!selectionValid(grid, stored) || isSelectionEmpty(stored!.selection)) {
    return []
  }
  const elements: HTMLElement[] = []
  for (const range of selectionRanges(grid, stored!.selection)) {
    if (range.xTo <= range.xFrom) {
      continue
    }
    const element = document.createElement('div')
    element.className = 'terminal-grid__selection'
    element.style.left = `${range.xFrom * TERMINAL_CELL_WIDTH}px`
    element.style.top = `${range.line * TERMINAL_CELL_HEIGHT}px`
    element.style.width = `${(range.xTo - range.xFrom) * TERMINAL_CELL_WIDTH}px`
    element.style.height = `${TERMINAL_CELL_HEIGHT}px`
    elements.push(element)
  }
  return elements
}

export function TerminalViewport({ session, controller }: TerminalViewportProps) {
  const { t } = useI18n()
  const scrollRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const latestRef = useRef(session)
  const controllerRef = useRef(controller)
  const appliedRef = useRef<{ streamId: string; version: number } | null>(null)
  const selectionRef = useRef<StoredSelection | null>(null)
  const draggingRef = useRef(false)
  const composingRef = useRef(false)
  const suppressNextInputRef = useRef(false)
  const copyBufferRef = useRef(false)
  const pendingDrawRef = useRef(false)
  const frameRef = useRef<number | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastDrawAtRef = useRef(0)
  const targetRef = useRef<{ streamId: string | null; version: number | null }>({
    streamId: null,
    version: null,
  })
  const followRef = useRef(true)
  const pinnedRef = useRef<PinnedLine | null>(null)
  const streamRef = useRef<string | null>(null)
  const lastSizeRef = useRef<string | null>(null)
  const scheduleRef = useRef<() => void>(() => undefined)
  const wheelHandlerRef = useRef<(event: WheelEvent) => void>(() => undefined)
  const [inputRejected, setInputRejected] = useState(false)

  // DOM commit 与 RAF 绘制同代际：使用 layoutEffect 在浏览器绘制前更新权威 session 引用。
  useLayoutEffect(() => {
    latestRef.current = session
    controllerRef.current = controller
  })

  const modes = () => latestRef.current.view?.inputModes ?? null
  const canInput = (): boolean => {
    const current = latestRef.current
    return current.hasControl && current.status === 'RUNNING' && current.view !== null
  }
  const mouseReporting = (): boolean => {
    const current = modes()
    return current !== null && current.mouseMode !== 'NONE' && current.mouseMode !== 'FOCUS'
  }

  const gridPoint = useCallback((clientX: number, clientY: number): GridPoint => {
    const scroll = scrollRef.current
    if (scroll === null) {
      return { line: 0, x: 0 }
    }
    const rect = scroll.getBoundingClientRect()
    const line = Math.floor(
      (clientY - rect.top + scroll.scrollTop) / TERMINAL_CELL_HEIGHT,
    )
    const x = Math.floor((clientX - rect.left + scroll.scrollLeft) / TERMINAL_CELL_WIDTH)
    return { line: Math.max(0, line), x: Math.max(0, x) }
  }, [])

  /** 只把活动屏行换算为 1-based VT 坐标；历史区/越界返回 null。 */
  const reportPoint = useCallback((point: GridPoint): { x: number; y: number } | null => {
    const grid = latestRef.current.view
    if (grid === null) {
      return null
    }
    const y = point.line - grid.history + 1
    if (y < 1 || y > grid.rows) {
      return null
    }
    return { x: clamp(point.x + 1, 1, grid.cols), y }
  }, [])

  const draw = useCallback(() => {
    const scroll = scrollRef.current
    const latest = latestRef.current
    const grid = latest.view
    if (scroll === null || grid === null) {
      return
    }
    const target = targetRef.current
    if (target.streamId !== latest.streamId || target.version !== grid.version) {
      // 代际守卫：排队的旧帧不得绘制也不得 ACK，为最新状态重新排队。
      scheduleRef.current()
      return
    }
    if (streamRef.current !== latest.streamId) {
      // 切流：重置跟随底部与历史锚点。
      streamRef.current = latest.streamId
      followRef.current = true
      pinnedRef.current = null
      appliedRef.current = null
    }
    if (!selectionValid(grid, selectionRef.current)) {
      selectionRef.current = null
    }

    const total = grid.lines.length
    const viewportHeight = scroll.clientHeight > 0
      ? scroll.clientHeight
      : Math.max(grid.rows, 1) * TERMINAL_CELL_HEIGHT
    const maxScroll = Math.max(0, total * TERMINAL_CELL_HEIGHT - viewportHeight)
    let desiredTop = clamp(scroll.scrollTop, 0, maxScroll)
    if (followRef.current) {
      desiredTop = maxScroll
    } else if (pinnedRef.current !== null) {
      const index = grid.lines.findIndex((line) => line.id === pinnedRef.current!.lineId)
      if (index >= 0) {
        desiredTop = clamp(index * TERMINAL_CELL_HEIGHT + pinnedRef.current.offset, 0, maxScroll)
      } else {
        pinnedRef.current = null
      }
    }

    const first = Math.max(0, Math.floor(desiredTop / TERMINAL_CELL_HEIGHT) - OVERSCAN_ROWS)
    const lineStart = Math.min(first, Math.max(0, total - 1))
    const visibleRows = Math.ceil(viewportHeight / TERMINAL_CELL_HEIGHT) + OVERSCAN_ROWS * 2
    const lineEnd = Math.min(total, lineStart + visibleRows + 1)
    const root = renderTerminalGrid(grid, {
      cellWidth: TERMINAL_CELL_WIDTH,
      cellHeight: TERMINAL_CELL_HEIGHT,
      lineStart,
      lineEnd,
      showCursor: grid.cursorVisible,
    })
    for (const highlight of selectionHighlight(grid, selectionRef.current)) {
      root.appendChild(highlight)
    }
    scroll.replaceChildren(root)
    if (scroll.scrollTop !== desiredTop) {
      scroll.scrollTop = desiredTop
    }

    const previous = appliedRef.current
    if (
      latest.streamId !== null
      && latest.streamId === grid.streamId
      && (previous === null || previous.streamId !== grid.streamId || previous.version < grid.version)
    ) {
      appliedRef.current = { streamId: grid.streamId, version: grid.version }
      controllerRef.current.applied(grid.streamId, grid.version)
    }
  }, [])

  const schedule = useCallback(() => {
    if (pendingDrawRef.current) {
      return
    }
    pendingDrawRef.current = true
    const latest = latestRef.current
    targetRef.current = { streamId: latest.streamId, version: latest.view?.version ?? null }
    const run = () => {
      pendingDrawRef.current = false
      frameRef.current = null
      lastDrawAtRef.current = Date.now()
      draw()
    }
    const wait = DRAW_MIN_INTERVAL_MS - (Date.now() - lastDrawAtRef.current)
    if (wait > 0) {
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        frameRef.current = requestAnimationFrame(run)
      }, wait)
    } else {
      frameRef.current = requestAnimationFrame(run)
    }
  }, [draw])

  useLayoutEffect(() => {
    scheduleRef.current = schedule
  }, [schedule])

  // 视图版本变化即排队重绘；滚动重绘走同一入口。
  useEffect(() => {
    schedule()
  }, [schedule, session.view, session.streamId])

  // 卸载时取消未执行的帧/定时器，避免旧绘制在组件消失后 ACK。
  useEffect(
    () => () => {
      if (frameRef.current !== null) {
        cancelAnimationFrame(frameRef.current)
        frameRef.current = null
      }
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
      pendingDrawRef.current = false
    },
    [],
  )

  const measure = useCallback(() => {
    const element = scrollRef.current
    const current = latestRef.current
    if (
      element === null
      || !current.hasControl
      || current.status !== 'RUNNING'
      || current.environmentId === null
    ) {
      return
    }
    // 无布局尺寸时绝不提出 5x2 之类的假尺寸。
    if (element.clientWidth <= 0 || element.clientHeight <= 0) {
      return
    }
    const cols = clamp(Math.floor(element.clientWidth / TERMINAL_CELL_WIDTH), MIN_COLUMNS, MAX_COLUMNS)
    const rows = clamp(Math.floor(element.clientHeight / TERMINAL_CELL_HEIGHT), MIN_ROWS, MAX_ROWS)
    const key = [
      current.environmentId,
      current.identity?.terminalId ?? '',
      current.writer?.writerEpoch ?? '',
      cols,
      rows,
    ].join('|')
    if (lastSizeRef.current === key) {
      return
    }
    const view = current.view
    if (view !== null && view.cols === cols && view.rows === rows) {
      lastSizeRef.current = key
      return
    }
    // 只有控制器接受时才记录去重；被拒绝不得提前写死。
    if (controllerRef.current.resize(cols, rows)) {
      lastSizeRef.current = key
    }
  }, [])

  const writerKey = `${session.hasControl}:${session.environmentId ?? ''}:${session.identity?.terminalId ?? ''}:${session.writer?.writerEpoch ?? ''}`

  // 持控制权时显式测量：claim 成功若尺寸未变不会有新的 RO 回调，必须主动 fit。
  useEffect(() => {
    measure()
  }, [measure, writerKey])

  useEffect(() => {
    const element = scrollRef.current
    if (element === null || typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(() => measure())
    observer.observe(element)
    return () => observer.disconnect()
  }, [measure])

  const dispatch = (bytes: Uint8Array | null): boolean => {
    if (bytes === null || !canInput()) {
      return false
    }
    const accepted = controllerRef.current.sendInput(bytes)
    if (accepted) {
      setInputRejected(false)
    }
    return accepted
  }

  const encodeAndDispatch = (factory: () => Uint8Array | null): void => {
    try {
      dispatch(factory())
    } catch (error) {
      // 唯一本地协议拒绝：超预算/孤立代理。明确告知本次未发送，不输出载荷。
      if (error instanceof TerminalInputError) {
        setInputRejected(true)
        return
      }
      throw error
    }
  }

  const handleInput = (event: FormEvent<HTMLTextAreaElement>) => {
    const target = event.currentTarget
    if (suppressNextInputRef.current) {
      // 抑制 Chromium 在 compositionend 之后补发的最终 input（同一次提交）。
      suppressNextInputRef.current = false
      target.value = ''
      return
    }
    const value = target.value
    const native = event.nativeEvent as InputEvent
    if (value.length === 0 || native.isComposing || composingRef.current) {
      return
    }
    target.value = ''
    encodeAndDispatch(() => encodeTerminalText(value))
  }

  const handleCompositionStart = () => {
    composingRef.current = true
  }

  const handleCompositionEnd = (event: CompositionEvent<HTMLTextAreaElement>) => {
    composingRef.current = false
    const target = event.currentTarget
    const value = target.value
    if (value.length === 0) {
      return
    }
    target.value = ''
    encodeAndDispatch(() => encodeTerminalText(value))
    // Chromium 随后可能再发一次 value 相同的最终 input（isComposing:false）：抑制它。
    suppressNextInputRef.current = true
  }

  const copySelectionToNative = (): boolean => {
    const grid = latestRef.current.view
    if (grid === null || !selectionValid(grid, selectionRef.current)) {
      return false
    }
    const text = copySelectedText(grid, selectionRef.current!.selection)
    if (text.length === 0) {
      return false
    }
    const input = inputRef.current
    if (input === null) {
      return false
    }
    // 唯一原生复制路径：把镜像文本放进透明 textarea 并全选，让默认 copy 事件携带它。
    input.value = text
    input.select()
    copyBufferRef.current = true
    return true
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    // 终端拥有自己的全部按键语义：不向 React 父级冒泡，避免应用 Stop/收起快捷键被触发。
    event.stopPropagation()
    const input = event.currentTarget
    if (copyBufferRef.current) {
      // 上一轮原生复制的文本不得混入后续真实输入。
      copyBufferRef.current = false
      input.value = ''
    }
    suppressNextInputRef.current = false

    const native = event.nativeEvent
    if (native.isComposing || event.keyCode === 229) {
      // IME 组合期间保留候选行为（Esc/Tab/方向键交给输入法）。
      return
    }

    const ctrl = event.ctrlKey
    const alt = event.altKey
    const meta = event.metaKey
    const key = event.key

    if ((ctrl || meta) && !alt && (key === 'v' || key === 'V')) {
      // 保留浏览器默认粘贴事件，由 onPaste 编码 clipboard 文本。
      return
    }

    if ((ctrl || meta) && !alt && (key === 'c' || key === 'C')) {
      if (copySelectionToNative()) {
        // 不拦截默认行为：让原生 copy 触发 onCopy。
        return
      }
    }

    const currentModes = modes()
    if (currentModes === null) {
      // 无权威 view：不编码、不发送；仍可查看旧只读末屏。
      return
    }
    const altGraph = typeof event.getModifierState === 'function' ? event.getModifierState('AltGraph') : false
    const bytes = encodeTerminalKey(
      {
        key,
        code: event.code,
        shift: event.shiftKey,
        alt,
        ctrl,
        meta,
        isComposing: native.isComposing,
        altGraph,
      },
      currentModes,
    )
    if (bytes !== null) {
      event.preventDefault()
      encodeAndDispatch(() => bytes)
      return
    }
    if (BROWSER_DEFAULT_KEYS.has(key)) {
      event.preventDefault()
    }
  }

  const handleCopy = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const grid = latestRef.current.view
    if (grid === null || !selectionValid(grid, selectionRef.current)) {
      return
    }
    const text = copySelectedText(grid, selectionRef.current!.selection)
    event.clipboardData?.setData('text/plain', text)
    event.preventDefault()
    event.currentTarget.value = ''
    copyBufferRef.current = false
  }

  const handlePaste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const text = event.clipboardData?.getData('text/plain') ?? ''
    if (text.length === 0) {
      return
    }
    const currentModes = modes()
    if (currentModes === null) {
      return
    }
    event.preventDefault()
    encodeAndDispatch(() => encodeTerminalPaste(text, currentModes))
  }

  const startLocalSelection = (point: GridPoint) => {
    const grid = latestRef.current.view
    if (grid === null || grid.lines.length === 0) {
      return
    }
    const line = clamp(point.line, 0, grid.lines.length - 1)
    const x = clamp(point.x, 0, grid.cols)
    const stored: StoredSelection = {
      selection: { anchor: { line, x }, focus: { line, x } },
      anchorId: grid.lines[line].id,
      focusId: grid.lines[line].id,
    }
    selectionRef.current = stored
    inputRef.current?.focus()
    schedule()
  }

  const updateLocalSelection = (point: GridPoint) => {
    const grid = latestRef.current.view
    const stored = selectionRef.current
    if (grid === null || stored === null || grid.lines.length === 0) {
      return
    }
    const line = clamp(point.line, 0, grid.lines.length - 1)
    const x = clamp(point.x, 0, grid.cols)
    selectionRef.current = {
      selection: { anchor: stored.selection.anchor, focus: { line, x } },
      anchorId: stored.anchorId,
      focusId: grid.lines[line].id,
    }
    schedule()
  }

  const buttonOf = (event: MouseEvent<HTMLTextAreaElement>): TerminalMouseButton => {
    if (event.button === 1) {
      return 1
    }
    if (event.button === 2) {
      return 2
    }
    return 0
  }

  const moveButtonOf = (event: MouseEvent<HTMLTextAreaElement>): TerminalMouseButton | null => {
    if ((event.buttons & 2) !== 0) {
      return 2
    }
    if ((event.buttons & 4) !== 0) {
      return 1
    }
    if ((event.buttons & 1) !== 0) {
      return 0
    }
    return null
  }

  const forwardMouse = (
    action: TerminalMouseAction,
    event: MouseEvent<HTMLTextAreaElement>,
    point: GridPoint,
    wheel: TerminalWheelDirection | null = null,
  ): boolean => {
    const grid = latestRef.current.view
    if (grid === null || !mouseReporting()) {
      return false
    }
    const report = reportPoint(point)
    if (report === null) {
      return false
    }
    encodeAndDispatch(() => encodeTerminalMouse(
      {
        action,
        button: action === 'move' ? moveButtonOf(event) : buttonOf(event),
        x: report.x,
        y: report.y,
        shift: event.shiftKey,
        alt: event.altKey,
        ctrl: event.ctrlKey,
        wheel,
      },
      grid.inputModes,
      grid.cols,
      grid.rows,
    ))
    return true
  }

  const handleMouseDown = (event: MouseEvent<HTMLTextAreaElement>) => {
    const point = gridPoint(event.clientX, event.clientY)
    if (event.shiftKey || !mouseReporting()) {
      event.preventDefault()
      draggingRef.current = true
      startLocalSelection(point)
      return
    }
    if (!forwardMouse('press', event, point)) {
      // 历史区/越界：本地选择，不向 VT 报告。
      event.preventDefault()
      draggingRef.current = true
      startLocalSelection(point)
    }
  }

  const handleMouseMove = (event: MouseEvent<HTMLTextAreaElement>) => {
    const point = gridPoint(event.clientX, event.clientY)
    if (draggingRef.current) {
      event.preventDefault()
      updateLocalSelection(point)
      return
    }
    if (mouseReporting()) {
      forwardMouse('move', event, point)
    }
  }

  const handleMouseUp = (event: MouseEvent<HTMLTextAreaElement>) => {
    const point = gridPoint(event.clientX, event.clientY)
    if (draggingRef.current) {
      draggingRef.current = false
      return
    }
    if (mouseReporting()) {
      forwardMouse('release', event, point)
    }
  }

  const handleFocus = () => {
    const currentModes = modes()
    if (currentModes !== null) {
      encodeAndDispatch(() => encodeTerminalFocus(true, currentModes))
    }
  }

  const handleBlur = () => {
    const currentModes = modes()
    if (currentModes !== null) {
      encodeAndDispatch(() => encodeTerminalFocus(false, currentModes))
    }
  }

  const handleScroll = () => {
    const scroll = scrollRef.current
    const grid = latestRef.current.view
    if (scroll === null || grid === null) {
      return
    }
    const viewportHeight = scroll.clientHeight > 0
      ? scroll.clientHeight
      : Math.max(grid.rows, 1) * TERMINAL_CELL_HEIGHT
    const maxScroll = Math.max(0, grid.lines.length * TERMINAL_CELL_HEIGHT - viewportHeight)
    if (scroll.scrollTop >= maxScroll - 1) {
      followRef.current = true
      pinnedRef.current = null
    } else {
      followRef.current = false
      const total = grid.lines.length
      if (total > 0) {
        const topLine = clamp(
          Math.floor(scroll.scrollTop / TERMINAL_CELL_HEIGHT),
          0,
          total - 1,
        )
        pinnedRef.current = {
          lineId: grid.lines[topLine].id,
          offset: scroll.scrollTop - topLine * TERMINAL_CELL_HEIGHT,
        }
      }
    }
    schedule()
  }

  // wheel 需要非 passive 监听；绑定一次，通过 ref 读取最新逻辑。
  useLayoutEffect(() => {
    wheelHandlerRef.current = (event: WheelEvent) => {
      const scroll = scrollRef.current
      if (scroll === null) {
        return
      }
      const point = gridPoint(event.clientX, event.clientY)
      const reactLike = event as unknown as MouseEvent<HTMLTextAreaElement>
      if (mouseReporting() && !event.shiftKey && reportPoint(point) !== null) {
        event.preventDefault()
        forwardMouse('wheel', reactLike, point, event.deltaY < 0 ? 'up' : 'down')
        return
      }
      event.preventDefault()
      const horizontal = event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)
      if (horizontal) {
        scroll.scrollLeft += event.deltaX !== 0 ? event.deltaX : event.deltaY
      } else {
        scroll.scrollTop += event.deltaY
      }
      schedule()
    }
  })

  useEffect(() => {
    const element = inputRef.current
    if (element === null) {
      return
    }
    const onWheel = (event: WheelEvent) => wheelHandlerRef.current(event)
    element.addEventListener('wheel', onWheel, { passive: false })
    return () => element.removeEventListener('wheel', onWheel)
  }, [])

  const readOnly = !(session.hasControl && session.status === 'RUNNING' && session.view !== null)

  return (
    <div
      className="terminal-viewport"
      data-testid="terminal-viewport"
      data-readonly={String(readOnly)}
      data-stream={session.streamId ?? ''}
      data-version={session.view?.version ?? ''}
    >
      <div
        ref={scrollRef}
        className="terminal-viewport__scroll"
        role="img"
        aria-label={t('shell.viewport.ariaLabel')}
        onScroll={handleScroll}
      />
      <textarea
        ref={inputRef}
        className="terminal-viewport__input"
        aria-label={t('shell.input.ariaLabel')}
        readOnly={readOnly}
        spellCheck={false}
        autoCapitalize="off"
        autoCorrect="off"
        autoComplete="off"
        onInput={handleInput}
        onCompositionStart={handleCompositionStart}
        onCompositionEnd={handleCompositionEnd}
        onKeyDown={handleKeyDown}
        onCopy={handleCopy}
        onPaste={handlePaste}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onFocus={handleFocus}
        onBlur={handleBlur}
      />
      {inputRejected && (
        <div className="terminal-viewport__status" role="status">
          {t('shell.input.rejected')}
        </div>
      )}
    </div>
  )
}
