import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CanvasAddMenu } from '@/features/canvas/agent/CanvasAddMenu'
import { CanvasAgentComposer } from '@/features/canvas/agent/CanvasAgentComposer'
import { CanvasAgentThread } from '@/features/canvas/agent/CanvasAgentThread'
import {
  CanvasRuntimeContext,
  useCanvasRuntime,
} from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasLocalState } from '@/features/canvas/types'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import { setLocale } from '@/shared/i18n'

const INITIAL_STATE: CanvasLocalState = {
  view: 'editor',
  canvasId: '1',
  selectedIds: ['10', '11'],
  selectedLinks: [],
  positionDrafts: {},
  viewport: { x: 0, y: 0, zoom: 1 },
  tool: 'select',
  toast: null,
  addMenuOpen: false,
  addMenuIndex: 0,
  threadOpen: false,
  agentPrompt: '',
  contextMode: 'selection',
  messages: [],
  uploadProgress: {},
  commandPending: false,
  conflictMessage: null,
  textEditor: null,
}

beforeEach(() => {
  setLocale('zh-CN')
})

describe('Canvas add menu', () => {
  // 键盘、鼠标和隐藏 file input 共享同一选择路径，避免只在点击场景可用。
  it('supports all keyboard navigation and dispatches non-file actions', async () => {
    const user = userEvent.setup()
    const harness = renderHarness(
      <CanvasAddMenu menuId="canvas-add-menu" />,
      { addMenuOpen: true, addMenuIndex: 0 },
    )
    const menu = screen.getByRole('menu')
    expect(screen.getByRole('menuitem', { name: /图片资源/ })).toHaveFocus()

    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(1)
    fireEvent.keyDown(menu, { key: 'ArrowUp' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(6)
    fireEvent.keyDown(menu, { key: 'Home' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(0)
    fireEvent.keyDown(menu, { key: 'End' })
    expect(harness.controller.setAddMenuIndex).toHaveBeenLastCalledWith(6)
    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(harness.controller.closeAddMenu).toHaveBeenCalled()

    harness.rerender({ addMenuOpen: true, addMenuIndex: 3 })
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Enter' })
    expect(harness.controller.handleAddAction).toHaveBeenCalledWith('text-resource')

    await user.hover(screen.getByRole('menuitem', { name: /视频生成/ }))
    expect(harness.controller.setAddMenuIndex).toHaveBeenCalledWith(5)
    await user.click(screen.getByRole('menuitem', { name: /分组/ }))
    expect(harness.controller.handleAddAction).toHaveBeenCalledWith('group')

    harness.rerender({ addMenuOpen: true, addMenuIndex: 99 })
    fireEvent.keyDown(screen.getByRole('menu'), { key: 'Enter' })
    expect(harness.controller.handleAddAction).toHaveBeenCalledTimes(2)
  })

  it('opens the correct file picker and uploads only non-empty selections', async () => {
    const user = userEvent.setup()
    const inputClick = vi.spyOn(HTMLInputElement.prototype, 'click')
    const harness = renderHarness(
      <CanvasAddMenu menuId="canvas-add-menu" />,
      { addMenuOpen: true },
    )
    const container = screen.getByRole('menu')
    const input = container.querySelector('input[type="file"]') as HTMLInputElement

    await user.click(screen.getByRole('menuitem', { name: /视频资源/ }))
    expect(inputClick).toHaveBeenCalled()
    expect(input.accept).toBe('video/mp4,video/quicktime')

    const file = new File(['video'], 'clip.mp4', { type: 'video/mp4' })
    fireEvent.change(input, { target: { files: [file] } })
    expect(harness.controller.uploadFiles).toHaveBeenCalledWith([file])
    expect(harness.controller.closeAddMenu).toHaveBeenCalled()

    vi.mocked(harness.controller.uploadFiles).mockClear()
    fireEvent.change(input, { target: { files: [] } })
    expect(harness.controller.uploadFiles).not.toHaveBeenCalled()
    inputClick.mockRestore()
  })

  it('keeps the menu inert while closed', () => {
    renderHarness(<CanvasAddMenu menuId="canvas-add-menu" />)
    const menu = screen.getByRole('menu', { hidden: true })
    expect(menu).toHaveAttribute('aria-hidden', 'true')
    expect(within(menu).getAllByRole('menuitem', { hidden: true })[0]).toHaveAttribute('tabindex', '-1')
  })
})

describe('Canvas agent composer', () => {
  // Enter 必须避开 Shift 换行和 IME composing，上传进度则保持纯本地呈现。
  it('autosizes, edits, sends, toggles add menu and renders upload progress', async () => {
    const user = userEvent.setup()
    const harness = renderHarness(
      <CanvasAgentComposer menuId="canvas-add-menu" />,
      {
        agentPrompt: 'first',
        uploadProgress: { 'clip.mp4:1:5': 0.456 },
      },
    )
    const textarea = screen.getByRole('textbox', { name: /向 Agent/ })
    Object.defineProperty(textarea, 'scrollHeight', { configurable: true, value: 140 })
    harness.rerender({
      agentPrompt: 'second',
      uploadProgress: { 'clip.mp4:1:5': 0.456 },
    })
    expect(textarea).toHaveStyle({ height: '104px', overflowY: 'auto' })
    expect(screen.getByText(/clip.mp4 46%/)).toBeInTheDocument()

    fireEvent.change(textarea, { target: { value: 'draft' } })
    expect(harness.controller.setAgentPrompt).toHaveBeenLastCalledWith('draft')

    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: true })
    fireEvent.keyDown(textarea, { key: 'Enter', isComposing: true })
    expect(harness.controller.sendAgent).not.toHaveBeenCalled()
    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: false, isComposing: false })
    expect(harness.controller.sendAgent).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: /添加资源/ }))
    expect(harness.controller.toggleAddMenu).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '发送给 Agent' }))
    expect(harness.controller.sendAgent).toHaveBeenCalledTimes(2)
  })

  it('uses minimum height and omits progress when no textarea measurement or upload exists', () => {
    const harness = renderHarness(<CanvasAgentComposer menuId="canvas-add-menu" />)
    const textarea = screen.getByRole('textbox', { name: /向 Agent/ })
    expect(textarea).toHaveStyle({ height: '37px', overflowY: 'hidden' })
    expect(document.querySelector('.upload-progress-list')).not.toBeInTheDocument()

    harness.controller.agentPromptRef.current = null
    harness.rerender({ agentPrompt: 'detached' })
    expect(screen.getByRole('textbox', { name: /向 Agent/ })).toBeInTheDocument()
  })
})

