import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  ChatPanel,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type ChatPanelFooterInput,
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
  type CommandBatchReplay,
  type ThreadCommand,
  useAgentThreadController,
} from '@/features/ai/runtime'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import {
  AgentSelectionModal,
  EnvironmentSelectionModal,
  SelectionListModal,
} from '@/features/ai/chat/SelectionListModal'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { BOUND_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import { extractContextWindow } from '@/features/ai/catalog'
import {
  branchDraftFromThread,
  branchDraftsEqual,
  projectPendingTarget,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import { branchTarget } from '@/features/ai/chat/session-entry-tree'
import { toThreadSelectionItem } from '@/features/ai/chat/thread-selection'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import { isConflictError, isNotFoundError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate, useI18n } from '@/shared/i18n'

/** 409 = 过期 revision 或非 quiescent 的 Thread；绝不能悄悄吞掉。 */
function rebindErrorMessage(error: unknown): string {
  if (isConflictError(error)) {
    return translate('ai.runtime.action.rebindConflict', {
      error: errorMessage(error, translate('ai.catalog.validation.conflict')),
    })
  }
  return errorMessage(error, translate('ai.runtime.action.rebindFailed'))
}

export function BoundThreadPane({
  chatId,
  agents,
  environments = [],
  paneId,
  threadId,
  focused,
  threadSort,
  onFocus,
  onThreadChange,
  onThreadSortChange,
  initialDraft,
  initialReplay,
  onReplayInitialized,
}: {
  chatId: string
  agents: AgentDefinitionDTO[]
  environments?: LiveEnvironmentDTO[]
  paneId: string
  threadId: string
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  /** 在首次发送失败后恢复 composer 文本；与 replay identity 相互独立。 */
  initialDraft?: string
  initialReplay?: CommandBatchReplay
  onReplayInitialized?: () => void
}) {
  const { t } = useI18n()
  const environmentNames = useMemo(
    () => new Map(environments.map((environment) => [environment.id, environment.name])),
    [environments],
  )
  // buildBatch 依赖 controller 的 snapshot thread；controller 在下方创建，因此
  // 稳定的回调通过一个 ref 转发，并在每次渲染中、use 之前赋值。
  const buildBatchRef = useRef<((content: string) => CommandBatchPlan | null) | null>(null)
  const controller = useAgentThreadController(
    threadId,
    initialDraft ?? initialReplay?.content ?? '',
    initialReplay,
    (content) => buildBatchRef.current?.(content) ?? null,
    environmentNames,
  )
  // 面板本地 branch draft：从持久化的 Thread snapshot 初始化，面板本地编辑，
  // 与下一条 message batch 一起原子应用。base 跟随 snapshot；queued SET_*
  // command 投影 effective base，避免连续发送时重复携带在途中的 settings。
  const [branchState, setBranchState] = useState<{
    base: BranchDraft
    draft: BranchDraft
    initialized: boolean
  } | null>(null)
  const [threadModalOpen, setThreadModalOpen] = useState(false)
  const [agentModalOpen, setAgentModalOpen] = useState(false)
  const [environmentModalOpen, setEnvironmentModalOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [rebindBlockedReason, setRebindBlockedReason] = useState<string | null>(null)
  const queryClient = useQueryClient()
  const boundThreadIdRef = useRef<string | null>(null)

  // 将面板重新绑定到另一个 Thread 时，会清空所有面板本地状态，并从新 snapshot
  // 重新初始化 draft（controller 也会重置其 stop/decision replay 状态）。
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    setBranchState(null)
    setHistoryOpen(false)
    setAgentModalOpen(false)
    setEnvironmentModalOpen(false)
    setThreadModalOpen(false)
    setRebindBlockedReason(null)
  }, [threadId])

  const effectiveBase = useMemo(() => {
    if (branchState == null) {
      return null
    }
    return projectPendingTarget(branchState.base, controller.queuedCommands)
  }, [branchState, controller.queuedCommands])
  const dirty =
    branchState != null
    && effectiveBase != null
    && !branchDraftsEqual(effectiveBase, branchState.draft)
  const hasPendingCommands = controller.queuedCommands.length > 0

  useEffect(() => {
    // 首次发送失败的 recovery 只会被消费一次：无论形式（仅文本的 409 recovery 或
    // 文本 + exact-batch replay）都在 controller 中初始化，然后清空面板状态，
    // 避免切走再切回时再次套用过期 recovery。
    if (initialReplay || initialDraft) {
      onReplayInitialized?.()
    }
  }, [initialDraft, initialReplay, onReplayInitialized])

  useEffect(() => {
    if (isNotFoundError(controller.messagesError)) {
      onThreadChange(null)
    }
  }, [controller.messagesError, onThreadChange])

  // 重新绑定到新 Thread 时，从其 snapshot 初始化 draft（base === draft，干净）。
  useEffect(() => {
    const thread = controller.thread
    if (!thread) {
      return
    }
    setBranchState((current) => {
      const snapshotDraft = branchDraftFromThread(thread)
      if (current == null) {
        return { base: snapshotDraft, draft: snapshotDraft, initialized: true }
      }
      if (!current.initialized) {
        return { ...current, base: snapshotDraft, draft: snapshotDraft, initialized: true }
      }
      // 持久化的 base 跟随 snapshot；用户 draft 永远不会被静默覆盖。
      return { ...current, base: snapshotDraft }
    })
  }, [controller.thread])

  const buildBatch = useCallback(
    (content: string): CommandBatchPlan | null => {
      const thread = controller.thread
      if (!thread || branchState == null || effectiveBase == null) {
        return null
      }
      return buildMessageBatchPlan({
        thread,
        effectiveBase,
        draft: branchState.draft,
        content,
      })
    },
    [branchState, controller.thread, effectiveBase],
  )
  useEffect(() => {
    // Ref 由 controller 的 submit handler 使用（事件驱动，总在 effect 之后）。
    buildBatchRef.current = buildBatch
  })

  const rebindMutation = useMutation({
    mutationFn: (entry: HarnessSessionEntryDTO) => {
      const target = branchTarget(entry)
      if (!target.headEntryId) {
        return Promise.reject(new Error(t('ai.runtime.action.rootNotBranchable')))
      }
      const thread = controller.thread
      if (!thread) {
        return Promise.reject(new Error(t('ai.runtime.action.threadNotLoaded')))
      }
      return harnessService.updateThreadHead(threadId, {
        targetEntryId: target.headEntryId,
        expectedRevision: thread.revision,
      })
    },
    onSuccess: async (updatedThread: HarnessThreadDTO, entry: HarnessSessionEntryDTO) => {
      setHistoryOpen(false)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
      // 成功重定位后，从目标 Thread 重新初始化 branch draft（yolo 保留服务器值；
      // branch settings 取自返回的 Thread）。
      const snapshotDraft = branchDraftFromThread(updatedThread)
      setBranchState({ base: snapshotDraft, draft: snapshotDraft, initialized: true })
      // USER/CUSTOM 回退会将其可编辑的原文恢复到 composer。
      controller.setDraft(branchTarget(entry).draft)
    },
  })

  // 面板切换门控：
  // - paneDirty = branch draft 已变脏 或 composer 有非空文本（已接受的消息已清空
  //   composer；非空 composer 属于新输入未发送）。
  // - panePending = 任何使切换不安全的 in-flight mutation：queued commands、command HTTP、
  //   head 重定位、stop、approval、未决的 exact batch replay，或等待精确重试的
  //   不确定 Stop 操作。
  const paneDirty = dirty || controller.draft.trim() !== ''
  const panePending =
    hasPendingCommands
    || controller.pending
    || rebindMutation.isPending
    || controller.stopPending
    || controller.stopReplayPending
    || controller.approvalPending
    || controller.replayPending

  const threadPicker = useChatThreadPicker(chatId, threadModalOpen, threadSort)
  const threadItems = threadPicker.items.map((item) => toThreadSelectionItem(item, threadSort))

  function editDraft(patch: Partial<BranchDraft>) {
    setBranchState((current) => {
      if (current == null) {
        return current
      }
      return { ...current, draft: { ...current.draft, ...patch } }
    })
  }

  function selectAgent(selectedAgentName: string) {
    setRebindBlockedReason(null)
    const agent = agents.find((item) => item.name === selectedAgentName)
    if (!agent) {
      setRebindBlockedReason(t('ai.runtime.action.agentUnresolvable', { agent: selectedAgentName }))
      return
    }
    // Draft-local edit：采用新的 agent name + 它的 active tool 集合；冻结的
    // model/thinking/environment/yolo 选中值保持不变。
    editDraft({ agentName: selectedAgentName, activeTools: [...agent.config.tools] })
    setAgentModalOpen(false)
  }

  function selectEnvironment(environmentId: string | null) {
    setRebindBlockedReason(null)
    editDraft({ environmentId })
    setEnvironmentModalOpen(false)
  }

  function toggleYolo() {
    setRebindBlockedReason(null)
    setBranchState((current) => {
      if (current == null) {
        return current
      }
      return {
        ...current,
        draft: { ...current.draft, yoloEnabled: !current.draft.yoloEnabled },
      }
    })
  }

  function selectThread(selectedThreadId: string) {
    setThreadModalOpen(false)
    if (selectedThreadId === threadId) {
      // 选中当前已绑定的 Thread 不需要确认，也不会产生任何变化。
      return
    }
    if (panePending) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
      return
    }
    onThreadChange(selectedThreadId)
  }

  /** 仅逻辑上 quiescent 的 Thread 才接受 head rebind（与服务端分类器一致）。 */
  function isRelocatable(): boolean {
    const thread = controller.thread
    return thread != null
      && (thread.status === 'IDLE' || thread.status === 'CONTINUATION_DUE')
      && !panePending
  }

  /** /tree 重定位：non-relocatable 状态或 pending command 在入口处直接拦截。 */
  function openHistory() {
    if (!isRelocatable()) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    setRebindBlockedReason(null)
    setHistoryOpen(true)
  }

  function rebindTo(entry: HarnessSessionEntryDTO) {
    if (panePending) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
      return
    }
    rebindMutation.mutate(entry)
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'session':
        // 全局 Session rebind 已不再存在：保持可见但禁用。
        return
      case 'thread':
        if (panePending) {
          setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
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
        toggleYolo()
        return
      case 'tree':
        openHistory()
        return
      case 'new':
        if (panePending) {
          setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
          return
        }
        if (paneDirty && !window.confirm(t('ai.chat.history.confirmDiscardDraft'))) {
          return
        }
        controller.setDraft('')
        onThreadChange(null)
        return
      default:
        controller.runCommand(command)
    }
  }

  const draft = branchState?.draft
  const draftModel = draft
    ? controller.models.find(
        (item) =>
          item.providerName === draft.model.providerName
          && item.name === draft.model.modelName,
      )
    : undefined
  const labels: ChatPanelLabels = {
    // Footer 始终反映面板本地 draft（agent/model/variant/environment），仅在
    // draft 尚未初始化时回退到持久化 snapshot 的 label。
    agentName: draft?.agentName || controller.runtimeLabels.agentName,
    providerName: draft?.model.providerName || controller.runtimeLabels.providerName,
    modelName: draft?.model.providerName && draft.model.modelName
      ? `${draft.model.providerName}/${draft.model.modelName}`
      : controller.runtimeLabels.modelName,
    variantName: draft?.model.variant || controller.runtimeLabels.variantName,
    environmentDisplayName:
      draft?.environmentId != null
        ? (environmentNames.get(draft.environmentId) ?? draft.environmentId)
        : (draft?.environmentId == null && draft != null
            ? null
            : controller.runtimeLabels.environmentDisplayName),
    contextWindow: extractContextWindow(draftModel) ?? controller.runtimeLabels.contextWindow,
  }
  const transcript: ChatPanelTranscriptInput = {
    timeline: controller.timeline,
    bodyRef: controller.bodyRef,
    loading: controller.messagesLoading,
    error: controller.messagesError,
    approvalPending: controller.approvalPending,
    onDecideApproval: (_message, decision) => {
      const invocationId = _message.invocationId
      if (invocationId) {
        void controller.decideApproval(invocationId, decision)
      }
    },
  }
  const composer: ChatPanelComposerInput = {
    draft: controller.draft,
    // Pending 覆盖 in-flight HTTP 请求（同时禁用发送：canSend 已检查 disabled），
    // 加上 branch draft 与 buildBatch 就绪状态，确保一个 replayRef 不会服务并发的
    // CAS 请求。
    pending: controller.pending || rebindMutation.isPending,
    disabled:
      controller.pending
      || controller.disabled
      || rebindMutation.isPending
      || branchState == null
      || effectiveBase == null,
    onDraftChange: controller.setDraft,
    onSubmit: () => {
      void controller.submitMessage()
    },
    onCommand: handleCommand,
    commands: BOUND_PANE_COMMANDS,
  }
  const footer: ChatPanelFooterInput = {
    yoloEnabled: branchState?.draft.yoloEnabled ?? controller.thread?.yoloEnabled,
    onAgentClick: () => {
      onFocus()
      setAgentModalOpen(true)
    },
    onModelClick: undefined,
    onVariantClick: undefined,
    onEnvironmentClick: () => {
      onFocus()
      setEnvironmentModalOpen(true)
    },
  }
  const activity: ChatPanelActivityInput = {
    working: controller.working,
    actionError: rebindBlockedReason ?? controller.actionError,
    onDismissActionError: () => {
      setRebindBlockedReason(null)
      controller.dismissActionError()
    },
  }

  return (
    <section
      className={`chat-pane ${focused ? 'focused' : ''}`}
      onMouseDown={onFocus}
      data-pane-id={paneId}
    >
      <ChatPanel
        labels={labels}
        transcript={transcript}
        composer={composer}
        footer={footer}
        activity={activity}
      />
      {historyOpen ? (
        <HistoryBranchPanel
          entries={controller.entries}
          currentHeadEntryId={controller.thread?.headEntryId}
          loading={controller.messagesLoading}
          queryError={controller.messagesError}
          pending={rebindMutation.isPending}
          rebindError={rebindMutation.error ? rebindErrorMessage(rebindMutation.error) : null}
          onClose={() => {
            setHistoryOpen(false)
            rebindMutation.reset()
          }}
          onRebind={rebindTo}
        />
      ) : null}
      {/* /thread 切换该面板展示哪个 Chat 作用域 Thread；不会修改任何 Thread。 */}
      <SelectionListModal
        open={threadModalOpen}
        title={t('ai.chat.selectThread')}
        items={threadItems}
        sort={threadSort}
        onSortChange={onThreadSortChange}
        loading={threadPicker.isLoading}
        emptyText={t('ai.chat.noThreads')}
        onClose={() => setThreadModalOpen(false)}
        onSelect={selectThread}
      />
      <AgentSelectionModal
        open={agentModalOpen}
        agents={agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        onClose={() => setAgentModalOpen(false)}
        onSelect={selectAgent}
      />
      <EnvironmentSelectionModal
        open={environmentModalOpen}
        environments={environments}
        selectedEnvironmentId={branchState?.draft.environmentId ?? null}
        onClose={() => setEnvironmentModalOpen(false)}
        onSelect={(environmentId) => {
          selectEnvironment(environmentId)
        }}
      />
    </section>
  )
}
