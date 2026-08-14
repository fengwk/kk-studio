import { useEffect, useRef, type RefObject } from 'react'

/**
 * 贴底自动滚动阈值：约 2 次常见桌面滚轮行程（deltaY≈100）。
 * 用户上滑超过该距离后停止跟随；回到阈值内再恢复。
 */
const CHAT_STICK_TO_BOTTOM_THRESHOLD_PX = 210

function distanceFromBottom(element: HTMLElement): number {
  return element.scrollHeight - element.scrollTop - element.clientHeight
}

function isNearBottom(element: HTMLElement, thresholdPx = CHAT_STICK_TO_BOTTOM_THRESHOLD_PX): boolean {
  return distanceFromBottom(element) <= thresholdPx
}

function scrollToBottom(element: HTMLElement) {
  element.scrollTop = element.scrollHeight
}

/**
 * 对话流增长时自动贴底；仅当当前已接近底部（阈值内）才滚动，避免打断回看历史。
 *
 * `initialScrollTop`：非空时挂载即恢复该位置（不再贴底），并让 stick 状态跟随
 * 恢复后的位置（用于 Event 视图重新进入时恢复上次离开的位置）。
 *
 * `resetKey`：变化时重新贴底并重置 stick（Thread 重绑后新线程首次进入必须贴底，
 * 不能沿用旧线程恢复位置时留下的 stick=false）。
 */
export function useChatTranscriptAutoScroll(
  bodyRef: RefObject<HTMLDivElement | null>,
  messageCount: number,
  eventCount?: number,
  initialScrollTop?: number | null,
  resetKey?: string | number | null,
) {
  const stickToBottomRef = useRef(true)

  useEffect(() => {
    // resetKey 变化（Thread 重绑）时先重置 stick：即使容器此刻尚未挂载
    // （重绑发生在 events 视图内），随后的内容增长也会重新贴底。
    stickToBottomRef.current = true
    const chatBody = bodyRef.current
    if (!chatBody) {
      return
    }

    const onScroll = () => {
      stickToBottomRef.current = isNearBottom(chatBody)
    }

    // 首次进入（无保存位置）贴底；恢复历史位置时 stick 状态跟随该位置。
    if (initialScrollTop != null) {
      chatBody.scrollTop = initialScrollTop
      stickToBottomRef.current = isNearBottom(chatBody)
    } else {
      scrollToBottom(chatBody)
    }
    chatBody.addEventListener('scroll', onScroll, { passive: true })
    return () => chatBody.removeEventListener('scroll', onScroll)
  }, [bodyRef, initialScrollTop, resetKey])

  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody || !stickToBottomRef.current) {
      return
    }
    scrollToBottom(chatBody)
  }, [bodyRef, eventCount, messageCount])

  // 流式内容高度变化时，若仍 stick 则继续贴底（不依赖 message/event 计数）。
  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody || typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(() => {
      if (stickToBottomRef.current) {
        scrollToBottom(chatBody)
      }
    })
    observer.observe(chatBody)
    for (const child of Array.from(chatBody.children)) {
      observer.observe(child)
    }
    return () => observer.disconnect()
  }, [bodyRef, messageCount])
}