describe('Canvas agent thread', () => {
  // Thread 展开时应滚到底部，并让 selection/whole 上下文及折叠动作都可达。
  it('renders messages, scrolls, switches context and collapses', async () => {
    const user = userEvent.setup()
    const scrollHeight = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(240)
    const harness = renderHarness(
      <CanvasAgentThread />,
      {
        threadOpen: true,
        messages: [
          { kind: 'user', text: '用户消息' },
          { kind: 'agent', text: 'Agent 消息' },
        ],
      },
    )
    const thread = screen.getByLabelText('Canvas Agent 消息')
    const messages = thread.querySelector('.thread-messages') as HTMLDivElement
    expect(messages.scrollTop).toBe(240)
    expect(screen.getByText('用户消息')).toHaveClass('user')
    expect(screen.getByText('Agent 消息')).not.toHaveClass('user')
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('selection context')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /整张画布/ }))
    expect(harness.controller.setContextMode).toHaveBeenCalledWith('whole')
    await user.click(screen.getByRole('button', { name: /当前选区/ }))
    expect(harness.controller.setContextMode).toHaveBeenCalledWith('selection')
    await user.click(screen.getByRole('button', { name: '收起 Agent 消息' }))
    expect(harness.controller.collapseThread).toHaveBeenCalled()

    harness.rerender({ threadOpen: true, contextMode: 'whole' })
    expect(screen.getByRole('button', { name: /整张画布/ })).toHaveAttribute('aria-pressed', 'true')
    scrollHeight.mockRestore()
  })

  it('stays inert and does not attempt scrolling while closed', () => {
    renderHarness(<CanvasAgentThread />)
    const thread = screen.getByLabelText('Canvas Agent 消息', { selector: '[hidden]' })
    expect(thread).toHaveAttribute('aria-hidden', 'true')
  })
})

describe('Canvas runtime context boundary', () => {
  it('fails closed outside CanvasRuntimeProvider', () => {
    function MissingProviderConsumer() {
      useCanvasRuntime()
      return null
    }
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    expect(() => render(<MissingProviderConsumer />)).toThrow('CanvasRuntimeProvider is required')
    consoleError.mockRestore()
  })
})

function renderHarness(
  children: ReactNode,
  initialState: Partial<CanvasLocalState> = {},
) {
  const agentPromptRef = createRef<HTMLTextAreaElement>()
  const dockAddRef = createRef<HTMLButtonElement>()
  const actions = {
    closeAddMenu: vi.fn(),
    setAddMenuIndex: vi.fn(),
    handleAddAction: vi.fn(),
    uploadFiles: vi.fn(async () => undefined),
    setAgentPrompt: vi.fn(),
    sendAgent: vi.fn(),
    toggleAddMenu: vi.fn(),
    setContextMode: vi.fn(),
    collapseThread: vi.fn(),
  }
  let state = { ...INITIAL_STATE, ...initialState }

  const controller = {
    state,
    agentPromptRef,
    dockAddRef,
    contextCount: 2,
    contextDescription: 'selection context',
    ...actions,
  } as unknown as CanvasController

  const value = () => ({
    ...controller,
    state,
  }) as CanvasController

  const view = render(
    <CanvasRuntimeContext.Provider value={value()}>
      {children}
    </CanvasRuntimeContext.Provider>,
  )

  return {
    controller,
    rerender(patch: Partial<CanvasLocalState>) {
      state = { ...state, ...patch }
      view.rerender(
        <CanvasRuntimeContext.Provider value={value()}>
          {children}
        </CanvasRuntimeContext.Provider>,
      )
    },
  }
}
