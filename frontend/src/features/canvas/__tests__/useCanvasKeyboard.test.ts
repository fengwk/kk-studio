import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasKeyboard } from '@/features/canvas/useCanvasKeyboard'
import type { CanvasView } from '@/features/canvas/types'

/**
 * useCanvasKeyboard 独立契约测试。
 *
 * hook 在 window 上以 capture 相位注册 keydown，全部回调来自 api 参数；
 * 本测试直接构造 api 并派发真实 KeyboardEvent，验证：
 * - 编辑视图快捷键 0/1/f/t 与 Cmd/Ctrl+K、Delete/Backspace 的消费与回调；
 * - Escape 的 surface 作用域判定（stage 内/编辑器顶层 vs 其他元素）与焦点恢复；
 * - blocking overlay / agent panel / 内层菜单存在时全局 handler 让路；
 * - library 视图下快捷键与 Escape 都不生效。
 */
interface KeyboardApi {
  view: CanvasView
  stageElementRef: { current: HTMLElement | null }
  fitViewRef: { current: (() => void) | null }
  focusSelectionRef: { current: (() => void) | null }
  zoomRef: { current: ((scale: number) => void) | null }
  clearSelection: ReturnType<typeof vi.fn>
  deleteSelection: ReturnType<typeof vi.fn>
  focusThread: ReturnType<typeof vi.fn>
  createTextNode: ReturnType<typeof vi.fn>
  closeOverlays: ReturnType<typeof vi.fn>
}

function createApi(): KeyboardApi {
  return {
    view: 'editor',
    stageElementRef: { current: null },
    fitViewRef: { current: null },
    focusSelectionRef: { current: null },
    zoomRef: { current: null },
    clearSelection: vi.fn(),
    deleteSelection: vi.fn(),
    focusThread: vi.fn(),
    createTextNode: vi.fn(),
    closeOverlays: vi.fn(),
  }
}

function setup() {
  const api = createApi()
  const hook = renderHook(() => useCanvasKeyboard(api))
  const rerenderWith = (next: Partial<KeyboardApi>) => {
    // hook 闭包捕获同一个 api 对象：就地修改后 rerender 触发 effect 依赖变化重绑。
    Object.assign(api, next)
    hook.rerender()
  }
  return { hook, api, rerenderWith }
}

function fireKey(target: EventTarget, init: KeyboardEventInit): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init })
  act(() => target.dispatchEvent(event))
  return event
}

describe('useCanvasKeyboard editor shortcuts', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('fires 0/1/f/t only in editor view with preventDefault', () => {
    const { api } = setup()
    api.fitViewRef.current = vi.fn()
    api.focusSelectionRef.current = vi.fn()
    api.zoomRef.current = vi.fn()

    const zero = fireKey(window, { key: '0' })
    expect(zero.defaultPrevented).toBe(true)
    expect(api.fitViewRef.current).toHaveBeenCalledTimes(1)
    fireKey(window, { key: '1' })
    expect(api.zoomRef.current).toHaveBeenCalledWith(1)
    const focus = fireKey(window, { key: 'f' })
    expect(focus.defaultPrevented).toBe(true)
    expect(api.focusSelectionRef.current).toHaveBeenCalledTimes(1)
    const text = fireKey(window, { key: 't' })
    expect(text.defaultPrevented).toBe(true)
    expect(api.createTextNode).toHaveBeenCalledTimes(1)
  })

  it('deletes the selection on Delete/Backspace only when not typing', () => {
    const { api } = setup()
    const input = document.createElement('input')
    document.body.appendChild(input)
    const button = document.createElement('button')
    document.body.appendChild(button)

    // 输入控件中的按键交给输入自身。
    fireKey(input, { key: 'Delete' })
    expect(api.deleteSelection).not.toHaveBeenCalled()
    // 删除分支只受 isTyping 保护（isButton 守卫仅作用于 0/1/f/t 快捷键）：
    // 按钮上的 Delete/Backspace 仍消费并删除选中。
    const onButton = fireKey(button, { key: 'Backspace' })
    expect(onButton.defaultPrevented).toBe(true)
    expect(api.deleteSelection).toHaveBeenCalledTimes(1)
    // button 上的 't' 不创建文本（isButton 守卫生效）。
    fireKey(button, { key: 't' })
    expect(api.createTextNode).not.toHaveBeenCalled()

    const deleteEvent = fireKey(window, { key: 'Delete' })
    expect(deleteEvent.defaultPrevented).toBe(true)
    expect(api.deleteSelection).toHaveBeenCalledTimes(2)
    fireKey(window, { key: 'Backspace' })
    expect(api.deleteSelection).toHaveBeenCalledTimes(3)
  })

  it('opens the thread with Cmd/Ctrl+K only in editor view', () => {
    const { api } = setup()
    const meta = fireKey(window, { key: 'k', metaKey: true })
    expect(meta.defaultPrevented).toBe(true)
    expect(api.focusThread).toHaveBeenCalledTimes(1)
    fireKey(window, { key: 'k', ctrlKey: true })
    expect(api.focusThread).toHaveBeenCalledTimes(2)
    // 大写 K 同样生效（key.toLowerCase）。
    fireKey(window, { key: 'K', metaKey: true })
    expect(api.focusThread).toHaveBeenCalledTimes(3)
  })
})

