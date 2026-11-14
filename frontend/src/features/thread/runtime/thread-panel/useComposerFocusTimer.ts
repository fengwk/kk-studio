import { useCallback, useRef, type RefObject } from 'react'
import { isEditorComposing, placeCaretAtEnd } from '@/features/thread/composer/composer-dom'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'

const MAX_FOCUS_ATTEMPTS = 5
const FOCUS_RETRY_DELAY_MS = 16

/**
 * Composer 的延迟聚焦定时器与重试。
 *
 * 聚焦必须延后到一次定时器之后：调用点常常刚触发渲染或刚关闭覆盖层，此时编辑器
 * 或许还没有 contenteditable=true。就绪之前最多重试 {@link MAX_FOCUS_ATTEMPTS} 次，
 * 每次间隔一个宏任务，避免无限轮询。modal/组合态下直接放弃——焦点归调用方或输入法。
 *
 * 定时器句柄由本 hook 独占持有；区域状态（isFocused）通过回调交还给调用方。
 */
export function useComposerFocusTimer({
  editorRef,
  activeRef,
  onFocused,
}: {
  editorRef: RefObject<HTMLDivElement | null>
  /** 组合 active 状态：面板被覆盖时不抢焦点。 */
  activeRef: RefObject<boolean>
  /** 编辑器真的拿到焦点时回调（同步区域焦点状态）。 */
  onFocused: () => void
}) {
  const focusTimerRef = useRef<number | null>(null)

  const clearFocusTimer = useCallback(() => {
    if (focusTimerRef.current !== null) {
      window.clearTimeout(focusTimerRef.current)
      focusTimerRef.current = null
    }
  }, [])

  /**
   * 请求聚焦（带定时重试）。forceCaretAtEnd 用于光标归位到末尾；
   * 编辑器内交互直接调用默认值。
   */
  const focusComposer = useCallback((forceCaretAtEnd = false) => {
    clearFocusTimer()
    const scheduleFocus = (attempt: number, delay: number) => {
      focusTimerRef.current = window.setTimeout(() => {
        focusTimerRef.current = null
        const el = editorRef.current
        if (!el || !activeRef.current) {
          return
        }
        if (shouldDeferToBlockingModal(el)) {
          return
        }
        if (el.getAttribute('contenteditable') !== 'true') {
          if (attempt < MAX_FOCUS_ATTEMPTS) {
            scheduleFocus(attempt + 1, FOCUS_RETRY_DELAY_MS)
          }
          return
        }
        if (isEditorComposing(el)) {
          // 组合期间不强制 focus、不移动光标：组合态归输入法所有，
          // 外部改动与焦点恢复统一等到 compositionend 之后。
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
          onFocused()
        } else if (attempt < MAX_FOCUS_ATTEMPTS) {
          scheduleFocus(attempt + 1, FOCUS_RETRY_DELAY_MS)
        }
      }, delay)
    }
    scheduleFocus(0, 0)
  }, [activeRef, clearFocusTimer, editorRef, onFocused])

  return { focusTimerRef, clearFocusTimer, focusComposer }
}
