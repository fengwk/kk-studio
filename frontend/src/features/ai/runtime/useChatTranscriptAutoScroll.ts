import { useEffect, useRef, type RefObject } from 'react'

/**
 * 贴底自动滚动阈值：约 2 次常见桌面滚轮行程（deltaY≈100）。
 * 用户上滑超过该距离后停止跟随；回到阈值内再恢复。
 */
export const CHAT_STICK_TO_BOTTOM_THRESHOLD_PX = 210

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
 */
export function useChatTranscriptAutoScroll(
  bodyRef: RefObject<HTMLDivElement | null>,
  messageCount: number,
  eventCount?: number,
) {
  const stickToBottomRef = useRef(true)

  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody) {
      return
    }

    const onScroll = () => {
      stickToBottomRef.current = isNearBottom(chatBody)
    }

    // 初始化时贴底，并同步一次 stick 状态。
    stickToBottomRef.current = true
    scrollToBottom(chatBody)
    chatBody.addEventListener('scroll', onScroll, { passive: true })
    return () => chatBody.removeEventListener('scroll', onScroll)
  }, [bodyRef])

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
