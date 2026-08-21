import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { ChatWorkspacePage } from '@/features/ai/chat/ChatWorkspacePage'

/** Chat 工作区路由；连同 Thread/Markdown 运行时按路由加载。 */
export default function ChatWorkspaceRoute({ children }: ExtensionComponentProps) {
  return (
    <>
      <ChatWorkspacePage />
      {children}
    </>
  )
}
