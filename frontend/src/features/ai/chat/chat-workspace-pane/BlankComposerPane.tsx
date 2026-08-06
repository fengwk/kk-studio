import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ThreadComposer,
  ThreadStatusFooter,
  type ThreadCommand,
} from '@/features/ai/runtime'
import { BLANK_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import { type PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { toThreadSelectionItem } from '@/features/ai/chat/thread-selection'
import {
  FirstSendMessageError,
  performBlankPaneFirstSend,
  type FirstSendRecovery,
} from '@/features/ai/chat/chat-first-send'
import {
  branchDraftsEqual,
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import {
  AgentSelectionModal,
  EnvironmentSelectionModal,
  SelectionListModal,
} from '@/features/ai/chat/SelectionListModal'
import {
  toAgentModelViews,
  type AgentModelView,
} from '@/features/ai/catalog'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import { agentService } from '@/shared/api/agent-service'
import { isConflictError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

export function BlankComposerPane({
  chat,
  agents,
  environments = [],
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  onAgentChange,
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
  onAgentChange: (agentName: string) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
  onFirstSendRecovery: (threadId: string, recovery: FirstSendRecovery) => void
}) {
  const { t } = useI18n()
  // Blank draft: first-resolvable value copy of the Chat defaults materialized through the
  // catalog, then frozen. Later Chat/Catalog refetches never silently rewrite it.
  const [frozenDraft, setFrozenDraft] = useState<BranchDraft | null>(null)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [pendingContent, setPendingContent] = useState<string | null>(null)
  // First-resolvable materialized draft: later pane-local edits (agent/env/yolo) mark the
  // pane dirty relative to this immutable initial value (state mirror, never a render-ref).
  const [initialFrozenDraft, setInitialFrozenDraft] = useState<BranchDraft | null>(null)
  const queryClient = useQueryClient()
  const threadPicker = useChatThreadPicker(chat?.id ?? '', threadModalOpen, threadSort)
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models: AgentModelView[] = toAgentModelViews(modelsQuery.data?.results ?? [])
  const chatAgent = useMemo(
    () => (chat?.agentName ? agents.find((agent) => agent.name === chat.agentName) : undefined),
    [agents, chat],
  )
  const environmentNames = useMemo(
    () => new Map(environments.map((environment) => [environment.id, environment.name])),
    [environments],
  )

  useEffect(() => {
    if (frozenDraft != null || !chat) {
      return
    }
    // Materialize as soon as the first parse is possible (catalog may still be loading).
    if (chatAgent != null && modelsQuery.isLoading) {
      return
    }
    const materialized = materializeBlankBranchDraft(chatAgent, chat.yoloEnabled, models)
    if (materialized != null) {
      setInitialFrozenDraft((current) => current ?? materialized)
      setFrozenDraft(materialized)
    }
    // Missing/stale agent or unresolved model/variant: stay unfrozen; the composer surfaces an
    // explicit error and the agent picker completes the draft before any Thread is created.
  }, [chat, chatAgent, frozenDraft, models, modelsQuery.isLoading])

  async function runFirstSend(content: string, effective: BranchDraft | null = frozenDraft) {
    if (!chat || effective == null) {
      return
    }
    setPending(true)
    setActionError(null)
    try {
      const result = await performBlankPaneFirstSend({
        chatId: chat.id,
        content,
        // Session rejects blank titles; Chat title is nullable — null stays null.
        title: chat.title ?? null,
        branchSettings: {
          environmentId: effective.environmentId,
          agentName: effective.agentName,
          model: { ...effective.model },
          thinkingLevel: effective.thinkingLevel,
          activeTools: [...effective.activeTools],
        },
        yoloEnabled: effective.yoloEnabled,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.threads(chat.id) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(result.threadId) }),
      ])
      setDraft('')
      setPendingContent(null)
      onThreadChange(result.threadId)
    } catch (error) {
      if (error instanceof FirstSendMessageError) {
        if (isConflictError(error.cause)) {
          // Known 409: the server explicitly rejected the stale batch (cursors moved). Still
          // bind the created Thread and restore the composer text, but NEVER hand the stale
          // plan to the controller replayRef: the next submit rebuilds fresh cursors + fresh
          // command ids against the refreshed snapshot.
          await Promise.all([
            queryClient.invalidateQueries({
              queryKey: queryKeys.threads.snapshot(error.snapshot.thread.threadId),
            }),
            queryClient.invalidateQueries({ queryKey: queryKeys.chats.threads(chat.id) }),
          ])
          onFirstSendRecovery(error.snapshot.thread.threadId, { content })
          return
        }
        // Network/uncertain failure: preserve the exact batch (same command id + full replay)
        // for the bound pane.
        onFirstSendRecovery(error.snapshot.thread.threadId, {
          content,
          replay: { plan: error.plan, content },
        })
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
    if (!content || content.startsWith('/') || pending) {
      return
    }
    onFocus()
    if (!frozenDraft) {
      // Either the catalog is still loading or the Chat agent/model could not be resolved;
      // opening the agent picker completes the draft (never create a Thread with an empty
      // provider/model/variant that the strict mapper would reject).
      setPendingContent(content)
      setAgentModalOpen(true)
      return
    }
    await runFirstSend(content, frozenDraft)
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'thread':
        if (panePending) {
          setActionError(t('ai.runtime.action.threadRunning'))
          return
        }
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

  function selectThread(selectedThreadId: string) {
    // Chat-scoped picker: switching panes is pane-local; no Thread mutation happens.
    setThreadModalOpen(false)
    if (panePending) {
      setActionError(t('ai.runtime.action.threadRunning'))
      return
    }
    if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
      return
    }
    onThreadChange(selectedThreadId)
  }

  function handleAgentSelected(selectedAgentName: string) {
    const agent = agents.find((item) => item.name === selectedAgentName)
    const next =
      agent != null
        ? materializeAgentBranchDraft(agent, models, frozenDraft, chat?.yoloEnabled)
        : null
    if (next == null) {
      setActionError(t('ai.runtime.action.agentUnresolvable', { agent: selectedAgentName }))
      return
    }
    // Freeze rule: when the draft already has a valid model selection, keep
    // model/thinking/environment/yolo and only adopt the agent name + activeTools; otherwise
    // the draft is fully materialized from the selected Agent + catalog.
    setFrozenDraft(next)
    // Picker materialization is also a pane-local baseline: only the first successful
    // materialization establishes the immutable initial draft, so later agent/env/yolo
    // edits compare against it (never against null).
    setInitialFrozenDraft((current) => current ?? next)
    setAgentModalOpen(false)
    setActionError(null)
    // Sync the Chat default for future blank panes; the frozen draft keeps this pane's value.
    void onAgentChange(selectedAgentName).catch((error: unknown) => {
      setActionError(errorMessage(error, t('ai.runtime.action.updateAgentFailed')))
    })
    const content = pendingContent?.trim()
    if (content) {
      void runFirstSend(content, next)
    }
  }

  function handleEnvironmentSelected(environmentId: string | null) {
    if (pending) {
      return
    }
    setFrozenDraft((current) => (current ? { ...current, environmentId } : current))
    setEnvironmentModalOpen(false)
  }

  async function toggleYolo() {
    if (pending) {
      return
    }
    setFrozenDraft((current) => {
      if (!current) {
        return current
      }
      const next = { ...current, yoloEnabled: !current.yoloEnabled }
      void onYoloChange(next.yoloEnabled).catch((error: unknown) => {
        setActionError(errorMessage(error, t('ai.runtime.action.updateYoloFailed')))
      })
      return next
    })
    setActionError(null)
  }

  // Pane transition gates: first-send HTTP pending blocks /thread; a non-empty composer,
  // a pending first-send payload, or pane-local draft edits require confirmation to discard.
  const panePending = pending
  const paneDirty =
    draft.trim() !== ''
    || pendingContent != null
    || (
      frozenDraft != null
      && initialFrozenDraft != null
      && !branchDraftsEqual(initialFrozenDraft, frozenDraft)
    )

  const footerAgentName =
    frozenDraft?.agentName
    || (chat?.agentName
      ? t('ai.runtime.action.agentMissing')
      : t('ai.runtime.action.blankAgent'))
  const environmentId = frozenDraft?.environmentId ?? null
  const environmentDisplayName = environmentId == null ? null : (environmentNames.get(environmentId) ?? environmentId)

  return (
    <section className={`chat-pane ${focused ? 'focused' : ''}`} onMouseDown={onFocus}>
      <section className="chat-shell thread-panel blank-pane">
        <main className="chat-main thread-panel-main">
          <div className="blank-pane-body">
            <h2>{t('ai.chat.blankTitle')}</h2>
            <p>{t('ai.chat.blankDescription')}</p>
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
            agentName={footerAgentName}
            providerName={frozenDraft?.model.providerName || undefined}
            modelName={
              frozenDraft?.model.providerName && frozenDraft.model.modelName
                ? `${frozenDraft.model.providerName}/${frozenDraft.model.modelName}`
                : undefined
            }
            variantName={frozenDraft?.model.variant || undefined}
            environmentDisplayName={environmentDisplayName}
            yoloEnabled={frozenDraft?.yoloEnabled ?? chat?.yoloEnabled}
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
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => {
          setAgentModalOpen(false)
          setPendingContent(null)
        }}
        onSelect={(selectedAgentName) => {
          handleAgentSelected(selectedAgentName)
        }}
      />
      <EnvironmentSelectionModal
        open={environmentModalOpen}
        environments={environments}
        selectedEnvironmentId={environmentId}
        selectionPending={pending}
        onClose={() => setEnvironmentModalOpen(false)}
        onSelect={(selectedId) => {
          handleEnvironmentSelected(selectedId)
        }}
      />
      {/* /thread only rebinds the pane; no Thread is mutated. */}
      <SelectionListModal
        open={threadModalOpen}
        title={t('ai.chat.selectThread')}
        items={threadPicker.items.map((thread) => toThreadSelectionItem(thread, threadSort))}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        loading={threadPicker.isLoading}
        emptyText={t('ai.chat.noThreads')}
        onClose={() => setThreadModalOpen(false)}
        onSelect={selectThread}
      />
    </section>
  )
}
