import { useEffect, useRef, type RefObject } from 'react'

/**
 * 重新贴底阈值：约 2 次常见桌面滚轮行程（deltaY≈100）。
 * 用户向历史方向滚动时立即停止跟随；主动滚回该阈值内再恢复。
 */
const CHAT_RESTICK_TO_BOTTOM_THRESHOLD_PX = 210
const CHAT_BOTTOM_EPSILON_PX = 1

function distanceFromBottom(element: HTMLElement): number {
  return element.scrollHeight - element.scrollTop - element.clientHeight
}

function isNearBottom(element: HTMLElement, thresholdPx = CHAT_RESTICK_TO_BOTTOM_THRESHOLD_PX): boolean {
  return distanceFromBottom(element) <= thresholdPx
}

function scrollToBottom(element: HTMLElement) {
  element.scrollTop = element.scrollHeight
}

function entryHeight(entry: ResizeObserverEntry): number {
  const borderBox = Array.isArray(entry.borderBoxSize) ? entry.borderBoxSize[0] : undefined
  if (borderBox != null && typeof borderBox.blockSize === 'number') {
    return borderBox.blockSize
  }
  if (typeof entry.contentRect?.height === 'number') {
    return entry.contentRect.height
  }
  return entry.target.getBoundingClientRect().height
}

function growthSignature(messageCount: number, eventCount?: number): string {
  return `${eventCount ?? 0}:${messageCount}`
}

/**
 * 对话流增长时自动贴底；用户向历史方向滚动后停止跟随，主动滚回底部阈值内
 * 才恢复跟随，避免打断回看历史。
 *
 * `initialScrollTop`：非空时挂载即恢复该位置（不再贴底），并让 stick 状态跟随
 * 恢复后的位置——恢复位置仍在 210px 阈值内时，后续内容增长继续贴底；
 * 超过阈值则保持不跟随（挂载时的初始定位由本 hook 完成，内容计数与
 * ResizeObserver 的增长 effect 只对挂载之后的实际变化生效）。
 *
 * `resetKey`：变化时重新贴底并重置 stick（Thread 重绑后新线程首次进入必须贴底，
 * 不能沿用旧线程恢复位置时留下的 stick=false）。
 *
 * `streaming`：当前时间线是否仍在流式增长。只有流式增长才用 ResizeObserver 补贴底；
 * 空闲时的高度变化（手动展开工具卡、图片加载完成、思考区展开）不是新内容，绝不能
 * 抢走外层 scrollTop——用户正在看的卡片必须停在原位。流式转终态时本观察器直接解绑，
 * 终态本身不触发任何滚动。
 */
export function useChatTranscriptAutoScroll(
  bodyRef: RefObject<HTMLDivElement | null>,
  messageCount: number,
  eventCount?: number,
  initialScrollTop?: number | null,
  resetKey?: string | number | null,
  streaming = false,
) {
  const stickToBottomRef = useRef(true)
  const lastScrollTopRef = useRef(0)
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
      const scrollTop = chatBody.scrollTop
      const movedTowardHistory =
        scrollTop < lastScrollTopRef.current
      const movedTowardBottom =
        scrollTop > lastScrollTopRef.current
      const reachedBottom =
        distanceFromBottom(chatBody) <= CHAT_BOTTOM_EPSILON_PX
      if (movedTowardHistory && !reachedBottom) {
        stickToBottomRef.current = false
      } else if (reachedBottom || (movedTowardBottom && isNearBottom(chatBody))) {
        stickToBottomRef.current = true
      }
      lastScrollTopRef.current = scrollTop
    }

    // 首次进入（无保存位置）贴底；恢复历史位置时 stick 状态跟随该位置。
    if (initialScrollTop != null) {
      chatBody.scrollTop = initialScrollTop
      stickToBottomRef.current = isNearBottom(chatBody)
    } else {
      scrollToBottom(chatBody)
    }
    lastScrollTopRef.current = chatBody.scrollTop
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
    lastScrollTopRef.current = chatBody.scrollTop
  }, [bodyRef, eventCount, messageCount])

  // 流式内容高度变化时，若仍 stick 则继续贴底（不依赖 message/event 计数）。
  // 非流式（空闲）时不观察：此时的高度变化只可能来自用户交互（展开工具卡）或
  // 媒体加载完成，继续贴底会把用户正在看的卡片挤出视口。
  //
  // 尺寸基线在 observe 时记录：ResizeObserver 的首个回调只报告当时的尺寸，与基线
  // 比较才能识别「贴底之后布局才真正稳定」的变化（懒渲染回合、字体与媒体落地），
  // 同时不会把挂载时恢复的历史位置或用户回看位置拉回底部。
  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody || !streaming || typeof ResizeObserver === 'undefined') {
      return
    }
    const sizes = new Map<Element, number>()
    const record = (element: Element) => {
      sizes.set(element, element.getBoundingClientRect().height)
    }
    const observer = new ResizeObserver((entries) => {
      if (!stickToBottomRef.current) {
        return
      }
      const changed = entries.some((entry) => {
        const height = entryHeight(entry)
        const previous = sizes.get(entry.target)
        sizes.set(entry.target, height)
        return previous !== undefined && Math.abs(previous - height) > 0.5
      })
      if (changed) {
        scrollToBottom(chatBody)
        lastScrollTopRef.current = chatBody.scrollTop
      }
    })
    observer.observe(chatBody)
    record(chatBody)
    for (const child of Array.from(chatBody.children)) {
      observer.observe(child)
      record(child)
    }
    return () => observer.disconnect()
  }, [bodyRef, messageCount, streaming])
}
