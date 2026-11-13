import { useContext } from 'react'
import { Link, type LinkProps } from 'react-router'
import {
  ThreadNavigationContext,
  type ThreadNavigationOptions,
} from '@/features/thread/runtime/thread-navigation-context'

/**
 * Thread 链接：普通点击交给当前 pane，修饰键/新标签页保留独立地址。
 *
 * <p>`afterEntryId` 只用于压缩执行视图：独立地址把它编码成查询参数，pane 内导航把它
 * 作为该层的读取游标，两条路径都只加载分支自己的 Entry。
 */
export function ThreadLink({
  threadId,
  afterEntryId,
  children,
  target,
  ...props
}: Omit<LinkProps, 'to' | 'onClick'> & { threadId: string; afterEntryId?: string }) {
  const openThread = useContext(ThreadNavigationContext)
  const options: ThreadNavigationOptions | undefined = afterEntryId ? { afterEntryId } : undefined
  const path = `/threads/${encodeURIComponent(threadId)}`
  const to = afterEntryId ? `${path}?afterEntryId=${encodeURIComponent(afterEntryId)}` : path
  return (
    <Link
      {...props}
      to={to}
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
        if (options != null) {
          openThread(threadId, options)
        } else {
          openThread(threadId)
        }
      }}
    >
      {children}
    </Link>
  )
}
