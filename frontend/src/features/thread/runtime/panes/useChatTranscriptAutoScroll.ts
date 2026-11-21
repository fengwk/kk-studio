import { useEffect, useRef, type RefObject } from 'react'

/**
 * 回底阈值（CSS px）：用户向历史方向滚动并离开该距离即停止跟随，主动滚回该距离内
 * 才恢复。不做成设置；程序定位、浏览器钳制与布局变化都不改跟随状态。
 */
const FOLLOW_THRESHOLD_PX = 32

/** 浏览器原生滚动键：键盘滚动同样属于明确的用户滚动意图。 */
const USER_SCROLL_KEYS = new Set([
  'ArrowUp',
  'ArrowDown',
  'PageUp',
  'PageDown',
  'Home',
  'End',
  ' ',
  'Spacebar',
])

/** 明确向历史方向滚动的键：按下即作废已排队的贴底帧，避免抵消正在进行的回看手势。 */
const TOWARD_HISTORY_KEYS = new Set(['ArrowUp', 'PageUp', 'Home'])

/**
 * 滚动条判定条带宽度。overlay 滚动条不占 clientWidth（经典滚动条占用宽度时用实际
 * 宽度），指针落在这条边缘条带里可能是抓住了原生滚动条拖动。
 */
const SCROLLBAR_EDGE_PX = 16

function distanceFromBottom(element: HTMLElement): number {
  return element.scrollHeight - element.scrollTop - element.clientHeight
}

/** 指针是否落在容器右侧/底部边缘条带（原生滚动条所在位置）。 */
function isScrollbarPointer(element: HTMLElement, event: PointerEvent): boolean {
  const rect = element.getBoundingClientRect()
  if (rect.width === 0 && rect.height === 0) {
    return false
  }
  const gutterX = Math.max(element.offsetWidth - element.clientWidth, SCROLLBAR_EDGE_PX)
  const gutterY = Math.max(element.offsetHeight - element.clientHeight, SCROLLBAR_EDGE_PX)
  return event.clientX - rect.left >= rect.width - gutterX
    || event.clientY - rect.top >= rect.height - gutterY
}

/**
 * 对话流跟随输出：一个布尔值（stickToBottomRef）决定是否贴底。
 *
 * - 跟随只由几何驱动：内容与视口的实际尺寸变化、内容增删都调度一次「布局后贴底」，
 *   不看消息数、事件数或流式 revision —— 那些不是「是否跟随」的依据。每帧最多安排
 *   一次定位，执行前再确认跟随，因此同一帧内的上滚可以作废已排队的贴底。
 * - 翻转跟随只认真实用户滚动：向历史方向离开阈值即停止跟随，用明确的滚动意图
 *   （滚轮 / 滚动键 / 触控 / 滚动条拖拽）带回阈值内才恢复。程序定位、布局收缩时
 *   浏览器把 scrollTop 钳制到底部、以及本 hook 自己的写入都不翻转。
 * - 不跟随时交由原生滚动锚定保留视口内的阅读位置（包括在上方补历史）。
 * - 用变化前的状态决定动作：变高之前跟随就滚到变高之后的底部，不重新计算
 *   「现在已经离底几百像素」。不使用平滑滚动。
 *
 * `initialScrollTop`：非空时挂载即恢复该位置（不再贴底），并按同一阈值初始化跟随
 * 状态——恢复位置仍在阈值内就继续跟随，超过阈值则保持不跟随。
 *
 * `resetKey`：变化时重置跟随并重新贴底（Thread 重绑后新线程首次进入必须贴底，
 * 不能沿用旧线程恢复位置时留下的 stick=false）。
 */
