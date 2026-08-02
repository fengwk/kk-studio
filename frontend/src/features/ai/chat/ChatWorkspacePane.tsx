import { useState } from 'react'
import { isPaneBound, type ChatPane, type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { BlankComposerPane } from '@/features/ai/chat/chat-workspace-pane/BlankComposerPane'
import { BoundThreadPane } from '@/features/ai/chat/chat-workspace-pane/BoundThreadPane'
import type { FirstSendReplay } from '@/features/ai/chat/chat-first-send'

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
  settingsPending = false,
  isSettingsMutationLocked = () => false,
  focused,
  sessionSort,
  threadSort,
  onFocus,
  onThreadChange,
  onSessionSortChange,
  onThreadSortChange,
  onAgentChange,
  onEnvironmentChange = async () => undefined,
  onYoloChange = async () => undefined,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  pane: ChatPane
  settingsPending?: boolean
  isSettingsMutationLocked?: () => boolean
  focused: boolean
  sessionSort: PaneSortPreference
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onSessionSortChange: (sort: PaneSortPreference) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onAgentChange: (agentName: string) => Promise<void>
  onEnvironmentChange?: (environmentName: string | null) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
}) {
  const [firstSendReplay, setFirstSendReplay] = useState<FirstSendReplay | null>(null)

  function handleThreadChange(threadId: string | null) {
    setFirstSendReplay((current) => (
      current?.threadId === threadId ? current : null
    ))
    onThreadChange(threadId)
  }

  function recoverFirstSend(replay: FirstSendReplay) {
    setFirstSendReplay(replay)
    onThreadChange(replay.threadId)
  }

  if (isPaneBound(pane.threadId)) {
    return (
      <BoundThreadPane
        chatId={chat?.id ?? ''}
        agents={agents}
        environments={environments}
        agentName={chat?.agentName ?? ''}
        environmentName={chat?.environmentName ?? null}
        yoloEnabled={chat?.yoloEnabled ?? false}
        settingsPending={settingsPending}
        isSettingsMutationLocked={isSettingsMutationLocked}
        paneId={pane.id}
        threadId={pane.threadId}
        focused={focused}
        sessionSort={sessionSort}
        threadSort={threadSort}
        onFocus={onFocus}
        onThreadChange={handleThreadChange}
        onSessionSortChange={onSessionSortChange}
        onThreadSortChange={onThreadSortChange}
        onAgentChange={onAgentChange}
        onEnvironmentChange={onEnvironmentChange}
        onYoloChange={onYoloChange}
        initialReplay={
          firstSendReplay?.threadId === pane.threadId
            ? firstSendReplay
            : undefined
        }
        onReplayInitialized={() => setFirstSendReplay(null)}
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
      agentName={chat?.agentName ?? ''}
      environmentName={chat?.environmentName ?? null}
      yoloEnabled={chat?.yoloEnabled ?? false}
      settingsPending={settingsPending}
      isSettingsMutationLocked={isSettingsMutationLocked}
      onAgentChange={onAgentChange}
      onEnvironmentChange={onEnvironmentChange}
      onYoloChange={onYoloChange}
      onFirstSendRecovery={recoverFirstSend}
    />
  )
}
