import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ThreadComposer,
  ThreadStatusFooter,
} from '@/features/ai/thread-panel'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import { BLANK_PANE_COMMANDS } from '@/features/ai/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat-workspace-pane/pane-errors'
import { sortWithRunningFirst, type PaneSortPreference } from '@/features/ai/chat-pane-state'
import {
  isRunningThread,
  toThreadSelectionItem,
} from '@/features/ai/chat-session-picker'
import { performBlankPaneFirstSend } from '@/features/ai/chat-first-send'
import { modelRef, toAgentModelViews, type AgentModelView } from '@/features/ai/AgentModelView'
import type { AgentDefinitionDTO, ChatDTO } from '@/shared/api/contracts'
import { extractContextWindow } from '@/features/ai/ai-model-draft-codec'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { AgentSelectionModal, SelectionListModal } from '@/features/ai/SelectionListModal'

function resolveDefaultAgent(
  chat: ChatDTO | undefined,
  agents: AgentDefinitionDTO[],
): AgentDefinitionDTO | undefined {
  if (!chat?.defaultAgentId) {
    return undefined
  }
  return agents.find((agent) => String(agent.id) === String(chat.defaultAgentId))
}

/** 空白 pane 尚无 Thread 时，用 Chat 默认 Agent 的 model/variant 填 footer（与已绑定 pane 一致） */
function resolveBlankPaneFooterLabels(
  agent: AgentDefinitionDTO | undefined,
  models: AgentModelView[],
) {
  if (!agent) {
    return {
      agentName: '（无 Agent）',
      providerName: undefined as string | undefined,
      modelName: undefined as string | undefined,
      variantName: undefined as string | undefined,
      contextWindow: undefined as number | undefined,
    }
  }
  const modelId = agent.modelId ? String(agent.modelId) : ''
  const model = models.find((item) => String(item.id) === modelId)
  return {
    agentName: agent.name || '（无 Agent）',
    providerName: model?.providerName || undefined,
    modelName: model ? modelRef(model) : modelId || undefined,
    variantName: agent.variant || undefined,
    contextWindow: extractContextWindow(model),
  }
}

export function BlankComposerPane({
  chat,
  agents,
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  onDefaultAgentChange,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onDefaultAgentChange: (agentId: string) => Promise<void>
}) {
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [pendingContent, setPendingContent] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const threadsQuery = useQuery({
    queryKey: queryKeys.threads.list,
    queryFn: () => harnessService.listThreads(),
    enabled: threadModalOpen,
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
  })
  const providers = providersQuery.data?.results ?? []
  const models: AgentModelView[] = toAgentModelViews(
    modelsQuery.data?.results ?? [],
    providers,
  )
  const defaultAgent = resolveDefaultAgent(chat, agents)
  const footerLabels = resolveBlankPaneFooterLabels(defaultAgent, models)
  const agentLabel =
    defaultAgent?.name || (chat?.defaultAgentId ? '（Agent 已删除/缺失）' : '（无 Agent）')

  async function runFirstSend(agentId: string, content: string) {
    setPending(true)
    setActionError(null)
    try {
      const result = await performBlankPaneFirstSend({
        agentDefinitionId: agentId,
        content,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(result.sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(result.thread.threadId) }),
      ])
      setDraft('')
      setPendingContent(null)
      onThreadChange(result.thread.threadId)
    } catch (error) {
      setActionError(errorMessage(error, '首发失败'))
      setDraft(content)
    } finally {
      setPending(false)
    }
  }

  async function handleSubmit() {
    const content = draft.trim()
    if (!content || content.startsWith('/') || pending) {
      return
    }
    onFocus()
    if (!defaultAgent) {
      setPendingContent(content)
      setAgentModalOpen(true)
      return
    }
    await runFirstSend(String(defaultAgent.id), content)
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'thread':
        setThreadModalOpen(true)
        return
      case 'agent':
        setAgentModalOpen(true)
        return
      default:
        setActionError(command.disabledReason || `当前场景不可用：/${command.id}`)
    }
  }

  async function handleAgentSelected(agentId: string) {
    setAgentModalOpen(false)
    const content = pendingContent?.trim()
    setActionError(null)
    try {
      await onDefaultAgentChange(agentId)
      if (content) {
        await runFirstSend(agentId, content)
      }
    } catch (error) {
      setActionError(errorMessage(error, '更新默认 Agent 失败'))
      if (content) {
        setDraft(content)
      }
    }
  }

  return (
    <section className={`chat-pane ${focused ? 'focused' : ''}`} onMouseDown={onFocus}>
      {/* 与有 Thread 时同一套 shell / composer 结构，避免 blank 与 thread 输入框样式分叉 */}
      <section className="chat-shell thread-panel blank-pane">
        <main className="chat-main thread-panel-main">
          <div className="blank-pane-body">
            <h2>新对话</h2>
            <p>输入后创建并 bootstrap 新 Thread；/agent 选默认 Agent，/thread 复用已有 Thread。</p>
            {actionError ? <div className="thread-error-panel">{actionError}</div> : null}
          </div>
          <ThreadComposer
            draft={draft}
            pending={pending}
            disabled={pending}
            onDraftChange={setDraft}
            onSubmit={() => {
              void handleSubmit()
            }}
            onCommand={handleCommand}
            commands={BLANK_PANE_COMMANDS}
          />
          <ThreadStatusFooter
            agentName={defaultAgent ? footerLabels.agentName : agentLabel}
            providerName={defaultAgent ? footerLabels.providerName : undefined}
            modelName={defaultAgent ? footerLabels.modelName : undefined}
            variantName={defaultAgent ? footerLabels.variantName : undefined}
            contextWindow={defaultAgent ? footerLabels.contextWindow : undefined}
            yoloEnabled={false}
            onAgentClick={() => {
              onFocus()
              setAgentModalOpen(true)
            }}
          />
        </main>
      </section>
      <AgentSelectionModal
        open={agentModalOpen}
        agents={agents.map((agent) => ({
          id: String(agent.id),
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => {
          setAgentModalOpen(false)
          setPendingContent(null)
        }}
        onSelect={(agentId) => {
          void handleAgentSelected(agentId)
        }}
      />
      {/* /thread only rebinds the pane; no Thread is mutated. */}
      <SelectionListModal
        open={threadModalOpen}
        title="选择 Thread"
        items={sortWithRunningFirst(threadsQuery.data ?? [], threadSort, isRunningThread).map(
          toThreadSelectionItem,
        )}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        emptyText="暂无 Thread"
        onClose={() => setThreadModalOpen(false)}
        onSelect={(selectedThreadId) => {
          setThreadModalOpen(false)
          onThreadChange(selectedThreadId)
        }}
      />
    </section>
  )
}
