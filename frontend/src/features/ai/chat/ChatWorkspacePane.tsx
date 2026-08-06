import { useState } from 'react'
import { isPaneBound, type ChatPane, type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type { CommandBatchReplay } from '@/features/ai/runtime'
import type { FirstSendRecovery } from '@/features/ai/chat/chat-first-send'
import { BlankComposerPane } from '@/features/ai/chat/chat-workspace-pane/BlankComposerPane'
import { BoundThreadPane } from '@/features/ai/chat/chat-workspace-pane/BoundThreadPane'

/**
 * Scene dispatcher for a single chat-workspace pane.
 *
 * - Bound Thread: delegates to {@link BoundThreadPane} (snapshot draft + command-batch send).
 * - Blank pane: delegates to {@link BlankComposerPane} (frozen catalog draft + first-send path).
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
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  onAgentChange,
  onYoloChange = async () => undefined,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  pane: ChatPane
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onAgentChange: (agentName: string) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
}) {
  const [firstSendRecovery, setFirstSendRecovery] = useState<{
    threadId: string
    content: string
    replay?: CommandBatchReplay
  } | null>(null)

  function handleThreadChange(threadId: string | null) {
    setFirstSendRecovery((current) => (
      current?.threadId === threadId ? current : null
    ))
    onThreadChange(threadId)
  }

  function recoverFirstSend(threadId: string, recovery: FirstSendRecovery) {
    setFirstSendRecovery({ threadId, ...recovery })
    onThreadChange(threadId)
  }

  if (isPaneBound(pane.threadId)) {
    return (
      <BoundThreadPane
        chatId={chat?.id ?? ''}
        agents={agents}
        environments={environments}
        paneId={pane.id}
        threadId={pane.threadId}
        focused={focused}
        threadSort={threadSort}
        onFocus={onFocus}
        onThreadChange={handleThreadChange}
        onThreadSortChange={onThreadSortChange}
        // Text restoration and replay identity are independent: a known 409 restores the
        // draft WITHOUT a replay, so the next submit rebuilds fresh cursors + command ids.
        initialDraft={
          firstSendRecovery?.threadId === pane.threadId
            ? firstSendRecovery.content
            : undefined
        }
        initialReplay={
          firstSendRecovery?.threadId === pane.threadId
            ? firstSendRecovery.replay
            : undefined
        }
        onReplayInitialized={() => setFirstSendRecovery(null)}
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
      onThreadChange={handleThreadChange}
      onThreadSortChange={onThreadSortChange}
      onAgentChange={onAgentChange}
      onYoloChange={onYoloChange}
      onFirstSendRecovery={recoverFirstSend}
    />
  )
}
