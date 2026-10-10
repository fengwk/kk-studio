/**
 * 全局终端面板离线 harness（Playwright Chromium）。
 *
 * 复用生产 `TerminalViewport` 与 `terminal.css`，用真实 `.app-frame` / `.stage` /
 * `.terminal-panel` 几何验证底部面板与 stage 共享高度；并注入一个记录型假
 * `TerminalController`（无 Backend、无 WebSocket）以观测输入编码、resize 与 applied。
 *
 * `window.__terminalHarness` 暴露确定性切面：
 * - `sendInput` / `resize` / `applied` 是控制器收到的调用记录（输入解码为字符串）；
 * - `pressKey` / `typeText` / `compose` 在真实 textarea 上派发浏览器原生事件，
 *   其中 `compose` 复现 Chromium 在 compositionend 之后补发最终 input 的序列；
 * - `patchSession` / `patchView` 用 React state 更新会话，触发视口重绘。
 */

import { useCallback, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import type { TerminalController, TerminalSessionSnapshot } from '@/features/shell/terminal-controller'
import type { TerminalMirrorState } from '@/features/shell/terminal-view-mirror'
import type { MouseMode, TerminalInputModes } from '@/features/shell/terminal-view-codec'
import { gridFromCapturedView } from '@/features/shell/__fixtures__/captured-view'
import { TerminalViewport } from '@/features/shell/TerminalViewport'

setLocale('zh-CN')

const ENVIRONMENT_ID = 'aaaaaaaa-0000-4000-8000-000000000001'
const TERMINAL_ID = '11111111-1111-4111-8111-111111111111'
const STREAM_ID = 'bbbbbbbb-0000-4000-8000-000000000001'

const COLS = 300
const ROWS = 40
const HISTORY = 60
/** 可从底部直接看到的两行固定内容：倒数第二行用于复制，末行是 shell prompt。 */
const COPY_LINE = 'COPY_ME'
const PROMPT_LINE = 'user@host:~$ '

const BASE_MODES: TerminalInputModes = {
  applicationCursor: false,
  applicationKeypad: false,
  bracketedPaste: false,
  autoNewLine: false,
  altSendsEscape: true,
  mouseMode: 'NONE',
  mouseFormat: 'XTERM',
}

function buildView(version = 1, modes: TerminalInputModes = BASE_MODES): TerminalMirrorState {
  const texts: string[] = []
  for (let index = 0; index < HISTORY + ROWS; index += 1) {
    texts.push(index < HISTORY ? `history-${index}` : `screen-${index - HISTORY}`)
  }
  texts[texts.length - 2] = COPY_LINE
  texts[texts.length - 1] = PROMPT_LINE
  const grid = gridFromCapturedView({
    cols: COLS,
    rows: ROWS,
    cursorX: PROMPT_LINE.length,
    cursorY: ROWS - 1,
    alternate: false,
    history: HISTORY,
    lines: texts.map((text) => ({ wrapped: false, text })),
  })
  return {
    ...grid,
    terminalId: TERMINAL_ID,
    streamId: STREAM_ID,
    version,
    cursorVisible: true,
    cursorShape: null,
    inputModeRevision: 0,
    inputModes: modes,
    lines: grid.lines.map((line, index) => ({ ...line, id: index })),
  }
}

function buildSession(): TerminalSessionSnapshot {
  return {
    environmentId: ENVIRONMENT_ID,
    identity: { daemonInstanceId: 'daemon-1', terminalId: TERMINAL_ID },
    streamId: STREAM_ID,
    executable: '/bin/bash',
    status: 'RUNNING',
    exitCode: null,
    view: buildView(),
    viewApplied: false,
    writer: null,
    hasControl: true,
    pending: false,
    notice: null,
  }
}

const textDecoder = new TextDecoder()
const sendInput: string[] = []
const resize: { cols: number; rows: number }[] = []
const applied: { streamId: string; version: number }[] = []

/** 记录型假控制器；只实现视口会调用的公开面。 */
const controller = {
  show: () => undefined,
  hide: () => undefined,
  selectEnvironment: () => undefined,
  refresh: () => undefined,
  claim: () => undefined,
  takeover: () => undefined,
  release: () => undefined,
  restart: () => undefined,
  terminate: () => undefined,
  sendInput: (bytes: Uint8Array) => {
    sendInput.push(textDecoder.decode(bytes))
    return true
  },
  resize: (cols: number, rows: number) => {
    resize.push({ cols, rows })
    return true
  },
  applied: (streamId: string, version: number) => {
    applied.push({ streamId, version })
  },
} as unknown as TerminalController

const inputElement = () => document.querySelector('.terminal-viewport__input') as HTMLTextAreaElement

function dispatchInput(value: string, isComposing: boolean): void {
  const element = inputElement()
  element.value = value
  element.dispatchEvent(
    new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value, isComposing }),
  )
}

