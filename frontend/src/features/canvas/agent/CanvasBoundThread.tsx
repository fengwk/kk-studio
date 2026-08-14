import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ChatPanel,
  ThreadEventDetail,
  ThreadEventView,
  ThreadShortcutsPanel,
  useMainViewScrollRestore,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type ChatPanelFooterInput,
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
  type ThreadCommand,
  type ThreadPanelMainView,
  useAgentThreadController,
} from '@/features/ai/runtime'
import {
  threadCommandsForActiveView,
  threadCommandsForScene,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import type { ThreadEventItem } from '@/features/ai/runtime/thread-events'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import { branchDraftFromThread } from '@/features/ai/chat/branch-draft'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
import type { UUIDString } from '@/shared/api/contracts/studio'

/**
 * Canvas 绑定 Thread：复用共享 useAgentThreadController + ChatPanel。
 * buildBatch 只发送 USER_MESSAGE（branch settings 已在 Thread 创建时烘焙）；
 * footer 展示持久化 label，agent/environment/yolo 的选择只属于 blank 流程。
 * 不携带任何隐式 Canvas 上下文 —— 绑定只来自 document.threadId。
 *
 * 命令能力（canvas-bound）：controller 只支持 stop（upload 由 ThreadComposer 处理
 * 文件选择），因此 agent/environment/yolo/tree/new/thread 全部禁用，
 * 只启用 stop/upload/events/conversation/shortcuts。
 */
const CANVAS_BOUND_COMMANDS: ThreadCommand[] = threadCommandsForScene('canvas-bound')

export function CanvasBoundThread({
  threadId,
  environments = [],
}: {
  threadId: UUIDString
  environments?: LiveEnvironmentDTO[]
}) {
  const environmentReadyByName = useMemo(
    () => new Map(environments.map((environment) => [environment.name, environment.ready])),
    [environments],
  )
  // 互斥主视图与 Event detail：threadId 改变（document 重新绑定）时重置回 conversation，
  // 同时清零两个主视图的 scrollTop。
  const buildBatchRef = useRef<((parts: ComposerPart[]) => CommandBatchPlan | null) | null>(null)
  const controller = useAgentThreadController(
    threadId,
    [],
    undefined,
    (parts) => buildBatchRef.current?.(parts) ?? null,
    environmentReadyByName,
  )
  const {
    mainView,
    switchMainView,
    reset: resetMainViewScroll,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  } = useMainViewScrollRestore(controller.bodyRef)
  const [eventDetail, setEventDetail] = useState<ThreadEventItem | null>(null)
  const [interaction, setInteraction] = useState<'shortcuts' | null>(null)
  const boundThreadIdRef = useRef<string | null>(null)
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    resetMainViewScroll()
    setEventDetail(null)
    setInteraction(null)
  }, [resetMainViewScroll, threadId])

  const buildBatch = useCallback(
    (parts: ComposerPart[]): CommandBatchPlan | null => {
      const thread = controller.thread
      if (!thread) {
        return null
      }
      const base = branchDraftFromThread(thread)
      return buildMessageBatchPlan({ thread, effectiveBase: base, draft: base, parts })
    },
    [controller.thread],
  )
  useEffect(() => {
    buildBatchRef.current = buildBatch
  })

  const labels: ChatPanelLabels = {
    agentName: controller.runtimeLabels.agentName,
    providerName: controller.runtimeLabels.providerName,
    modelName: controller.runtimeLabels.modelName,
    variantName: controller.runtimeLabels.variantName,
    environmentName: controller.runtimeLabels.environmentName,
    environmentReady: controller.runtimeLabels.environmentReady,
    contextWindow: controller.runtimeLabels.contextWindow,
  }
  const transcript: ChatPanelTranscriptInput = {
    timeline: controller.timeline,
    bodyRef: controller.bodyRef,
    // conversation 重新挂载时以 initialScrollTop 恢复保存位置（null 首次进入贴底）；
    // resetKey=threadId：重绑后新线程首次进入重新贴底。
    initialScrollTop: initialConversationScrollTop,
    resetKey: threadId,
    eventCount: controller.entries.length + controller.queuedCommands.length,
    loading: controller.messagesLoading,
    error: controller.messagesError,
    approvalPending: controller.approvalPending,
    onDecideApproval: (message, decision) => {
      if (message.invocationId) {
        void controller.decideApproval(message.invocationId, decision)
      }
    },
  }
  function handleCommand(command: ThreadCommand) {
    if (command.disabled) {
      return
    }
    switch (command.id) {
      case 'events':
        switchMainView('events')
        return
      case 'conversation':
        // 切回 conversation 同时关闭 event detail（只读 widget 随之卸载）。
        setEventDetail(null)
        switchMainView('conversation')
        return
      case 'shortcuts':
        setInteraction('shortcuts')
        return
      default:
        // canvas-bound 只投影 stop/upload/events/conversation/shortcuts；
        // upload 由 ThreadComposer 拦截，到达这里的只有 stop。
        controller.runCommand(command)
    }
  }
  // 互斥主视图：events 时替换 transcript 滚动区；detail 是 widget zone 的只读展示。
  const mainViewInput: ThreadPanelMainView = {
    events:
      mainView === 'events' ? (
        <ThreadEventView
          events={controller.events}
          bodyRef={eventsBodyRef}
          initialScrollTop={initialEventsScrollTop}
          detailOpen={eventDetail != null}
          onActivate={(event) => setEventDetail(event)}
          onCloseDetail={() => setEventDetail(null)}
        />
      ) : undefined,
  }
  // events 变化时按 id 刷新已选中的 detail（内容更新跟随新快照）；id 消失则清空。
  useEffect(() => {
    setEventDetail((current) => {
      if (current == null) {
        return current
      }
      const next = controller.events.find((event) => event.id === current.id)
      return next ?? null
    })
  }, [controller.events])
  // 当前已激活的主视图命令保持可见但禁用（events 激活时 /events 禁用）。
  const commands = useMemo(
    () => threadCommandsForActiveView(CANVAS_BOUND_COMMANDS, mainView),
    [mainView],
  )
  const composer: ChatPanelComposerInput = {
    parts: controller.draft,
    pending: controller.pending,
    disabled: controller.pending || controller.disabled,
    onPartsChange: controller.setDraft,
    onSubmit: (payload, localDraft) => {
      void controller.submitMessage(payload, localDraft)
    },
    onCommand: handleCommand,
    commands,
    focusOnEscape: true,
    interactionPanel:
      interaction === 'shortcuts' ? (
        <ThreadShortcutsPanel onClose={() => setInteraction(null)} />
      ) : undefined,
  }
  const footer: ChatPanelFooterInput = {
    yoloEnabled: controller.thread?.yoloEnabled,
  }
  const activity: ChatPanelActivityInput = {
    working: controller.working,
    widgets: eventDetail ? (
      <ThreadEventDetail item={eventDetail} onClose={() => setEventDetail(null)} />
    ) : null,
    actionError: controller.actionError,
    onDismissActionError: controller.dismissActionError,
  }

  return (
    <ChatPanel
      labels={labels}
      transcript={transcript}
      mainView={mainViewInput}
      composer={composer}
      footer={footer}
      activity={activity}
    />
  )
}
