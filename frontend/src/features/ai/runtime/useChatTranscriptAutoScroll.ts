import { useEffect, useRef, type RefObject } from 'react'
import { TRANSCRIPT_READING_INTENT_EVENT } from '@/features/ai/runtime/transcript-reading'

/**
 * 重新贴底阈值：约 2 次常见桌面滚轮行程（deltaY≈100）。
 * 用户向历史方向滚动时立即停止跟随；主动滚回该阈值内再恢复。
 */
const CHAT_RESTICK_TO_BOTTOM_THRESHOLD_PX = 210
const CHAT_BOTTOM_EPSILON_PX = 1

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

function isNearBottom(element: HTMLElement, thresholdPx = CHAT_RESTICK_TO_BOTTOM_THRESHOLD_PX): boolean {
  return distanceFromBottom(element) <= thresholdPx
}

function scrollToBottom(element: HTMLElement) {
  element.scrollTop = element.scrollHeight
}

function growthSignature(messageCount: number, eventCount?: number): string {
  return `${eventCount ?? 0}:${messageCount}`
}

/** 布局后调度：浏览器在下一帧（布局已完成）执行；无 rAF 的环境（jsdom）同步执行。 */
function afterLayout(callback: () => void): () => void {
  if (typeof requestAnimationFrame !== 'function') {
    callback()
    return () => {}
  }
  const frame = requestAnimationFrame(callback)
  return () => cancelAnimationFrame(frame)
}

/**
 * 对话流增长时自动贴底；用户向历史方向滚动后停止跟随，主动滚回底部阈值内
 * 才恢复跟随，避免打断回看历史。
 *
 * `initialScrollTop`：非空时挂载即恢复该位置（不再贴底），并让 stick 状态跟随
 * 恢复后的位置——恢复位置仍在 210px 阈值内时，后续内容增长继续贴底；
 * 超过阈值则保持不跟随（挂载时的初始定位由本 hook 完成，内容计数与流式
 * revision 的变化只对挂载之后的实际变化生效）。
 *
 * `resetKey`：变化时重新贴底并重置 stick（Thread 重绑后新线程首次进入必须贴底，
 * 不能沿用旧线程恢复位置时留下的 stick=false）。
 *
 * `streamRevision`：流式正文的显式更新签名（见 transcript-reading.ts）。只有它变化
 * 才贴底——媒体加载完成、图片解码、展开/收起等布局尺寸变化都不是流式信号，
 * 绝不触发滚动。
 *
 * 暂停跟随只有三条来源：外层向历史滚动、用户输入、子只读区域冒泡阅读意图
 * （`TRANSCRIPT_READING_INTENT_EVENT`）；恢复只有一条路径——用户用明确的滚动意图
 * （滚轮 / 滚动键 / 触控 / 滚动条拖拽）把 transcript 自己滚回贴底阈值内。
 * 布局尺寸变化、程序化定位（包括布局收缩时浏览器把 scrollTop 钳制到底部）
 * 既不重开跟随，也不改变跟随态。
 */
