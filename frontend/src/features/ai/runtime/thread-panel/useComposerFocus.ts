import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  type RefObject,
} from 'react'
import { placeCaretAtEnd } from '@/features/ai/composer/composer-dom'
import { hasBlockingModal } from '@/shared/ui/blocking-overlay'

const MAX_FOCUS_ATTEMPTS = 5
const FOCUS_RETRY_DELAY_MS = 16

export interface ComposerFocusOptions {
  /** 编辑器根节点（contenteditable=true）。焦点定时器重试期间节点可能尚未可编辑。 */
  editorRef: RefObject<HTMLDivElement | null>
  /** disabled（不可编辑）期间不调度/不恢复焦点；active 恢复会推迟到重新 enabled。 */
  disabled: boolean
  /** false 时由同一 Composer 区域的 interaction panel 接管；组件保持挂载以保留上传状态。 */
  active: boolean
  /** 当前交互作用域是否允许全局 Escape 把焦点恢复到此 Composer。 */
  focusOnEscape: boolean
  /** 提交在途标记：true -> false 且可编辑时自动恢复键入焦点。 */
  pending: boolean
  /**
   * 全局 Escape 命中且编辑器可编辑时关闭当前覆盖层（palette/control menu）；
   * 无覆盖层时为 no-op。hook 随后统一恢复焦点到编辑器末尾。
   */
  closeOverlay: () => void
}

export interface ComposerFocus {
  /**
   * 请求聚焦（带定时重试）。forceCaretAtEnd 用于焦点离开编辑器后（菜单关闭、
   * Escape、active 恢复）把光标归位到末尾；编辑器内交互直接调用默认值。
   */
  focusComposer: (forceCaretAtEnd?: boolean) => void
}

/**
 * Composer 焦点状态机：统一聚焦定时器与重试、覆盖层关闭后的焦点恢复、
 * active/focusOnEscape、pending 完成自动聚焦与卸载清理。
 */
export function useComposerFocus({
  editorRef,
  disabled,
  active,
  focusOnEscape,
  pending,
  closeOverlay,
}: ComposerFocusOptions): ComposerFocus {
  const focusTimerRef = useRef<number | null>(null)
  const restoreFocusRef = useRef(false)
  const wasPendingRef = useRef(false)
  const closeOverlayRef = useRef(closeOverlay)
  // 每次 render 后在 layout phase 同步最新 closeOverlay（菜单/草稿语义）：
  // layout effect 在浏览器可派发任何事件之前、以及被动 Escape 监听器注册之前
  // 同步刷新，不存在「render 之后仍用旧闭包」的可观察窗口。
  useLayoutEffect(() => {
    closeOverlayRef.current = closeOverlay
  }, [closeOverlay])

  const clearFocusTimer = useCallback(() => {
    if (focusTimerRef.current === null) {
      return
    }
    window.clearTimeout(focusTimerRef.current)
    focusTimerRef.current = null
  }, [])

  const focusComposer = useCallback((forceCaretAtEnd = false) => {
    clearFocusTimer()
    const scheduleFocus = (attempt: number, delay: number) => {
      focusTimerRef.current = window.setTimeout(() => {
        focusTimerRef.current = null
        tryFocus(attempt)
      }, delay)
    }
    const tryFocus = (attempt: number) => {
      const el = editorRef.current
      if (!el || el.getAttribute('contenteditable') !== 'true') {
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
      if (attempt < MAX_FOCUS_ATTEMPTS && document.activeElement !== el) {
        scheduleFocus(attempt + 1, FOCUS_RETRY_DELAY_MS)
      }
    }
    scheduleFocus(0, 0)
  }, [clearFocusTimer, editorRef])

  // interaction panel 接管（active=false）后清掉焦点恢复请求与定时器；重新
  // active 且可编辑时恢复焦点到编辑器末尾。
  useEffect(() => {
    if (!active) {
      restoreFocusRef.current = true
      clearFocusTimer()
      return
    }
    if (restoreFocusRef.current && !disabled) {
      restoreFocusRef.current = false
      focusComposer(true)
    }
  }, [active, clearFocusTimer, disabled, focusComposer])

  // 全局 Escape：Modal/alertdialog/lightbox 保留自己的 Escape 语义；关闭后再次
  // 按 Escape 才回到 Composer。命中且编辑器可编辑时统一关闭覆盖层并恢复焦点。
  useEffect(() => {
    if (!focusOnEscape || !active) {
      return
    }
    const handleEscape = (event: globalThis.KeyboardEvent) => {
      if (
        event.key !== 'Escape'
        || event.defaultPrevented
        || event.isComposing
        || event.keyCode === 229
      ) {
        return
      }
      if (hasBlockingModal()) {
        return
      }
      if (editorRef.current?.getAttribute('contenteditable') !== 'true') {
        return
      }
      event.preventDefault()
      closeOverlayRef.current()
      focusComposer(true)
    }
    window.addEventListener('keydown', handleEscape)
    return () => window.removeEventListener('keydown', handleEscape)
  }, [active, editorRef, focusComposer, focusOnEscape])

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

  return { focusComposer }
}
