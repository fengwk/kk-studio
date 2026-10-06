import { useEffect, useRef, type ReactNode } from 'react'
import { announceTranscriptReading } from '@/features/ai/runtime/transcript-reading'

/**
 * 工具静态正文的唯一只读视口。
 *
 * - 有界高度 + 内部滚动：长内容不撑高外层 transcript；
 * - `overscroll-behavior: contain` 让内部回看不把外层卡片滚走；
 * - 持续日志默认跟随底部，用户上滚立即暂停，回到底部再恢复；
 * - 用户上滚回看时冒泡阅读意图，通知 transcript 外层暂停自动贴底（只暂停，恢复由
 *   外层滚回底部决定），后续流式增长不会把正在看的卡片拉走；
 * - 键盘可聚焦，触屏原生滚动。
 */
export function ToolOutputViewport({
  children,
  followKey,
  className = '',
  ariaLabel,
}: {
  children: ReactNode
  /** 内容签名；变化时若仍在跟随则贴底。 */
  followKey: string
  className?: string
  ariaLabel?: string
}) {
  const elementRef = useRef<HTMLPreElement>(null)
  const followRef = useRef(true)
  const lastScrollTopRef = useRef(0)

  useEffect(() => {
    const element = elementRef.current
    if (!element) {
      return
    }
    lastScrollTopRef.current = element.scrollTop
    const onScroll = () => {
      const scrollTop = element.scrollTop
      const atBottom = element.scrollHeight - scrollTop - element.clientHeight <= 1
      const movedTowardHistory = scrollTop < lastScrollTopRef.current
      lastScrollTopRef.current = scrollTop
      followRef.current = atBottom
      // 只报告「回看」；内层滚回底部不代表外层可以重新贴底。
      if (movedTowardHistory && !atBottom) {
        announceTranscriptReading(element, 'tool-output')
      }
    }
    element.addEventListener('scroll', onScroll, { passive: true })
    return () => element.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    const element = elementRef.current
    if (element != null && followRef.current) {
      element.scrollTop = element.scrollHeight
    }
  }, [followKey])

  return (
    <pre
      ref={elementRef}
      className={['thread-tool-pre', 'thread-tool-output', className].filter(Boolean).join(' ')}
      tabIndex={0}
      aria-label={ariaLabel}
    >
      {children}
    </pre>
  )
}
