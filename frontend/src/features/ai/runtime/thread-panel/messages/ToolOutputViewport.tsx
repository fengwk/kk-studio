import { useEffect, useRef, type ReactNode } from 'react'
import { announceTranscriptReading } from '@/features/ai/runtime/transcript-reading'

/**
 * 工具正文的唯一只读视口。
 *
 * - 有界高度 + 内部滚动：长内容不撑高外层 transcript；
 * - `overscroll-behavior: contain` 让内部回看不把外层卡片滚走；
 * - `followTail` 只对持续日志（bash 流式输出）开启：挂载与内容增长时跟到尾部，
 *   用户上滚立即暂停，自己滚回底部再恢复；默认 false，静态正文（结果 Text/JSON、
 *   write/edit/task 参数）一律从顶部读，内容增长也不强拉到底部；
 * - 用户上滚回看时冒泡阅读意图，通知 transcript 外层暂停自动贴底（只暂停，恢复由
 *   外层滚回底部决定），后续流式增长不会把正在看的卡片拉走；
 * - 键盘可聚焦，触屏原生滚动。
 */
export function ToolOutputViewport({
  children,
  followKey,
  followTail = false,
  className = '',
  ariaLabel,
}: {
  children: ReactNode
  /** 内容签名；持续日志跟随尾部时，变化即贴底。 */
  followKey: string
  /** 是否跟随尾部（只有 bash 持续日志开启）；静态正文从顶部读。 */
  followTail?: boolean
  className?: string
  ariaLabel?: string
}) {
  const elementRef = useRef<HTMLPreElement>(null)
  // 只有持续日志才跟随尾部；静态正文永不自动贴底。
  const followRef = useRef(followTail)
  const followTailRef = useRef(followTail)
  const lastScrollTopRef = useRef(0)

  // followTail 变化（持续日志开始/结束）时同步内层跟随状态：
  // 开始跟随尾部，结束时停止；静态正文永不自动贴底。
  useEffect(() => {
    followTailRef.current = followTail
    followRef.current = followTail
  }, [followTail])

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
      followRef.current = followTailRef.current && atBottom
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
    // 只有持续日志跟随尾部；静态正文的内容增长也不得强拉到底部。
    if (element == null || !followTail || !followRef.current) {
      return
    }
    element.scrollTop = element.scrollHeight
  }, [followKey, followTail])

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
