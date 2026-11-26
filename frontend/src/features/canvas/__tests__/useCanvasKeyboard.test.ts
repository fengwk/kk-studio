import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { useCanvasKeyboard } from '@/features/canvas/useCanvasKeyboard'

/**
 * useCanvasKeyboard 独立契约测试。
 *
 * hook 在 window 上以 capture 相位注册 keydown，全部回调来自 api 参数；
 * 本测试直接构造 api 并派发真实 KeyboardEvent，验证：
 * - 编辑视图快捷键 0/1/f/t、Delete/Backspace 的消费与回调；
 * - Escape 的 surface 作用域判定（stage 内/编辑器顶层 vs 其他元素）与焦点恢复；
 * - blocking overlay / 内层菜单存在时全局 handler 让路；
 */
interface KeyboardApi {
  stageElementRef: { current: HTMLElement | null }
  fitViewRef: { current: (() => void) | null }
  focusSelectionRef: { current: (() => void) | null }
  zoomRef: { current: ((scale: number) => void) | null }
  clearSelection: Mock<() => void>
  deleteSelection: Mock<() => void>
  createTextNode: Mock<() => void>
  closeOverlays: Mock<() => void>
}

function createApi(): KeyboardApi {
  return {
    stageElementRef: { current: null },
    fitViewRef: { current: null },
    focusSelectionRef: { current: null },
    zoomRef: { current: null },
    clearSelection: vi.fn(),
    deleteSelection: vi.fn(),
    createTextNode: vi.fn(),
    closeOverlays: vi.fn(),
  }
}

function setup() {
  const api = createApi()
  const hook = renderHook(() => useCanvasKeyboard(api))
  return { hook, api }
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

  it('fires 0/1/f/t with preventDefault', () => {
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
})

describe('useCanvasKeyboard scoping', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('lets blocking overlays and inner menus handle keys first', () => {
    const { api } = setup()
    const blocking = document.createElement('div')
    blocking.className = 'modal-backdrop'
    document.body.appendChild(blocking)
    fireKey(window, { key: 't' })
    expect(api.createTextNode).not.toHaveBeenCalled()
    blocking.remove()

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

  it('treats window/document targets as the canvas surface', () => {
    const { api } = setup()
    const escape = fireKey(window, { key: 'Escape' })
    expect(escape.defaultPrevented).toBe(true)
    expect(api.closeOverlays).toHaveBeenCalledTimes(1)
  })

  it('stops consuming shortcuts and Escape after unmount', () => {
    const { hook, api } = setup()
    api.fitViewRef.current = vi.fn()
    hook.unmount()

    for (const key of ['Escape', 'Delete', '0']) {
      expect(fireKey(window, { key }).defaultPrevented).toBe(false)
    }
    expect(api.closeOverlays).not.toHaveBeenCalled()
    expect(api.clearSelection).not.toHaveBeenCalled()
    expect(api.deleteSelection).not.toHaveBeenCalled()
    expect(api.fitViewRef.current).not.toHaveBeenCalled()
  })
})