export function useChatTranscriptAutoScroll(
  bodyRef: RefObject<HTMLDivElement | null>,
  messageCount: number,
  eventCount?: number,
  initialScrollTop?: number | null,
  resetKey?: string | number | null,
  streamRevision: string | null = null,
) {
  const stickToBottomRef = useRef(true)
  const lastScrollTopRef = useRef(0)
  // 明确的用户滚动意图：只有它允许「恢复跟随」。布局收缩导致的原生 scrollTop
  // 钳制、子区域阅读意图、程序化定位都不设置它。
  const userScrollIntentRef = useRef(false)
  // 已处理的增长签名：挂载/重绑时由初始定位 effect 记录，增长 effect 只对
  // 之后的计数变化生效，绝不把挂载时恢复的历史位置拉回底部。
  const lastGrowthSignatureRef = useRef<string | null>(null)
  // 待执行的贴底帧：阅读意图会直接取消它，绝不留到下一帧再贴底。
  const cancelPendingScrollRef = useRef<(() => void) | null>(null)

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
      const movedTowardHistory = scrollTop < lastScrollTopRef.current
      const reachedBottom = distanceFromBottom(chatBody) <= CHAT_BOTTOM_EPSILON_PX
      const movedTowardBottom = scrollTop > lastScrollTopRef.current
      if (movedTowardHistory) {
        // 向上移动：回看历史，立即暂停跟随。
        if (!reachedBottom) {
          stickToBottomRef.current = false
        }
        // 位置变小却仍然到底：只可能是布局收缩时浏览器把 scrollTop 钳制到新的底部
        // （卡片收起、图片尺寸变动）。这不是用户手势，既不重开跟随也不改变跟随态。
        userScrollIntentRef.current = false
      } else if (
        userScrollIntentRef.current
        && (reachedBottom || (movedTowardBottom && isNearBottom(chatBody)))
      ) {
        // 只有明确的用户滚动意图回到贴底阈值内才恢复跟随。
        stickToBottomRef.current = true
        userScrollIntentRef.current = false
      }
      lastScrollTopRef.current = scrollTop
    }

    // 明确的用户滚动意图接线：滚轮、滚动键、触控滚动，以及抓住原生滚动条拖动。
    // 键盘滚动只在容器内（或其后代）按键时才算，避免全局快捷键误判。
    const markUserScrollIntent = () => {
      userScrollIntentRef.current = true
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (USER_SCROLL_KEYS.has(event.key)) {
        markUserScrollIntent()
      }
    }
    const onPointerDown = (event: PointerEvent) => {
      if (isScrollbarPointer(chatBody, event)) {
        markUserScrollIntent()
      }
    }

    // 首次进入（无保存位置）贴底；恢复历史位置时 stick 状态跟随该位置。
    // 这里同步读取一次 scrollHeight，强制得到已经确定的布局。
    if (initialScrollTop != null) {
      chatBody.scrollTop = initialScrollTop
      stickToBottomRef.current = isNearBottom(chatBody)
    } else {
      scrollToBottom(chatBody)
    }
    lastScrollTopRef.current = chatBody.scrollTop
    userScrollIntentRef.current = false
    chatBody.addEventListener('scroll', onScroll, { passive: true })
    chatBody.addEventListener('wheel', markUserScrollIntent, { passive: true })
    chatBody.addEventListener('touchstart', markUserScrollIntent, { passive: true })
    chatBody.addEventListener('touchmove', markUserScrollIntent, { passive: true })
    chatBody.addEventListener('keydown', onKeyDown)
    chatBody.addEventListener('pointerdown', onPointerDown)
    // 子只读区域的回看/交互意图：暂停跟随并撤销待执行的贴底帧；恢复只有
    // 「用户自己用滚动意图把 transcript 滚回底部」一条路径。
    const onReadingIntent = () => {
      stickToBottomRef.current = false
      // 用户正在读内容：作废尚未兑现的滚动意图，避免它稍后重开跟随。
      userScrollIntentRef.current = false
      cancelPendingScrollRef.current?.()
      cancelPendingScrollRef.current = null
    }
    chatBody.addEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    // 初始定位由本 effect 完成；增长 effect 从下次计数变化开始生效。
    lastGrowthSignatureRef.current = growthSignature(messageCount, eventCount)
    return () => {
      chatBody.removeEventListener('scroll', onScroll)
      chatBody.removeEventListener('wheel', markUserScrollIntent)
      chatBody.removeEventListener('touchstart', markUserScrollIntent)
      chatBody.removeEventListener('touchmove', markUserScrollIntent)
      chatBody.removeEventListener('keydown', onKeyDown)
      chatBody.removeEventListener('pointerdown', onPointerDown)
      chatBody.removeEventListener(TRANSCRIPT_READING_INTENT_EVENT, onReadingIntent)
    }
    // 初始定位只发生在挂载/重绑；计数变化由增长 effect 单独处理，
    // 重跑本 effect 会把恢复的历史位置覆盖掉。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bodyRef, initialScrollTop, resetKey])

  /**
   * 显式信号触发的贴底：调度到布局完成后的下一帧执行，写入前再次确认用户仍在跟随
   * （同一帧内的外层上滚或阅读意图会阻止它执行）。
   */
  const scheduleScrollToBottom = (chatBody: HTMLDivElement): (() => void) => {
    cancelPendingScrollRef.current?.()
    const cancel = afterLayout(() => {
      cancelPendingScrollRef.current = null
      if (!stickToBottomRef.current || bodyRef.current !== chatBody) {
        return
      }
      scrollToBottom(chatBody)
      lastScrollTopRef.current = chatBody.scrollTop
    })
    cancelPendingScrollRef.current = cancel
    return cancel
  }

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
    return scheduleScrollToBottom(chatBody)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bodyRef, eventCount, messageCount])

  // 流式正文更新：只有显式 stream revision 变化才贴底。模型/工具文本更新是唯一
  // 信号；媒体加载、图片解码、懒渲染与用户交互都不参与。
  useEffect(() => {
    const chatBody = bodyRef.current
    if (!chatBody || streamRevision == null || !stickToBottomRef.current) {
      return
    }
    return scheduleScrollToBottom(chatBody)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bodyRef, streamRevision])
}
