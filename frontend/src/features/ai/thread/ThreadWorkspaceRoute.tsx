import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { ThreadWorkspacePage } from '@/features/ai/thread/ThreadWorkspacePage'

/** 独立 Thread 工作区路由；连同 Thread 运行时按路由加载。 */
export default function ThreadWorkspaceRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <ThreadWorkspacePage />
      {children}
    </>
  )
}