describe('useCanvasKeyboard scoping', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('lets blocking overlays, agent panels, and inner menus handle keys first', () => {
    const { api } = setup()
    const blocking = document.createElement('div')
    blocking.className = 'modal-backdrop'
    document.body.appendChild(blocking)
    fireKey(window, { key: 't' })
    expect(api.createTextNode).not.toHaveBeenCalled()
    blocking.remove()

    const agentPanel = document.createElement('div')
    agentPanel.className = 'agent-panel'
    document.body.appendChild(agentPanel)
    fireKey(agentPanel, { key: 't' })
    expect(api.createTextNode).not.toHaveBeenCalled()
    agentPanel.remove()

    const menu = document.createElement('div')
    menu.setAttribute('role', 'menu')
    document.body.appendChild(menu)
    fireKey(menu, { key: 'Escape' })
    expect(api.closeOverlays).not.toHaveBeenCalled()
  })

  it('only consumes Escape on the canvas surface and refocuses the stage', () => {
    const { api } = setup()
    const stage = document.createElement('section')
    stage.tabIndex = 0
    document.body.appendChild(stage)
    api.stageElementRef.current = stage
    const focusSpy = vi.spyOn(stage, 'focus')

    // stage 内元素的 Escape 被消费并恢复焦点。
    const inside = document.createElement('div')
    stage.appendChild(inside)
    const onSurface = fireKey(inside, { key: 'Escape' })
    expect(onSurface.defaultPrevented).toBe(true)
    expect(api.closeOverlays).toHaveBeenCalledTimes(1)
    expect(api.clearSelection).toHaveBeenCalledTimes(1)
    expect(focusSpy).toHaveBeenCalledWith({ preventScroll: true })

    // stage 外元素（编辑器头部/顶栏）的 Escape 交给低优先级作用域。
    const outside = document.createElement('div')
    document.body.appendChild(outside)
    const offSurface = fireKey(outside, { key: 'Escape' })
    expect(offSurface.defaultPrevented).toBe(false)
    expect(api.closeOverlays).toHaveBeenCalledTimes(1)
  })

  it('treats window/document targets as the editor surface only in editor view', () => {
    const { api, rerenderWith } = setup()
    const escape = fireKey(window, { key: 'Escape' })
    expect(escape.defaultPrevented).toBe(true)
    expect(api.closeOverlays).toHaveBeenCalledTimes(1)

    // 切到 library：window 上的 Escape 不再属于 canvas surface。
    // view 是渲染时解构进闭包的，必须就地修改 api 并 rerender 才能生效。
    rerenderWith({ view: 'library' })
    api.closeOverlays.mockClear()
    api.clearSelection.mockClear()
    api.focusThread.mockClear()
    fireKey(window, { key: 'Escape' })
    expect(api.closeOverlays).not.toHaveBeenCalled()
    // library 视图下 Cmd+K 不打开对话。
    fireKey(window, { key: 'k', metaKey: true })
    expect(api.focusThread).not.toHaveBeenCalled()
    // library 视图下 Delete/0 都不消费。
    const libraryDelete = fireKey(window, { key: 'Delete' })
    expect(libraryDelete.defaultPrevented).toBe(false)
    expect(api.deleteSelection).not.toHaveBeenCalled()
    api.fitViewRef.current = vi.fn()
    const libraryZero = fireKey(window, { key: '0' })
    expect(libraryZero.defaultPrevented).toBe(false)
    expect(api.fitViewRef.current).not.toHaveBeenCalled()
  })
})
