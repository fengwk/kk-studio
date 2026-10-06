import { useContext } from 'react'
import { Link, type LinkProps } from 'react-router'
import { ThreadNavigationContext } from '@/features/ai/runtime/thread-navigation-context'

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
