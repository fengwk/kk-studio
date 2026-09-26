import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
} from 'react'
import { placeCaretAtEnd } from '@/features/ai/composer/composer-dom'
import { hasBlockingModal } from '@/shared/ui/blocking-overlay'

const MAX_FOCUS_ATTEMPTS = 5
const FOCUS_RETRY_DELAY_MS = 16

export interface ComposerFocusOptions {
  /** 编辑器根节点（contenteditable=true）。焦点定时器重试期间节点可能尚未可编辑。 */
  editorRef: RefObject<HTMLDivElement | null>
  /** 包含整个 Composer（dock、palette、controls 等）的区域根节点。 */
  containerRef: RefObject<HTMLElement | null>
  /** disabled（不可编辑）期间不调度/不恢复焦点；active 恢复会推迟到重新 enabled。 */
  disabled: boolean
  /** false 时由同一 Composer 区域的 interaction panel 接管；组件保持挂载以保留上传状态。 */
  active: boolean
  /** 当前交互作用域是否允许全局 Escape 把焦点恢复到此 Composer。 */
  focusOnEscape: boolean
  /** 提交在途标记：true -> false 且可编辑时自动恢复键入焦点。 */
  pending: boolean
  /**
   * 关闭当前覆盖层（palette/control menu）；无覆盖层时为 no-op。
   * 若上层 control menu 优先处理了 Esc，返回 true 阻止外部进一步 blur。
   */
  closeOverlay: () => boolean | void
  /**
   * 真正离开 Composer 区域时的回调（用于清空 plusMenuOpen、controlMenu 等）。
   */
  onLeaveRegion?: () => void
}

export interface ComposerFocus {
  /** Composer 区域当前是否拥有焦点 */
  isFocused: boolean
  /**
   * 请求聚焦（带定时重试）。forceCaretAtEnd 用于光标归位到末尾；
   * 编辑器内交互直接调用默认值。
   */
  focusComposer: (forceCaretAtEnd?: boolean) => void
  /**
   * 让 Composer 区域主动失焦（取消待执行 focus 定时器并 blur 区域内当前活动元素）。
   */
  blurComposer: () => void
  /**
   * Composer 区域外层容器的 React 键盘事件处理器。
   * 保证子层 React 组件优先处理 Escape 并可阻止冒泡或 preventDefault。
   */
  handleKeyDown: (event: ReactKeyboardEvent) => void
}

/**
 * Composer 焦点状态机：统一聚焦定时器与重试、覆盖层关闭后的焦点恢复、
 * active/focusOnEscape、pending 完成自动聚焦与卸载清理。
 */
