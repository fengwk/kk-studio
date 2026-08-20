import { AgentPane } from '@/features/ai/runtime/AgentPane'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'

/** Chat 只提供 owner/defaults；发送、导航、命令和 Thread projection 全部由 AgentPane 共享。 */
export function ChatWorkspacePane({
  chat,
  agents,
  environments = [],
  pane,
  focused,
  onFocus,
}: {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  pane: { id: string }
  focused: boolean
  onFocus: () => void
}) {
  return (
    <AgentPane
      owner={{ type: 'CHAT', id: chat.id }}
      paneId={pane.id}
      agents={agents}
      environments={environments}
      defaults={{
        agentName: chat.agentName,
        environment: chat.environment,
        yoloEnabled: chat.yoloEnabled,
      }}
      focused={focused}
      onFocus={onFocus}
    />
  )
}