export interface TerminalHarnessApi {
  readonly sendInput: string[]
  readonly resize: { cols: number; rows: number }[]
  readonly applied: { streamId: string; version: number }[]
  clearLog(): void
  focusInput(): void
  typeText(text: string): void
  compose(text: string): void
  pressKey(init: {
    key: string
    code?: string
    ctrlKey?: boolean
    shiftKey?: boolean
    altKey?: boolean
    metaKey?: boolean
  }): void
  patchSession(patch: Partial<TerminalSessionSnapshot>): void
  patchView(patch: Partial<TerminalMirrorState>): void
  setMouseMode(mode: MouseMode): void
}

declare global {
  interface Window {
    __terminalHarness: TerminalHarnessApi
  }
}

let setSessionRef: ((updater: (current: TerminalSessionSnapshot) => TerminalSessionSnapshot) => void) | null =
  null

window.__terminalHarness = {
  sendInput,
  resize,
  applied,
  clearLog() {
    sendInput.length = 0
    resize.length = 0
    applied.length = 0
  },
  focusInput() {
    inputElement().focus()
  },
  typeText(text) {
    inputElement().focus()
    dispatchInput(text, false)
  },
  compose(text) {
    const element = inputElement()
    element.focus()
    element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    dispatchInput(text, true)
    element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: text }))
    // Chromium 在 compositionend 之后会再补发一次同值、isComposing=false 的 input。
    dispatchInput(text, false)
  },
  pressKey(init) {
    const element = inputElement()
    element.focus()
    element.dispatchEvent(
      new KeyboardEvent('keydown', {
        bubbles: true,
        cancelable: true,
        key: init.key,
        code: init.code ?? '',
        ctrlKey: init.ctrlKey ?? false,
        shiftKey: init.shiftKey ?? false,
        altKey: init.altKey ?? false,
        metaKey: init.metaKey ?? false,
      }),
    )
  },
  patchSession(patch) {
    setSessionRef?.((current) => ({ ...current, ...patch }))
  },
  patchView(patch) {
    setSessionRef?.((current) =>
      current.view === null ? current : { ...current, view: { ...current.view, ...patch } },
    )
  },
  setMouseMode(mode) {
    setSessionRef?.((current) =>
      current.view === null
        ? current
        : { ...current, view: { ...current.view, inputModes: { ...current.view.inputModes, mouseMode: mode } } },
    )
  },
}

export function TerminalHarnessApp() {
  const [session, setSession] = useState<TerminalSessionSnapshot>(buildSession)
  const setSessionStable = useCallback(
    (updater: (current: TerminalSessionSnapshot) => TerminalSessionSnapshot) => setSession(updater),
    [],
  )

  useEffect(() => {
    setSessionRef = setSessionStable
    return () => {
      setSessionRef = null
    }
  }, [setSessionStable])

  return (
    <div className="app-frame" data-testid="harness-frame">
      <main className="stage" data-testid="harness-stage">
        <div className="harness-stage-fill">stage content</div>
      </main>
      <section className="terminal-panel" data-testid="harness-panel" aria-label="终端">
        <header className="terminal-panel__header">
          <div className="terminal-panel__title">
            <span>终端</span>
            <span className="terminal-panel__meta">ready-env · 运行中 · 控制中</span>
          </div>
        </header>
        <div className="terminal-panel__tabpanel">
          <TerminalViewport session={session} controller={controller} />
        </div>
      </section>
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement !== null) {
  createRoot(rootElement).render(<TerminalHarnessApp />)
}
