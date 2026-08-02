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
import {
  FirstSendMessageError,
  type FirstSendReplay,
  performBlankPaneFirstSend,
} from '@/features/ai/chat/chat-first-send'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import {
  extractContextWindow,
  modelRef,
  toAgentModelViews,
  type AgentModelView,
} from '@/features/ai/catalog'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { agentService } from '@/shared/api/agent-service'
import { chatService } from '@/shared/api/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import {
  AgentSelectionModal,
  EnvironmentSelectionModal,
  SelectionListModal,
} from '@/features/ai/chat/SelectionListModal'
import { translate, useI18n } from '@/shared/i18n'

function resolveChatAgent(
  chat: ChatDTO | undefined,
  agents: AgentDefinitionDTO[],
): AgentDefinitionDTO | undefined {
  if (!chat?.agentName) {
    return undefined
  }
  return agents.find((agent) => agent.name === chat.agentName)
}

/** 空白 pane 尚无 Thread 时，用当前可见 Chat Agent 的 model/variant 填 footer。 */
function resolveBlankPaneFooterLabels(
  agent: AgentDefinitionDTO | undefined,
  models: AgentModelView[],
) {
  if (!agent) {
    return {
      agentName: translate('ai.runtime.action.blankAgent'),
      providerName: undefined as string | undefined,
      modelName: undefined as string | undefined,
      variantName: undefined as string | undefined,
      contextWindow: undefined as number | undefined,
    }
  }
  const model = models.find((item) => modelRef(item) === agent.model)
  return {
    agentName: agent.name || translate('ai.runtime.action.blankAgent'),
    providerName: model?.providerName || undefined,
    modelName: model ? modelRef(model) : agent.model || undefined,
    variantName: agent.variant || undefined,
    contextWindow: extractContextWindow(model),
  }
}

