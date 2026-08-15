import { useEffect, type RefObject } from 'react'
import type { CanvasView } from '@/features/canvas/types'
import { hasBlockingModal, isEditableKeyboardTarget } from '@/shared/ui/blocking-overlay'

type KeyboardApi = {
  view: CanvasView
  stageElementRef: RefObject<HTMLElement | null>
  fitViewRef: RefObject<(() => void) | null>
  focusSelectionRef: RefObject<(() => void) | null>
  zoomRef: RefObject<((scale: number) => void) | null>
  clearSelection: () => void
  deleteSelection: () => void
  focusThread: () => void
  createTextNode: () => void
  closeOverlays: () => void
}

/** Agent panel（CanvasAgentDock 的 aside.agent-panel）：panel/Composer 自己的按键作用域。 */
const AGENT_PANEL_SELECTOR = '.agent-panel'
/** 自带 Escape 语义的内层菜单（右键菜单/添加菜单）。 */
const MENU_SELECTOR = '[role="menu"]'

/**
 * window/document/documentElement/body 没有具体聚焦控件，编辑器视为
 * Canvas surface；真正落在 HTMLElement 上的焦点仍按 stage.contains 判定。
 */
function isCanvasSurfaceEscapeTarget(
  target: EventTarget | null,
  stage: HTMLElement | null,
  view: CanvasView,
): boolean {
  if (!(target instanceof HTMLElement) || target === document.body || target === document.documentElement) {
    return view === 'editor'
  }
  return Boolean(stage?.contains(target))
}

/**
 * 编辑器键盘快捷键与焦点恢复，与主 controller 主体分离。
 *
 * Escape 优先级采用「capture 相位 + 作用域判定」，不依赖 useEffect/window
 * listener 的注册顺序：
 * - blocking modal 最高优先级，任何情况下先让路；
 * - Agent panel（Composer、Event/interaction panel 等）内的按键归 panel/Composer，
 *   Canvas 全局 handler 不抢；
 * - 已打开的内层菜单（右键菜单/添加菜单）拥有自己的 Escape 语义；
 * - Canvas surface（stage 内）上的 Escape 由本 handler 在 capture 相位消费
 *   （preventDefault + stopPropagation），低优先级的 window bubble handler
 *   （如 ThreadComposer 的异步焦点恢复）根本收不到该事件，焦点留在 stage。
 */
export function useCanvasKeyboard(api: KeyboardApi) {
  const {
    view,
    stageElementRef,
    fitViewRef,
    focusSelectionRef,
    zoomRef,
    clearSelection,
    deleteSelection,
    focusThread,
    createTextNode,
    closeOverlays,
  } = api

  useEffect(() => {
    function handleKeyboard(event: KeyboardEvent) {
      // 任何 blocking overlay（modal/alertdialog/lightbox）存在时，Canvas 全局
      // 快捷键全部让路：不拦截、不抢事件——不只是 Escape。
      if (hasBlockingModal()) {
        return
      }
      const target = event.target as HTMLElement | null
      // Agent panel 内的按键由 panel/Composer 处理，Canvas 全局 handler 一律不抢。
      if (target instanceof HTMLElement && target.closest(AGENT_PANEL_SELECTOR)) {
        return
      }
      // 已打开的内层菜单拥有自己的 Escape 语义（含 rename/confirm 模式的回退）。
      if (target instanceof HTMLElement && target.closest(MENU_SELECTOR)) {
        return
      }
      const isTyping = isEditableKeyboardTarget(target)
      const isButton = Boolean(target?.closest?.('button'))
      const modifier = event.metaKey || event.ctrlKey

      if (modifier && event.key.toLowerCase() === 'k') {
        if (view !== 'editor') {
          return
        }
        event.preventDefault()
        focusThread()
        return
      }

      if (event.key === 'Escape') {
        // 只消费 Canvas surface 上的 Escape：无具体聚焦元素（window/document/
        // documentElement/body）时编辑器视为当前作用域；其余不在 stage 内的
        // 目标（顶栏、编辑器头部等）交给低优先级作用域处理。
        const onSurface = isCanvasSurfaceEscapeTarget(target, stageElementRef.current, view)
        if (!onSurface) {
          return
        }
        event.preventDefault()
        // capture 相位消费：事件不再下行/冒泡，低优先级 handler 不会随后抢焦点。
        event.stopPropagation()
        closeOverlays()
        if (view === 'editor') {
          clearSelection()
          stageElementRef.current?.focus({ preventScroll: true })
        }
        return
      }

      if (view !== 'editor') {
        return
      }

      const deleteKey = event.key === 'Delete' || event.key === 'Backspace'
      if (deleteKey && !isTyping) {
        event.preventDefault()
        deleteSelection()
        return
      }

      if (isTyping || isButton) {
        return
      }

      if (event.key === '0') {
        event.preventDefault()
        fitViewRef.current?.()
      } else if (event.key === '1') {
        event.preventDefault()
        zoomRef.current?.(1)
      } else if (event.key.toLowerCase() === 'f') {
        event.preventDefault()
        focusSelectionRef.current?.()
      } else if (event.key.toLowerCase() === 't') {
        event.preventDefault()
        createTextNode()
      }
    }

    window.addEventListener('keydown', handleKeyboard, true)
    return () => window.removeEventListener('keydown', handleKeyboard, true)
  }, [
    clearSelection,
    closeOverlays,
    createTextNode,
    deleteSelection,
    fitViewRef,
    focusThread,
    focusSelectionRef,
    stageElementRef,
    view,
    zoomRef,
  ])
}
