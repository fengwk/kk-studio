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

function growthSignature(messageCount: number, eventCount?: number): string {
  return `${eventCount ?? 0}:${messageCount}`
}

/**
 * 对话流增长时自动贴底；仅当当前已接近底部（阈值内）才滚动，避免打断回看历史。
 *
 * `initialScrollTop`：非空时挂载即恢复该位置（不再贴底），并让 stick 状态跟随
 * 恢复后的位置——恢复位置仍在 210px 阈值内时，后续内容增长继续贴底；
 * 超过阈值则保持不跟随（挂载时的初始定位由本 hook 完成，内容计数与
 * ResizeObserver 的增长 effect 只对挂载之后的实际变化生效）。
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
  // 已处理的增长签名：挂载/重绑时由初始定位 effect 记录，增长 effect 只对
  // 之后的计数变化生效，绝不把挂载时恢复的历史位置拉回底部。
  const lastGrowthSignatureRef = useRef<string | null>(null)

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
    // 初始定位由本 effect 完成；增长 effect 从下次计数变化开始生效。
    lastGrowthSignatureRef.current = growthSignature(messageCount, eventCount)
    return () => chatBody.removeEventListener('scroll', onScroll)
    // 初始定位只发生在挂载/重绑；计数变化由增长 effect 单独处理，
    // 重跑本 effect 会把恢复的历史位置覆盖掉。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bodyRef, initialScrollTop, resetKey])

  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody || !stickToBottomRef.current) {
      return
    }
    const signature = growthSignature(messageCount, eventCount)
    if (lastGrowthSignatureRef.current === signature) {
      return
    }
    lastGrowthSignatureRef.current = signature
    scrollToBottom(chatBody)
  }, [bodyRef, eventCount, messageCount])

  // 流式内容高度变化时，若仍 stick 则继续贴底（不依赖 message/event 计数）。
  // 每个目标首次 observe 时的初始回调只报告当前尺寸（并非变化），跳过它，
  // 避免把挂载时恢复的历史位置拉回底部。
  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody || typeof ResizeObserver === 'undefined') {
      return
    }
    const initialCallbacks = new Set<Element>()
    const observer = new ResizeObserver((entries) => {
      if (!stickToBottomRef.current) {
        return
      }
      const changed = entries.some((entry) => {
        if (initialCallbacks.has(entry.target)) {
          return true
        }
        initialCallbacks.add(entry.target)
        return false
      })
      if (changed) {
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
