import { lazy, Suspense, useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ThreadComposer,
  ThreadShortcutsPanel,
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
  copyBinding,
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  hasMessageContent,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  clearStoredComposerDraft,
  restoreComposerDraft,
  storeComposerDraft,
} from '@/features/ai/composer/composer-draft'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import {
  AgentSelectionPanel,
  ThreadSelectionPanel,
} from '@/features/ai/chat/SelectionPanel'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import {
  toAgentModelViews,
  type AgentModelView,
} from '@/features/ai/catalog'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import { agentService } from '@/shared/api/agent-service'
import { isConflictError } from '@/shared/api/client'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'

const ConfirmActionModal = lazy(async () => {
  const module = await import('@/shared/ui/console/ConfirmActionModal')
  return { default: module.ConfirmActionModal }
})

export function BlankComposerPane({
  chat,
  agents,
  environments = [],
  paneId,
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  onAgentChange,
  onYoloChange = async () => undefined,
  onEnvironmentChange = async () => undefined,
  onFirstSendRecovery,
}: {
  chat: ChatDTO | undefined
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  paneId: string
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  onAgentChange: (agentName: string) => Promise<void>
  onYoloChange?: (yoloEnabled: boolean) => Promise<void>
  /** 空面板显式选择/清空 Environment 草稿时同步 Chat 默认值（版本化 CAS）；失败仅提示，不影响本面板草稿。 */
  onEnvironmentChange?: (environment: EnvironmentBindingDTO | null) => Promise<void>
  onFirstSendRecovery: (threadId: string, recovery: FirstSendRecovery) => void
}) {
  const { t } = useI18n()
  const draftStorageScope = chat?.id ? `chat:${chat.id}:pane:${paneId}` : ''
  // 空面板 draft：通过 catalog 将 Chat 默认值 materialize 后，复制第一个可解析值，
  // 再冻结。后续 Chat/Catalog refetch 不会悄悄重写它。
  const [frozenDraft, setFrozenDraft] = useState<BranchDraft | null>(null)
  const [parts, setPartsState] = useState<ComposerPart[]>(
    () => restoreComposerDraft(draftStorageScope, []),
  )
  const [pending, setPending] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [interaction, setInteraction] = useState<
    'agent' | 'environment' | 'thread' | 'shortcuts' | null
  >(null)
  const [discardConfirm, setDiscardConfirm] = useState<ConfirmModalState | null>(null)
  /** 等待 agent 补全期间挂起的提交：payload（server uploadId）+ localDraft（客户端 localId）分开保存。 */
  const [pendingContent, setPendingContent] = useState<{
    payload: ComposerPart[]
    localDraft: ComposerPart[]
  } | null>(null)
  // 首个可解析 materialized draft：后续 pane-local 编辑（agent/env/yolo）相对于这个
  // 不可变的初始值会标记面板为 dirty（state 镜像，绝不是 render-ref）。
  const [initialFrozenDraft, setInitialFrozenDraft] = useState<BranchDraft | null>(null)
  const queryClient = useQueryClient()
  const threadPicker = useChatThreadPicker(chat?.id ?? '', interaction === 'thread', threadSort)
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const models: AgentModelView[] = toAgentModelViews(modelsQuery.data?.results ?? [])
  const chatAgent = useMemo(
    () => (chat?.agentName ? agents.find((agent) => agent.name === chat.agentName) : undefined),
    [agents, chat],
  )
  // canonical 名称即展示身份；仅携带统一可用性标记（live 列表缺失/未知 => unavailable）。
  const environmentReadyByName = useMemo(
    () => new Map(environments.map((environment) => [environment.name, environment.ready])),
    [environments],
  )

  useEffect(() => {
    if (frozenDraft != null || !chat) {
      return
    }
    // 首次可解析就立即 materialize（catalog 仍在加载中时也可以）。
    if (chatAgent != null && modelsQuery.isLoading) {
      return
    }
    const materialized = materializeBlankBranchDraft(
      chatAgent,
      chat.yoloEnabled,
      models,
      // Chat 默认 Environment binding 是空面板草稿的起点；用户可在首次发送前更改或清空。
      chat.environment ?? null,
    )
    if (materialized != null) {
      setInitialFrozenDraft((current) => current ?? materialized)
      setFrozenDraft(materialized)
    }
    // 缺少/过期 agent，或 model/variant 无法解析：保持不冻结；composer 显示
    // 明确错误，并通过 agent picker 在创建 Thread 之前补全 draft。
  }, [chat, chatAgent, frozenDraft, models, modelsQuery.isLoading])

  async function runFirstSend(
    sendParts: ComposerPart[],
    localDraft: ComposerPart[],
    effective: BranchDraft | null = frozenDraft,
  ) {
    if (!chat || effective == null) {
      return
    }
    clearStoredComposerDraft(draftStorageScope)
    setPending(true)
    setActionError(null)
    try {
      const result = await performBlankPaneFirstSend({
        chatId: chat.id,
        // payload（server uploadId）只用于 HTTP 发送。
        parts: sendParts,
        // Session 拒绝空标题；Chat title 可为空——保持 null。
        title: chat.title ?? null,
        branchSettings: {
          environment: copyBinding(effective.environment),
          agentName: effective.agentName,
          model: { ...effective.model },
          activeTools: [...effective.activeTools],
        },
        yoloEnabled: effective.yoloEnabled,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.threads(chat.id) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(result.threadId) }),
      ])
      setPartsState([])
      setPendingContent(null)
      onThreadChange(result.threadId)
    } catch (error) {
      if (error instanceof FirstSendMessageError) {
        if (isConflictError(error.cause)) {
          // 已知 409：服务器明确拒绝了过期 batch（cursor 已移动）。仍需绑定已创建的
          // Thread 并恢复 composer 内容，但绝不能把过期的 plan 交给 controller replayRef：
          // 下一次提交会基于刷新的 snapshot 重新构建 cursor + command id。
          await Promise.all([
            queryClient.invalidateQueries({
              queryKey: queryKeys.threads.snapshot(error.snapshot.thread.threadId),
            }),
            queryClient.invalidateQueries({ queryKey: queryKeys.chats.threads(chat.id) }),
          ])
          onFirstSendRecovery(error.snapshot.thread.threadId, { parts: localDraft })
          return
        }
        // 网络/不确定失败：为绑定面板保留精确 batch（相同 command id + 完整 replay）。
        onFirstSendRecovery(error.snapshot.thread.threadId, {
          parts: localDraft,
          replay: { plan: error.plan, parts: localDraft },
        })
        return
      }
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
      updateParts(localDraft)
    } finally {
      setPending(false)
    }
  }

  async function handleSubmit(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]) {
    const payload = trimMessageParts(payloadParts ?? parts)
    const localDraft = trimMessageParts(localDraftParts ?? parts)
    if (!hasMessageContent(payload) || slashQueryOf(payload) != null || pending) {
      return
    }
    onFocus()
    if (!frozenDraft) {
      // 要么 catalog 仍在加载，要么 Chat agent/model 无法解析；打开 agent picker
      // 补全 draft（绝不创建 provider/model/variant 为空的 Thread，否则会被
      // strict mapper 拒绝）。payload 与 localDraft 分开挂起。
      setPendingContent({ payload, localDraft })
      setInteraction('agent')
      return
    }
    await runFirstSend(payload, localDraft, frozenDraft)
  }

  function updateParts(next: ComposerPart[]) {
    storeComposerDraft(draftStorageScope, next)
    setPartsState(next)
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
        setInteraction('thread')
        return
      case 'agent':
        setInteraction('agent')
        return
      case 'environment':
        setInteraction('environment')
        return
      case 'yolo':
        void toggleYolo()
        return
      case 'shortcuts':
        setInteraction('shortcuts')
        return
      default:
        setActionError(
          command.disabledReason
          || t('ai.runtime.action.unavailableScene', { command: command.id }),
        )
    }
  }

  function selectThread(selectedThreadId: string) {
    // Chat 作用域 picker：切换面板仅作用于面板本地；不会触发任何 Thread mutation。
    if (panePending) {
      setActionError(t('ai.runtime.action.threadRunning'))
      return
    }
    confirmDiscardIfNeeded(() => {
      setInteraction(null)
      updateParts([])
      onThreadChange(selectedThreadId)
    })
  }

  function confirmDiscardIfNeeded(action: () => void) {
    if (!paneDirty) {
      action()
      return
    }
    setDiscardConfirm({
      title: t('ai.chat.history.discardDraftTitle'),
      description: t('ai.chat.history.confirmDiscardDraft'),
      confirmLabel: t('ai.chat.history.discardDraftConfirm'),
      tone: 'danger',
      onConfirm: () => {
        setDiscardConfirm(null)
        action()
      },
    })
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
    // Freeze 规则：当 draft 已有有效 model selection 时，保留 model/environment/yolo，
    // 仅采用 agent name + activeTools；否则 draft 由选中的 Agent + catalog 完整 materialize。
    setFrozenDraft(next)
    // Picker materialization 同样是面板本地基线：只有首次成功的 materialization 才会建立
    // 不可变的初始 draft；后续的 agent/env/yolo 编辑以此为基准（永远不与 null 比较）。
    setInitialFrozenDraft((current) => current ?? next)
    setInteraction(null)
    setActionError(null)
    // 同步 Chat 默认值以影响后续空面板；当前 frozen draft 保留本面板的值。
    void onAgentChange(selectedAgentName).catch((error: unknown) => {
      setActionError(errorMessage(error, t('ai.runtime.action.updateAgentFailed')))
    })
    const content = pendingContent
    if (content && hasMessageContent(content.payload)) {
      void runFirstSend(content.payload, content.localDraft, next)
    }
  }

  function handleEnvironmentSelected(environment: EnvironmentBindingDTO | null) {
    if (pending) {
      return
    }
    // 与 agent/yolo 一致的语义：立即更新本面板 frozen draft（首次发送 ROOT 使用面板本地值），
    // 同时异步把 Chat 默认值同步为最新选择——即使更新失败/延迟，本面板草稿不受影响。
    setFrozenDraft((current) => (current ? { ...current, environment } : current))
    setInteraction(null)
    setActionError(null)
    void onEnvironmentChange(environment).catch((error: unknown) => {
      setActionError(errorMessage(error, t('ai.runtime.action.updateEnvironmentFailed')))
    })
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

  // 面板切换门控：first-send HTTP pending 阻塞 /thread；非空 composer、pending first-send
  // payload 或面板本地 draft 编辑都需要确认后才能丢弃。
  const panePending = pending
  const paneDirty =
    hasMessageContent(parts)
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
  const environment = frozenDraft?.environment ?? null
  const interactionPanel =
    interaction === 'agent' ? (
      <AgentSelectionPanel
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        selectedAgentName={frozenDraft?.agentName ?? chat?.agentName}
        selectionPending={pending}
        onClose={() => {
          setInteraction(null)
          setPendingContent(null)
        }}
        onSelect={handleAgentSelected}
      />
    ) : interaction === 'environment' ? (
      <EnvironmentWorkspacePanel
        environments={environments}
        current={environment}
        pending={pending}
        onClose={() => setInteraction(null)}
        onSelect={handleEnvironmentSelected}
      />
    ) : interaction === 'thread' ? (
      <ThreadSelectionPanel
        items={threadPicker.items.map((thread) => toThreadSelectionItem(thread, threadSort))}
        sort={threadSort}
        loading={threadPicker.isLoading}
        onSortChange={onThreadSortChange}
        onClose={() => setInteraction(null)}
        onSelect={selectThread}
      />
    ) : interaction === 'shortcuts' ? (
      <ThreadShortcutsPanel onClose={() => setInteraction(null)} />
    ) : null

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
            parts={parts}
            pending={pending}
            disabled={pending}
            onPartsChange={updateParts}
            onSubmit={(payload, localDraft) => {
              void handleSubmit(payload, localDraft)
            }}
            onCommand={handleCommand}
            commands={BLANK_PANE_COMMANDS}
            focusOnEscape={focused && interactionPanel == null}
            active={interactionPanel == null}
          />
          {interactionPanel}
          <ThreadStatusFooter
            agentName={footerAgentName}
            providerName={frozenDraft?.model.providerName || undefined}
            modelName={
              frozenDraft?.model.providerName && frozenDraft.model.modelName
                ? `${frozenDraft.model.providerName}/${frozenDraft.model.modelName}`
                : undefined
            }
            variantName={frozenDraft?.model.variant || undefined}
            environment={environment}
            environmentReady={environment == null ? undefined : (environmentReadyByName.get(environment.name) ?? false)}
            yoloEnabled={frozenDraft?.yoloEnabled ?? chat?.yoloEnabled}
            onAgentClick={() => {
              onFocus()
              setInteraction('agent')
            }}
            onEnvironmentClick={() => {
              onFocus()
              setInteraction('environment')
            }}
          />
        </main>
      </section>
      {discardConfirm ? (
        <Suspense fallback={null}>
          <ConfirmActionModal
            modal={discardConfirm}
            pending={false}
            onClose={() => setDiscardConfirm(null)}
          />
        </Suspense>
      ) : null}
    </section>
  )
}
