import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ChatPanel,
  ThreadEventDetail,
  ThreadEventView,
  ThreadShortcutsPanel,
  useThreadPanelViewState,
  type ChatPanelActivityInput,
  type ChatPanelComposerInput,
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
  type ThreadCommand,
  type ThreadPanelMainView,
  useAgentThreadController,
} from '@/features/ai/runtime'
import { useSystemPromptPreview } from '@/features/ai/runtime/useSystemPromptPreview'
import {
  threadCommandsForScene,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import {
  buildMessageBatchPlan,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import {
  activeToolsFromAgent,
  branchDraftFromThread,
  projectPendingTarget,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import { AgentSelectionPanel } from '@/features/ai/chat/SelectionPanel'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import { useEnvironmentWorkspaceMetadata } from '@/features/ai/environment/useEnvironmentWorkspaceMetadata'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import type { UUIDString } from '@/shared/api/contracts/studio'

/**
 * Canvas 绑定 Thread：复用共享 useAgentThreadController + ChatPanel。Branch settings
 * 与 Chat Bound 一样是 pane-local draft：下一条消息把最小 SET_* diff 与
 * USER_MESSAGE 作为同一 batch 原子发送。不携带任何隐式 Canvas 上下文——绑定
 * 只来自 document.threadId。
 *
 * 命令能力（canvas-bound）：agent/environment/yolo 可编辑 pane draft；stop 由
 * controller 处理，upload 由 ThreadComposer 处理；tree/new/thread 仍禁用。
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
  )
  const [branchState, setBranchState] = useState<{
    base: BranchDraft
    draft: BranchDraft
  } | null>(null)
  const {
    mode,
    switchMode,
    selectedEventId,
    selectEvent,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  } = useThreadPanelViewState(threadId, controller.bodyRef, controller.events)
  const systemPrompt = useSystemPromptPreview(
    threadId,
    mode === 'events',
    controller.working,
  )
  const selectedRecord =
    selectedEventId == null
      ? null
      : (controller.events.find((event) => event.id === selectedEventId) ?? null)
  const [interaction, setInteraction] = useState<
    'agent' | 'environment' | 'shortcuts' | null
  >(null)
  const boundThreadIdRef = useRef<string | null>(null)
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    // 主视图/Event 状态由 useThreadPanelViewState 在 threadId 变化时重置。
    setBranchState(null)
    setInteraction(null)
  }, [threadId])

  useEffect(() => {
    const thread = controller.thread
    if (!thread) {
      return
    }
    setBranchState((current) => {
      const snapshotDraft = branchDraftFromThread(thread)
      if (current == null) {
        return {
          base: snapshotDraft,
          draft: projectPendingTarget(snapshotDraft, controller.queuedCommands),
        }
      }
      return { ...current, base: snapshotDraft }
    })
  }, [controller.queuedCommands, controller.thread])

  const effectiveBase = useMemo(
    () => branchState == null
      ? null
      : projectPendingTarget(branchState.base, controller.queuedCommands),
    [branchState, controller.queuedCommands],
  )

  const buildBatch = useCallback(
    (parts: ComposerPart[]): CommandBatchPlan | null => {
      const thread = controller.thread
      if (!thread || branchState == null || effectiveBase == null) {
        return null
      }
      return buildMessageBatchPlan({
        thread,
        effectiveBase,
        draft: branchState.draft,
        parts,
      })
    },
    [branchState, controller.thread, effectiveBase],
  )
  useEffect(() => {
    buildBatchRef.current = buildBatch
  })

  const draft = branchState?.draft
  // Footer 只投影已生效的 snapshot facts；pane-local draft 仅属于下一条 batch。
  const environment = controller.runtimeLabels.environment
  const environmentReady =
    environment != null
      ? (environmentReadyByName.get(environment.name) ?? false)
      : undefined
  const { gitBranch } = useEnvironmentWorkspaceMetadata(environment, environmentReady)
  const labels: ChatPanelLabels = {
    environment,
    environmentReady,
    gitBranch,
    branchUsage: controller.branchUsage,
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
      case 'agent':
        setInteraction('agent')
        return
      case 'environment':
        setInteraction('environment')
        return
      case 'yolo':
        setYoloEnabled(!(branchState?.draft.yoloEnabled ?? false))
        return
      case 'events':
        switchMode(mode === 'events' ? 'conversation' : 'events')
        return
      case 'shortcuts':
        setInteraction('shortcuts')
        return
      default:
        // upload 由 ThreadComposer 拦截；到达这里的其它可用命令只有 stop。
        controller.runCommand(command)
    }
  }

  function editDraft(patch: Partial<BranchDraft>) {
    setBranchState((current) =>
      current == null
        ? current
        : { ...current, draft: { ...current.draft, ...patch } },
    )
  }

  function selectAgent(agentName: string) {
    const agent = controller.agents.find((candidate) => candidate.name === agentName)
    if (agent == null) {
      return
    }
    editDraft({ agentName, activeTools: activeToolsFromAgent(agent) })
    setInteraction(null)
  }

  function selectEnvironment(environment: EnvironmentBindingDTO | null) {
    editDraft({ environment })
    setInteraction(null)
  }

  function setYoloEnabled(enabled: boolean) {
    editDraft({ yoloEnabled: enabled })
  }
  const interactionPanel =
    interaction === 'agent' ? (
      <AgentSelectionPanel
        agents={controller.agents.map((agent) => ({
          name: agent.name,
          description: agent.description,
        }))}
        selectedAgentName={draft?.agentName}
        onSelect={selectAgent}
        onClose={() => setInteraction(null)}
      />
    ) : interaction === 'environment' ? (
      <EnvironmentWorkspacePanel
        environments={environments}
        current={draft?.environment ?? null}
        onSelect={selectEnvironment}
        onClose={() => setInteraction(null)}
      />
    ) : interaction === 'shortcuts' ? (
      <ThreadShortcutsPanel onClose={() => setInteraction(null)} />
    ) : undefined

  // 互斥主视图：events 时替换 transcript 滚动区；detail 是 widget zone 的只读展示。
  const mainViewInput: ThreadPanelMainView = {
    events:
      mode === 'events' ? (
        <ThreadEventView
          events={controller.events}
          selectedEventId={selectedEventId}
          onSelectedEventIdChange={selectEvent}
          bodyRef={eventsBodyRef}
          initialScrollTop={initialEventsScrollTop}
          systemPrompt={systemPrompt}
        />
      ) : undefined,
  }
  const commands = CANVAS_BOUND_COMMANDS
  const composer: ChatPanelComposerInput = {
    parts: controller.draft,
    pending: controller.pending,
    disabled:
      controller.pending
      || controller.disabled
      || branchState == null
      || effectiveBase == null,
    onPartsChange: controller.setDraft,
    onSubmit: (payload, localDraft) => {
      void controller.submitMessage(payload, localDraft)
    },
    onCommand: handleCommand,
    commands,
    focusOnEscape: true,
    interactionPanel,
    settings: draft == null ? undefined : {
      model: draft.model,
      models: controller.models,
      yoloEnabled: draft.yoloEnabled,
      onModelChange: (model) => editDraft({ model }),
      onYoloChange: setYoloEnabled,
    },
  }
  const activity: ChatPanelActivityInput = {
    working: controller.working,
    widgets: selectedRecord ? (
      <ThreadEventDetail record={selectedRecord} onClose={() => selectEvent(null)} />
    ) : null,
    onDecideTaskApproval: (targetThreadId, invocationId, decision) => {
      void controller.decideApproval(invocationId, decision, targetThreadId)
    },
    actionError: controller.actionError,
    onDismissActionError: controller.dismissActionError,
  }

  return (
    <ChatPanel
      labels={labels}
      transcript={transcript}
      mainView={mainViewInput}
      composer={composer}
      activity={activity}
    />
  )
}
