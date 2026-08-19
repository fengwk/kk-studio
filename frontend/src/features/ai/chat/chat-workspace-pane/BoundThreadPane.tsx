import {
  lazy,
  Suspense,
  useEffect,
  useRef,
  useState,
} from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ChatPanel,
  ThreadEventDetail,
  ThreadShortcutsPanel,
  useBoundBranchPanel,
  useBoundThreadPanelLabels,
  useBoundThreadPanelViews,
  buildBoundThreadTranscript,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type CommandBatchReplay,
  type ThreadCommand,
} from '@/features/ai/runtime'

import {
  browserNotificationPermission,
  useThreadNotifications,
} from '@/features/ai/runtime/thread-notifications'
import { useBrowserPreferences } from '@/features/settings/browser-preferences'
import { HistoryBranchPanel } from '@/features/ai/chat/HistoryBranchPanel'
import {
  AgentSelectionPanel,
  ThreadSelectionPanel,
} from '@/features/ai/chat/SelectionPanel'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import type { PaneSortPreference } from '@/features/ai/chat/chat-pane-state'
import { BOUND_PANE_COMMANDS } from '@/features/ai/chat/chat-workspace-pane/commands'
import { errorMessage } from '@/features/ai/chat/chat-workspace-pane/pane-errors'
import {
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  createTextPart,
  hasMessageContent,
  slashQueryOf,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import { branchTarget } from '@/features/ai/chat/session-entry-tree'
import { toThreadSelectionItem } from '@/features/ai/chat/thread-selection'
import { useChatThreadPicker } from '@/features/ai/chat/useChatThreadPicker'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts/ai-runtime'
import { isConflictError, isNotFoundError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate, useI18n } from '@/shared/i18n'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'

const ConfirmActionModal = lazy(async () => {
  const module = await import('@/shared/ui/console/ConfirmActionModal')
  return { default: module.ConfirmActionModal }
})

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
  environments?: LiveEnvironmentDTO[]
  paneId: string
  threadId: string
  focused: boolean
  threadSort: PaneSortPreference
  onFocus: () => void
  onThreadChange: (threadId: string | null) => void
  onThreadSortChange: (sort: PaneSortPreference) => void
  /** 在首次发送失败后恢复 composer 内容；与 replay identity 相互独立。 */
  initialDraft?: ComposerPart[]
  initialReplay?: CommandBatchReplay
  onReplayInitialized?: () => void
}) {
  const { t } = useI18n()
  const panel = useBoundBranchPanel({
    threadId,
    initialParts: initialDraft ?? initialReplay?.parts ?? [],
    initialReplay,
  })
  const { controller } = panel
  // 互斥主视图与 Event 选中（mode/selected + 双 scrollTop）：每个 mounted
  // Pane 独立；threadId 重绑由 hook 全部重置回 conversation。
  const {
    mode,
    switchMode,
    selectEvent,
    initialConversationScrollTop,
    selectedRecord,
    mainView,
  } = useBoundThreadPanelViews(threadId, controller)
  const [interaction, setInteraction] = useState<
    'thread' | 'agent' | 'environment' | 'history' | 'shortcuts' | null
  >(null)
  const [rebindBlockedReason, setRebindBlockedReason] = useState<string | null>(null)
  const [discardConfirm, setDiscardConfirm] = useState<ConfirmModalState | null>(null)
  const queryClient = useQueryClient()
  const historyEntriesQuery = useQuery({
    queryKey: queryKeys.threads.entries(threadId),
    queryFn: () => harnessService.listThreadEntries(threadId),
    enabled: interaction === 'history',
  })
  const boundThreadIdRef = useRef<string | null>(null)
  // 全局应用设置（AppProviders mount-once）：通知开关是 SettingsPage 与所有
  // Bound 面板共享的唯一事实源，不再维护面板本地偏好。
  const { notificationsEnabled } = useBrowserPreferences()
  // permission 每次渲染都从当前浏览器状态派生，绝不缓存 mount 时快照：
  // SettingsPage 可能在别处请求权限后置全局 enabled，已挂载面板必须立即反映
  // 真实 permission，通知 hook 不会被陈旧快照门控；Footer 不再承载通知入口。
  const notificationPermission = browserNotificationPermission()
  const threadNotifications = useThreadNotifications({
    threadId,
    title: controller.title,
    messages: controller.timeline.messages,
    working: controller.working,
    enabled: notificationsEnabled && notificationPermission === 'granted',
  })

  // 将面板重新绑定到另一个 Thread 时，会清空面板本地交互/错误/确认状态
  //（branch draft 由 useBoundBranchPanel 重置，主视图/Event 状态由
  // useThreadPanelViewState 重置；controller 也会重置其 stop/decision replay 状态）。
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    setInteraction(null)
    setRebindBlockedReason(null)
    setDiscardConfirm(null)
  }, [threadId])

  const labels = useBoundThreadPanelLabels(environments, controller)
  const transcript = buildBoundThreadTranscript({
    controller,
    threadId,
    initialConversationScrollTop,
    onDenyApproval: () => threadNotifications.markPermissionRejected(),
  })
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
      setInteraction(null)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
      // 成功重定位后，从目标 Thread 重新初始化 branch draft（yolo 保留服务器值；
      // branch settings 取自返回的 Thread）。
      panel.resetDraftFromThread(updatedThread)
      // USER/CUSTOM 回退会将其可编辑的原文恢复到 composer。
      controller.setDraft([createTextPart(branchTarget(entry).draft)])
    },
  })

  // 面板切换门控：
  // - paneDirty = branch draft 已变脏 或 composer 有非空文本（已接受的消息已清空
  //   composer；非空 composer 属于新输入未发送）。
  // - panePending = 任何使切换不安全的 in-flight mutation：queued commands、command HTTP、
  //   head 重定位、stop、approval、未决的 exact batch replay，或等待精确重试的
  //   不确定 Stop 操作。
  const hasUnsentMessage = hasMessageContent(controller.draft)
  const hasUnsentSettings = panel.dirty
  const paneDirty = hasUnsentSettings || hasUnsentMessage
  const panePending =
    hasPendingCommands
    || controller.pending
    || rebindMutation.isPending
    || controller.stopPending
    || controller.stopReplayPending
    || controller.approvalPending
    || controller.replayPending

  const threadPicker = useChatThreadPicker(chatId, interaction === 'thread', threadSort)
  const threadItems = threadPicker.items.map((item) => toThreadSelectionItem(item, threadSort))

  function selectAgent(selectedAgentName: string) {
    setRebindBlockedReason(null)
    if (!panel.selectAgent(selectedAgentName)) {
      setRebindBlockedReason(t('ai.runtime.action.agentUnresolvable', { agent: selectedAgentName }))
      return
    }
    setInteraction(null)
  }

  function selectEnvironment(environment: EnvironmentBindingDTO | null) {
    setRebindBlockedReason(null)
    panel.selectEnvironment(environment)
    setInteraction(null)
  }

  function setYoloEnabled(enabled: boolean) {
    setRebindBlockedReason(null)
    panel.setYoloEnabled(enabled)
  }

  function selectModel(model: BranchDraft['model']) {
    setRebindBlockedReason(null)
    panel.selectModel(model)
  }

  function selectThread(selectedThreadId: string) {
    if (selectedThreadId === threadId) {
      // 选中当前已绑定的 Thread 不需要确认，也不会产生任何变化。
      setInteraction(null)
      return
    }
    if (panePending) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    confirmDiscardIfNeeded(
      () => {
        setInteraction(null)
        controller.setDraft([])
        onThreadChange(selectedThreadId)
      },
      'thread',
    )
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
    setInteraction('history')
  }

  function rebindTo(entry: HarnessSessionEntryDTO) {
    if (panePending) {
      setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
      return
    }
    confirmDiscardIfNeeded(() => rebindMutation.mutate(entry), 'history')
  }

  function confirmDiscardIfNeeded(
    action: () => void,
    target: 'thread' | 'history' | 'new' = 'thread',
    discarded = { message: hasUnsentMessage, settings: hasUnsentSettings },
  ) {
    if (!paneDirty || (!discarded.message && !discarded.settings)) {
      action()
      return
    }
    const targetKey =
      target === 'history'
        ? 'ai.chat.history.discardTargetHistory'
        : target === 'new'
          ? 'ai.chat.history.discardTargetNew'
          : 'ai.chat.history.discardTargetThread'
    const contentKey =
      discarded.message && discarded.settings
        ? 'ai.chat.history.discardContentMessageAndSettings'
        : discarded.message
          ? 'ai.chat.history.discardContentMessage'
          : 'ai.chat.history.discardContentSettings'
    setDiscardConfirm({
      title: t('ai.chat.history.discardDraftTitle'),
      description: t('ai.chat.history.confirmDiscardDraft', {
        target: t(targetKey),
        content: t(contentKey),
      }),
      confirmLabel: t('ai.chat.history.discardDraftConfirm'),
      tone: 'danger',
      onConfirm: () => {
        setDiscardConfirm(null)
        action()
      },
    })
  }

  function handleCommand(command: ThreadCommand) {
    onFocus()
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'thread':
        if (panePending) {
          setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
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
        setYoloEnabled(!(panel.draft?.yoloEnabled ?? false))
        return
      case 'tree':
        openHistory()
        return
      case 'events':
        switchMode(mode === 'events' ? 'conversation' : 'events')
        return
      case 'shortcuts':
        setInteraction('shortcuts')
        return
      case 'new':
        if (panePending) {
          setRebindBlockedReason(t('ai.runtime.action.threadRunning'))
          return
        }
        {
          // ThreadComposer 已消费纯 slash 命令，但 React 仍可能在本次事件中暴露旧 parts。
          // 只有命令之外仍有附件/正文，或 branch 设置已修改时，才需要丢弃确认。
          const commandInputOnly =
            slashQueryOf(controller.draft) != null
            && controller.draft.every((part) => part.type === 'text')
          confirmDiscardIfNeeded(
            () => {
              controller.setDraft([])
              onThreadChange(null)
            },
            'new',
            {
              message: hasUnsentMessage && !commandInputOnly,
              settings: hasUnsentSettings,
            },
          )
        }
        return
      default:
        controller.runCommand(command)
    }
  }

  const draft = panel.draft
  const interactionPanel =
    interaction === 'thread' ? (
      <ThreadSelectionPanel
        items={threadItems}
        selectedThreadId={threadId}
        sort={threadSort}
        loading={threadPicker.isLoading}
        onSortChange={onThreadSortChange}
        onClose={() => setInteraction(null)}
        onSelect={selectThread}
      />
    ) : interaction === 'agent' ? (
      <AgentSelectionPanel
        agents={controller.agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        selectedAgentName={panel.draft?.agentName}
        onClose={() => setInteraction(null)}
        onSelect={selectAgent}
      />
    ) : interaction === 'environment' ? (
      <EnvironmentWorkspacePanel
        environments={environments}
        current={panel.draft?.environment ?? null}
        onClose={() => setInteraction(null)}
        onSelect={selectEnvironment}
      />
    ) : interaction === 'history' ? (
      <HistoryBranchPanel
        entries={historyEntriesQuery.data ?? []}
        currentHeadEntryId={controller.thread?.headEntryId}
        loading={historyEntriesQuery.isLoading}
        queryError={historyEntriesQuery.error}
        pending={rebindMutation.isPending}
        rebindError={rebindMutation.error ? rebindErrorMessage(rebindMutation.error) : null}
        onClose={() => {
          setInteraction(null)
          rebindMutation.reset()
        }}
        onRebind={rebindTo}
      />
    ) : interaction === 'shortcuts' ? (
      <ThreadShortcutsPanel onClose={() => setInteraction(null)} />
    ) : null
  const commands = BOUND_PANE_COMMANDS
  const composer: ChatPanelComposerInput = {
    parts: controller.draft,
    // Pending 覆盖 in-flight HTTP 请求（同时禁用发送：canSend 已检查 disabled），
    // 加上 branch draft 与 buildBatch 就绪状态，确保一个 replayRef 不会服务并发的
    // CAS 请求。
    pending: controller.pending || rebindMutation.isPending,
    disabled:
      controller.pending
      || controller.disabled
      || rebindMutation.isPending
      || panel.branchState == null
      || panel.effectiveBase == null,
    onPartsChange: controller.setDraft,
    onHistoryPartsChange: (parts) => controller.setDraft(parts, 'history'),
    onSubmit: (payload, localDraft) => {
      void controller.submitMessage(payload, localDraft)
    },
    onCommand: handleCommand,
    commands,
    focusOnEscape: focused,
    interactionPanel,
    settings: draft == null ? undefined : {
      model: draft.model,
      models: controller.models,
      yoloEnabled: draft.yoloEnabled,
      onModelChange: selectModel,
      onYoloChange: setYoloEnabled,
    },
  }
  const activity: ChatPanelActivityInput = {
    working: controller.working,
    widgets: selectedRecord ? (
      <ThreadEventDetail record={selectedRecord} onClose={() => selectEvent(null)} />
    ) : null,
    onDecideTaskApproval: (targetThreadId, invocationId, decision) => {
      if (decision === 'DENY') {
        threadNotifications.markPermissionRejected()
      }
      void controller.decideApproval(invocationId, decision, targetThreadId)
    },
    actionError: rebindBlockedReason ?? panel.yoloError ?? controller.actionError,
    onDismissActionError: () => {
      setRebindBlockedReason(null)
      panel.dismissYoloError()
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
        mainView={mainView}
        composer={composer}
        activity={activity}
      />
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