export function useComposerFocus({
  editorRef,
  containerRef,
  disabled,
  active,
  focusOnEscape,
  pending,
  closeOverlay,
  onLeaveRegion,
}: ComposerFocusOptions): ComposerFocus {
  const [isFocused, setIsFocused] = useState(false)
  const isFocusedRef = useRef(false)
  const focusTimerRef = useRef<number | null>(null)
  const restoreFocusRef = useRef(false)
  const wasPendingRef = useRef(false)
  const closeOverlayRef = useRef(closeOverlay)
  const onLeaveRegionRef = useRef(onLeaveRegion)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // 每次 render 后在 layout phase 同步最新 closeOverlay 与 onLeaveRegion：
  useLayoutEffect(() => {
    closeOverlayRef.current = closeOverlay
    onLeaveRegionRef.current = onLeaveRegion
  }, [closeOverlay, onLeaveRegion])

  const isInsideRegion = useCallback((target: Node | null): boolean => {
    if (!target) {
      return false
    }
    const container = containerRef.current
    return Boolean(container && container.contains(target))
  }, [containerRef])

  const clearFocusTimer = useCallback(() => {
    if (focusTimerRef.current !== null) {
      window.clearTimeout(focusTimerRef.current)
      focusTimerRef.current = null
    }
  }, [])

  const blurComposer = useCallback(() => {
    clearFocusTimer()
    restoreFocusRef.current = false
    const activeEl = document.activeElement
    if (activeEl instanceof HTMLElement && isInsideRegion(activeEl)) {
      activeEl.blur()
    }
    isFocusedRef.current = false
    setIsFocused(false)
    onLeaveRegionRef.current?.()
  }, [clearFocusTimer, isInsideRegion])

  const focusComposer = useCallback((forceCaretAtEnd = false) => {
    clearFocusTimer()
    const scheduleFocus = (attempt: number, delay: number) => {
      focusTimerRef.current = window.setTimeout(() => {
        focusTimerRef.current = null
        const el = editorRef.current
        if (!el || !active) {
          return
        }
        if (el.getAttribute('contenteditable') !== 'true') {
          if (attempt < MAX_FOCUS_ATTEMPTS) {
            scheduleFocus(attempt + 1, FOCUS_RETRY_DELAY_MS)
          }
          return
        }
        if (document.activeElement !== el) {
          el.focus({ preventScroll: true })
        }
        const selection = el.ownerDocument.getSelection()
        if (
          document.activeElement === el
          && (
            forceCaretAtEnd
            || !selection
            || selection.rangeCount === 0
            || !el.contains(selection.getRangeAt(0).commonAncestorContainer)
          )
        ) {
          placeCaretAtEnd(el)
        }
        if (document.activeElement === el) {
          isFocusedRef.current = true
          setIsFocused(true)
        } else if (attempt < MAX_FOCUS_ATTEMPTS) {
          scheduleFocus(attempt + 1, FOCUS_RETRY_DELAY_MS)
        }
      }, delay)
    }
    scheduleFocus(0, 0)
  }, [active, clearFocusTimer, editorRef])

  // 区域内的 focusin / focusout 原生监听（仅负责区域焦点状态检测与防抢焦清理）：
  useEffect(() => {
    const container = containerRef.current
    if (!container) {
      return
    }

    if (document.activeElement && container.contains(document.activeElement)) {
      isFocusedRef.current = true
      setIsFocused(true)
    }

    const handleFocusIn = () => {
      if (!isFocusedRef.current) {
        isFocusedRef.current = true
        setIsFocused(true)
      }
    }

    const handleFocusOut = (event: FocusEvent) => {
      const nextTarget = event.relatedTarget as Node | null
      if (nextTarget && container.contains(nextTarget)) {
        return
      }
      queueMicrotask(() => {
        if (!mountedRef.current) {
          return
        }
        if (!container.contains(document.activeElement)) {
          clearFocusTimer()
          restoreFocusRef.current = false
          isFocusedRef.current = false
          setIsFocused(false)
          onLeaveRegionRef.current?.()
        }
      })
    }

    container.addEventListener('focusin', handleFocusIn)
    container.addEventListener('focusout', handleFocusOut)

    return () => {
      container.removeEventListener('focusin', handleFocusIn)
      container.removeEventListener('focusout', handleFocusOut)
    }
  }, [clearFocusTimer, containerRef])

  // 区域内 React 键盘事件处理器：挂载在 Composer 最外层容器上。
  // React 合成事件自下而上冒泡，子组件（如 Controls 下拉菜单）可优先拦截 Escape。
  const handleKeyDown = useCallback((event: ReactKeyboardEvent) => {
    if (disabled || !active) {
      return
    }
    if (event.key !== 'Escape') {
      return
    }
    if (
      event.defaultPrevented
      || event.nativeEvent.isComposing
      || event.nativeEvent.keyCode === 229
      || event.repeat
    ) {
      return
    }
    if (hasBlockingModal()) {
      return
    }
    event.preventDefault()
    event.stopPropagation()
    const handled = closeOverlayRef.current()
    if (handled) {
      focusComposer(true)
      return
    }
    blurComposer()
  }, [active, blurComposer, disabled, focusComposer])

  // interaction panel 接管（active=false）后清掉焦点恢复请求与定时器；重新
  // active 且可编辑时恢复焦点到编辑器末尾。
  useEffect(() => {
    if (!active) {
      restoreFocusRef.current = true
      clearFocusTimer()
      isFocusedRef.current = false
      setIsFocused(false)
      onLeaveRegionRef.current?.()
      return
    }
    if (restoreFocusRef.current && !disabled) {
      restoreFocusRef.current = false
      focusComposer(true)
    }
  }, [active, clearFocusTimer, disabled, focusComposer])

  // 全局 Escape：Modal/alertdialog/lightbox 保留自己的 Escape 语义；
  // 仅负责未聚焦且当前活动 pane 处于 focusOnEscape 时，恢复焦点到编辑器末尾。
  useEffect(() => {
    if (!focusOnEscape || !active || disabled) {
      return
    }
    const handleEscape = (event: globalThis.KeyboardEvent) => {
      if (
        event.key !== 'Escape'
        || event.defaultPrevented
        || event.isComposing
        || event.keyCode === 229
        || event.repeat
      ) {
        return
      }
      if (hasBlockingModal()) {
        return
      }
      if (editorRef.current?.getAttribute('contenteditable') !== 'true') {
        return
      }
      const container = containerRef.current
      if (container && document.activeElement && container.contains(document.activeElement)) {
        return
      }
      event.preventDefault()
      focusComposer(true)
    }
    window.addEventListener('keydown', handleEscape)
    return () => window.removeEventListener('keydown', handleEscape)
  }, [active, containerRef, disabled, editorRef, focusComposer, focusOnEscape])

  // 发送完成后（pending true -> false）：active 且可编辑时立即恢复键入焦点；若
  // interaction panel 仍接管（active=false），隐藏 editor 不得抢焦点，只保留
  // 恢复意图；active 重新打开且 enabled 时由 active effect 恢复一次。
  useEffect(() => {
    if (wasPendingRef.current && !pending) {
      if (!active) {
        restoreFocusRef.current = true
      } else if (!disabled) {
        focusComposer()
      }
    }
    wasPendingRef.current = pending
  }, [active, disabled, focusComposer, pending])

  // 卸载清理：取消尚未执行的延迟聚焦。
  useEffect(() => () => clearFocusTimer(), [clearFocusTimer])

  return { isFocused, focusComposer, blurComposer, handleKeyDown }
}
