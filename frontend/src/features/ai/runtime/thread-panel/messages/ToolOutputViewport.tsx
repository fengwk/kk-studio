import { useLayoutEffect, useRef } from 'react'

/**
 * 完整保留 Tool 输出，只把默认视口限制为最后五行。
 *
 * 初次挂载与处于末尾时的 realtime 更新会跟随尾部；用户主动向上滚动后暂停自动跟随。
 */
export function ToolOutputViewport({ text, className = '' }: { text: string; className?: string }) {
  const elementRef = useRef<HTMLPreElement>(null)
  const followTailRef = useRef(true)

  useLayoutEffect(() => {
    const element = elementRef.current
    if (element && followTailRef.current) {
      element.scrollTop = element.scrollHeight
    }
  }, [text])

  return (
    <pre
      ref={elementRef}
      className={`thread-tool-pre thread-tool-output${className ? ` ${className}` : ''}`}
      tabIndex={0}
      onScroll={(event) => {
        const element = event.currentTarget
        followTailRef.current =
          element.scrollHeight - element.scrollTop - element.clientHeight <= 2
      }}
    >
      {text}
    </pre>
  )
}
