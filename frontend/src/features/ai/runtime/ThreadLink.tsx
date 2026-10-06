import { createContext, useContext } from 'react'
import { Link, type LinkProps } from 'react-router'

/** 普通点击交给当前 pane；没有 pane 导航时保留独立 Thread 地址。 */
export const ThreadNavigationContext = createContext<((threadId: string) => void) | null>(null)

export function ThreadLink({
  threadId,
  children,
  target,
  ...props
}: Omit<LinkProps, 'to' | 'onClick'> & { threadId: string }) {
  const openThread = useContext(ThreadNavigationContext)
  return (
    <Link
      {...props}
      to={`/threads/${encodeURIComponent(threadId)}`}
      target={target}
      onClick={(event) => {
        if (
          !openThread
          || event.defaultPrevented
          || event.button !== 0
          || event.metaKey
          || event.ctrlKey
          || event.shiftKey
          || event.altKey
          || (target != null && target !== '_self')
        ) {
          return
        }
        event.preventDefault()
        openThread(threadId)
      }}
    >
      {children}
    </Link>
  )
}