export function BlankComposerPane({
  chat,
  agents,
  environments = [],
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  agentName,
  environmentName,
  yoloEnabled,
  settingsPending,
  onAgentChange,
  onEnvironmentChange = async () => undefined,
  onYoloChange = async () => undefined,
  onFirstSendRecovery,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  settingsPending: boolean
  onAgentChange: (agentName: string) => Promise<void>
  onEnvironmentChange?: (environmentName: string | null) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
  onFirstSendRecovery: (replay: FirstSendReplay) => void
}) {
  const { t } = useI18n()
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [pendingContent, setPendingContent] = useState<string | null>(null)
  const [threadAssociationPending, setThreadAssociationPending] = useState(false)
  const queryClient = useQueryClient()
  const threadPicker = useChatThreadPicker(chat?.id ?? '', threadModalOpen, threadSort)
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models: AgentModelView[] = toAgentModelViews(modelsQuery.data?.results ?? [])
  const chatAgent = resolveChatAgent(chat, agents)
  const footerLabels = resolveBlankPaneFooterLabels(chatAgent, models)
  const agentLabel =
    chatAgent?.name
    || (chat?.agentName
      ? t('ai.runtime.action.agentMissing')
      : t('ai.runtime.action.blankAgent'))

  async function runFirstSend(content: string, effectiveAgentName = agentName) {
    setPending(true)
    setActionError(null)
    try {
      const result = await performBlankPaneFirstSend({
        chatId: chat?.id ?? '',
        content,
        agentName: effectiveAgentName,
        environmentName,
        yoloEnabled,
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
      if (error instanceof FirstSendMessageError) {
        onFirstSendRecovery(error.replay)
        return
      }
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
      setDraft(content)
    } finally {
      setPending(false)
    }
  }

  async function handleSubmit() {
    const content = draft.trim()
    if (!content || content.startsWith('/') || pending || settingsPending) {
      return
    }
    onFocus()
    if (!chatAgent) {
      setPendingContent(content)
      setAgentModalOpen(true)
      return
    }
    await runFirstSend(content)
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
      case 'environment':
        setEnvironmentModalOpen(true)
        return
      case 'yolo':
        void toggleYolo()
        return
      default:
        setActionError(
          command.disabledReason
          || t('ai.runtime.action.unavailableScene', { command: command.id }),
        )
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
      setActionError(errorMessage(error, t('ai.runtime.action.associateThreadFailed')))
    } finally {
      setThreadAssociationPending(false)
    }
  }

  async function handleAgentSelected(selectedAgentName: string) {
    if (settingsPending) {
      return
    }
    const content = pendingContent?.trim()
    setActionError(null)
    try {
      await onAgentChange(selectedAgentName)
      setAgentModalOpen(false)
      if (content) {
        await runFirstSend(content, selectedAgentName)
      }
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.updateAgentFailed')))
      if (content) {
        setDraft(content)
      }
    }
  }

  async function handleEnvironmentSelected(environmentName: string | null) {
    if (settingsPending) {
      return
    }
    setActionError(null)
    try {
      await onEnvironmentChange(environmentName)
      setEnvironmentModalOpen(false)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.updateEnvironmentFailed')))
    }
  }

  async function toggleYolo() {
    if (settingsPending) {
      return
    }
    setActionError(null)
    try {
      await onYoloChange(!yoloEnabled)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.updateYoloFailed')))
    }
  }

  return (
    <section className={`chat-pane ${focused ? 'focused' : ''}`} onMouseDown={onFocus}>
      {/* 与有 Thread 时同一套 shell / composer 结构，避免 blank 与 thread 输入框样式分叉 */}
      <section className="chat-shell thread-panel blank-pane">
        <main className="chat-main thread-panel-main">
          <div className="blank-pane-body">
            <h2>{t('ai.chat.blankTitle')}</h2>
            <p>{t('ai.chat.blankDescription')}</p>
            {actionError ? <div className="thread-error-panel">{actionError}</div> : null}
          </div>
          <ThreadComposer
            draft={draft}
            pending={pending || settingsPending}
            disabled={pending || settingsPending}
            onDraftChange={setDraft}
            onSubmit={() => {
              void handleSubmit()
            }}
            onCommand={handleCommand}
            commands={BLANK_PANE_COMMANDS}
          />
          <ThreadStatusFooter
            agentName={chatAgent ? footerLabels.agentName : agentLabel}
            providerName={chatAgent ? footerLabels.providerName : undefined}
            modelName={chatAgent ? footerLabels.modelName : undefined}
            variantName={chatAgent ? footerLabels.variantName : undefined}
            contextWindow={chatAgent ? footerLabels.contextWindow : undefined}
            environmentName={environmentName}
            yoloEnabled={yoloEnabled}
            onAgentClick={() => {
              onFocus()
              setAgentModalOpen(true)
            }}
            onEnvironmentClick={() => {
              onFocus()
              setEnvironmentModalOpen(true)
            }}
          />
        </main>
      </section>
      <AgentSelectionModal
        open={agentModalOpen}
        selectionPending={settingsPending}
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => {
          setAgentModalOpen(false)
          setPendingContent(null)
        }}
        onSelect={(selectedAgentName) => {
          void handleAgentSelected(selectedAgentName)
        }}
      />
      <EnvironmentSelectionModal
        open={environmentModalOpen}
        environments={environments}
        selectedEnvironmentName={environmentName}
        selectionPending={settingsPending}
        onClose={() => setEnvironmentModalOpen(false)}
        onSelect={(environmentName) => {
          void handleEnvironmentSelected(environmentName)
        }}
      />
      {/* /thread only rebinds the pane; no Thread is mutated. */}
      <SelectionListModal
        open={threadModalOpen}
        title={t('ai.chat.selectThread')}
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
        emptyText={t('ai.chat.noThreads')}
        onClose={() => setThreadModalOpen(false)}
        onSelect={selectThread}
      />
    </section>
  )
}