export function useChatTranscriptAutoScroll(
  bodyRef: RefObject<HTMLDivElement | null>,
  initialScrollTop: number | null = null,
  resetKey?: string | number | null,
) {
  const stickToBottomRef = useRef(true)
  const lastScrollTopRef = useRef(0)

  useEffect(() => {
    // resetKey 变化（Thread 重绑）时先重置跟随：即使容器此刻尚未挂载（重绑发生在
    // Event 视图内），随后的几何变化也会重新贴底。
    stickToBottomRef.current = true
    const chatBody = bodyRef.current
    if (!chatBody) {
      return
    }

    // Avoid leaving a gesture armed when a wheel/key event did not scroll this viewport.
    let intent: { direction: number; expiresAt: number } | null = null
    const setFollowing = (following: boolean) => {
      stickToBottomRef.current = following
      chatBody.dataset.following = String(following)
    }
    let frame: number | null = null
    const cancelPendingScroll = () => {
      if (frame == null) {
        return
      }
      cancelAnimationFrame(frame)
      frame = null
    }
    // 布局后定位：执行前再确认跟随，同一帧内的上滚可以作废它。
    const scrollToBottom = () => {
      frame = null
      if (!stickToBottomRef.current || bodyRef.current !== chatBody) {
        return
      }
      chatBody.scrollTop = chatBody.scrollHeight
      // 自身写入后同步基线：随后的 scroll 事件位置差为 0，不会被误读成用户手势。
      lastScrollTopRef.current = chatBody.scrollTop
    }
    const scheduleScrollToBottom = () => {
      if (frame == null && stickToBottomRef.current) {
        frame = requestAnimationFrame(scrollToBottom)
      }
    }

    const onScroll = () => {
      const scrollTop = chatBody.scrollTop
      const previous = lastScrollTopRef.current
      lastScrollTopRef.current = scrollTop
      if (scrollTop === previous) {
        return
      }
      const direction = Math.sign(scrollTop - previous)
      if (!intent || performance.now() > intent.expiresAt
        || (intent.direction !== 0 && intent.direction !== direction)) {
        return
      }
      const nearBottom = distanceFromBottom(chatBody) <= FOLLOW_THRESHOLD_PX
      if (direction < 0 && !nearBottom) {
        setFollowing(false)
        cancelPendingScroll()
      } else if (direction > 0 && nearBottom) {
        setFollowing(true)
      }
    }

    const markUserScrollIntent = (direction: number) => {
      intent = { direction, expiresAt: performance.now() + 250 }
      if (direction <= 0) {
        cancelPendingScroll()
      }
    }
    const onTouchMove = () => markUserScrollIntent(0)
    const onScrollEnd = () => { intent = null }
    const onWheel = (event: WheelEvent) => {
      if (event.deltaY !== 0) {
        markUserScrollIntent(Math.sign(event.deltaY))
      }
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (!USER_SCROLL_KEYS.has(event.key)) {
        return
      }
      const towardHistory = TOWARD_HISTORY_KEYS.has(event.key)
        || (event.shiftKey && (event.key === ' ' || event.key === 'Spacebar'))
      markUserScrollIntent(towardHistory ? -1 : 1)
    }
    const onPointerDown = (event: PointerEvent) => {
      if (isScrollbarPointer(chatBody, event)) {
        markUserScrollIntent(0)
      }
    }

    // 首次进入（无保存位置）贴底；恢复历史位置时跟随状态由该位置按阈值决定。
    // 这里同步读取一次 scrollHeight，强制得到已经确定的布局。
    if (initialScrollTop != null) {
      chatBody.scrollTop = initialScrollTop
      setFollowing(distanceFromBottom(chatBody) <= FOLLOW_THRESHOLD_PX)
    } else {
      setFollowing(true)
      chatBody.scrollTop = chatBody.scrollHeight
    }
    lastScrollTopRef.current = chatBody.scrollTop
    chatBody.addEventListener('scroll', onScroll, { passive: true })
    chatBody.addEventListener('wheel', onWheel, { passive: true })
    chatBody.addEventListener('touchmove', onTouchMove, { passive: true })
    chatBody.addEventListener('scrollend', onScrollEnd)
    chatBody.addEventListener('keydown', onKeyDown)
    chatBody.addEventListener('pointerdown', onPointerDown)

    // 几何信号：视口自身的尺寸变化（分屏、窗口缩放）与内容盒子的尺寸变化
    // （正文流式增长、卡片变高、媒体落地）都只表示「内容变了」，是否贴底由
    // 当前跟随状态决定。
    const resizeObserver = new ResizeObserver(scheduleScrollToBottom)
    const observedElements = new Set<Element>()
    const observe = (element: Element) => {
      if (observedElements.has(element)) {
        return
      }
      observedElements.add(element)
      resizeObserver.observe(element)
    }
    const syncObservedElements = () => {
      observe(chatBody)
      for (const child of Array.from(chatBody.children)) {
        observe(child)
      }
      for (const element of observedElements) {
        if (element === chatBody || chatBody.contains(element)) {
          continue
        }
        observedElements.delete(element)
        resizeObserver.unobserve(element)
      }
      scheduleScrollToBottom()
    }
    // 直接子节点增删（新事件行、加载/错误状态切换）不一定改变已观察盒子的尺寸，
    // 因此同时重新挂接观察并调度一次定位。
    const childListObserver = new MutationObserver(syncObservedElements)
    syncObservedElements()
    childListObserver.observe(chatBody, { childList: true })

    return () => {
      cancelPendingScroll()
      resizeObserver.disconnect()
      childListObserver.disconnect()
      observedElements.clear()
      chatBody.removeEventListener('scroll', onScroll)
      chatBody.removeEventListener('wheel', onWheel)
      chatBody.removeEventListener('touchmove', onTouchMove)
      chatBody.removeEventListener('scrollend', onScrollEnd)
      delete chatBody.dataset.following
      chatBody.removeEventListener('keydown', onKeyDown)
      chatBody.removeEventListener('pointerdown', onPointerDown)
    }
  }, [bodyRef, initialScrollTop, resetKey])
}
