import { AgentPane } from '@/features/ai/runtime/AgentPane'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type { PaneTarget } from '@/features/ai/runtime/agent-pane'

/** Chat 只提供 owner/defaults；发送、导航、命令和 Thread projection 全部由 AgentPane 共享。 */
export function ChatWorkspacePane({
  chat,
  agents,
  environments = [],
  pane,
  focused,
  onFocus,
  initialTarget,
  onTargetConsumed,
}: {
  chat: ChatDTO
  agents: AgentDefinitionDTO[]
  environments?: EnvironmentCardDTO[]
  pane: { id: string }
  focused: boolean
  onFocus: () => void
  initialTarget?: PaneTarget
  onTargetConsumed?: (target: PaneTarget) => void
}) {
  return (
    <AgentPane
      owner={{ type: 'CHAT', chatId: chat.id }}
      paneId={pane.id}
      agents={agents}
      environments={environments}
      defaults={{
        agentName: chat.agentName,
        yoloEnabled: chat.yoloEnabled,
      }}
      focused={focused}
      onFocus={onFocus}
      initialTarget={initialTarget}
      onTargetConsumed={onTargetConsumed}
    />
  )
}
