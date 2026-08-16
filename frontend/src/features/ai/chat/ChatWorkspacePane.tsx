import { useState } from 'react'
import { isPaneBound, type ChatPane, type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { EnvironmentBindingDTO, LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type { CommandBatchReplay } from '@/features/ai/runtime'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { FirstSendRecovery } from '@/features/ai/chat/chat-first-send'
import { BlankComposerPane } from '@/features/ai/chat/chat-workspace-pane/BlankComposerPane'
import { BoundThreadPane } from '@/features/ai/chat/chat-workspace-pane/BoundThreadPane'

/**
 * 单个 Chat 工作区面板的场景分发器。
 *
 * - 绑定 Thread：委托给 {@link BoundThreadPane}（snapshot draft + command-batch send）。
 * - 空面板：委托给 {@link BlankComposerPane}（frozen catalog draft + first-send path）。
 *
 * 分发纯粹是结构性的；任何行为变更都应放在专用面板模块中，以便此文件保持稳定且易于审查的契约。
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
  onEnvironmentChange = async () => undefined,
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
  onEnvironmentChange?: (environment: EnvironmentBindingDTO | null) => Promise<void>
}) {
  const [firstSendRecovery, setFirstSendRecovery] = useState<{
    threadId: string
    parts: ComposerPart[]
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
        environments={environments}
        paneId={pane.id}
        threadId={pane.threadId}
        focused={focused}
        threadSort={threadSort}
        onFocus={onFocus}
        onThreadChange={handleThreadChange}
        onThreadSortChange={onThreadSortChange}
        // 内容恢复与 replay identity 相互独立：已知 409 会恢复 draft，但不会恢复 replay，
        // 因此下一次提交会重新构建最新 cursor 和 command id。
        initialDraft={
          firstSendRecovery?.threadId === pane.threadId
            ? firstSendRecovery.parts
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
      paneId={pane.id}
      focused={focused}
      threadSort={threadSort}
      onFocus={onFocus}
      onThreadChange={handleThreadChange}
      onThreadSortChange={onThreadSortChange}
      onAgentChange={onAgentChange}
      onYoloChange={onYoloChange}
      onEnvironmentChange={onEnvironmentChange}
      onFirstSendRecovery={recoverFirstSend}
    />
  )
}
