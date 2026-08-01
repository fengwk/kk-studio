import { isPaneBound, type ChatPane, type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { BlankComposerPane } from '@/features/ai/chat/chat-workspace-pane/BlankComposerPane'
import { BoundThreadPane } from '@/features/ai/chat/chat-workspace-pane/BoundThreadPane'

/**
 * Scene dispatcher for a single chat-workspace pane.
 *
 * - Bound Thread: delegates to {@link BoundThreadPane} (controller-driven rebind + picker UX).
 * - Blank pane: delegates to {@link BlankComposerPane} (composer + first-send path).
 *
 * The dispatch is purely structural; any behavioral change belongs to the dedicated pane
 * modules so this file can stay a stable, easily-reviewable contract.
 */
export function ChatWorkspacePane({
  chat,
  agents,
  environments = [],
  pane,
  focused,
  sessionSort,
  threadSort,
  onFocus,
  onThreadChange,
  onSessionSortChange,
  onThreadSortChange,
  onDefaultAgentChange,
  onDefaultEnvironmentChange = async () => undefined,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  pane: ChatPane
  focused: boolean
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
  onDefaultEnvironmentChange?: (environmentName: string | null) => Promise<void>
}) {
  if (isPaneBound(pane.threadId)) {
    return (
      <BoundThreadPane
        chatId={chat?.id ?? ''}
        agents={agents}
        environments={environments}
        paneId={pane.id}
        threadId={pane.threadId}
        focused={focused}
        sessionSort={sessionSort}
        threadSort={threadSort}
        onFocus={onFocus}
        onThreadChange={onThreadChange}
        onSessionSortChange={onSessionSortChange}
        onThreadSortChange={onThreadSortChange}
      />
    )
  }

  return (
    <BlankComposerPane
      chat={chat}
      agents={agents}
      environments={environments}
      focused={focused}
      threadSort={threadSort}
      onFocus={onFocus}
      onThreadChange={onThreadChange}
      onThreadSortChange={onThreadSortChange}
      onDefaultAgentChange={onDefaultAgentChange}
      onDefaultEnvironmentChange={onDefaultEnvironmentChange}
    />
  )
}
