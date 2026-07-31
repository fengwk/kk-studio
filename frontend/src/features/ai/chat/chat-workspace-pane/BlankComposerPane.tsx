import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ThreadComposer,
  ThreadStatusFooter,
  type ThreadCommand,
} from '@/features/ai/runtime'
import { BLANK_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import { type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { toThreadSelectionItem } from '@/features/ai/chat/chat-session-picker'
import { performBlankPaneFirstSend } from '@/features/ai/chat/chat-first-send'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import {
  extractContextWindow,
  modelRef,
  toAgentModelViews,
  type AgentModelView,
} from '@/features/ai/catalog'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { AgentSelectionModal, SelectionListModal } from '@/features/ai/chat/SelectionListModal'

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
  const [threadAssociationPending, setThreadAssociationPending] = useState(false)
  const queryClient = useQueryClient()
  const threadPicker = useChatThreadPicker(chat?.id ?? '', threadModalOpen, threadSort)
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
        chatId: chat?.id ?? '',
        agentDefinitionId: agentId,
        content,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
        queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(result.sessionId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(result.thread.threadId) }),
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

  async function selectThread(selectedThreadId: string) {
    setThreadAssociationPending(true)
    try {
      if (threadPicker.scope === 'global' && chat?.id) {
        await chatService.associateThread(chat.id, selectedThreadId)
        await queryClient.invalidateQueries({ queryKey: queryKeys.threads.list })
      }
      setThreadModalOpen(false)
      onThreadChange(selectedThreadId)
    } catch (error) {
      setActionError(errorMessage(error, '关联 Thread 失败'))
    } finally {
      setThreadAssociationPending(false)
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
        items={threadPicker.items.map((thread) => toThreadSelectionItem(thread, threadSort))}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        scope={threadPicker.scope}
        onScopeChange={threadPicker.setScope}
        loading={threadPicker.isLoading}
        hasMore={threadPicker.hasNextPage}
        loadingMore={threadPicker.isFetchingNextPage}
        onLoadMore={() => {
          void threadPicker.loadMore()
        }}
        selectionPending={threadAssociationPending}
        emptyText="暂无 Thread"
        onClose={() => setThreadModalOpen(false)}
        onSelect={selectThread}
      />
    </section>
  )
}
