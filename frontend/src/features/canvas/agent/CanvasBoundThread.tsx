import { useEffect, useRef, useState } from 'react'
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
  type ThreadCommand,
} from '@/features/ai/runtime'
import {
  threadCommandsForScene,
} from '@/features/ai/runtime/thread-panel/thread-commands'
import { AgentSelectionPanel } from '@/features/ai/chat/SelectionPanel'
import { EnvironmentWorkspacePanel } from '@/features/ai/chat/EnvironmentWorkspacePanel'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import type { UUIDString } from '@/shared/api/contracts/studio'

/**
 * Canvas 绑定 Thread：复用共享 useBoundBranchPanel + ChatPanel。Branch settings
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
  const panel = useBoundBranchPanel({ threadId })
  const { controller } = panel
  // 互斥主视图与 Event detail：threadId 改变（document 重新绑定）时重置回 conversation，
  // 同时清零两个主视图的 scrollTop（视图派生迁移到共享 useBoundThreadPanelViews）。
  const {
    mode,
    switchMode,
    selectEvent,
    initialConversationScrollTop,
    selectedRecord,
    mainView,
  } = useBoundThreadPanelViews(threadId, controller)
  const [interaction, setInteraction] = useState<
    'agent' | 'environment' | 'shortcuts' | null
  >(null)
  const boundThreadIdRef = useRef<string | null>(null)
  useEffect(() => {
    if (boundThreadIdRef.current === threadId) {
      return
    }
    boundThreadIdRef.current = threadId
    // branch draft 由 useBoundBranchPanel 在 threadId 变化时重置；主视图/Event
    // 状态由 useThreadPanelViewState 重置。
    setInteraction(null)
  }, [threadId])

  const labels = useBoundThreadPanelLabels(environments, controller)
  const transcript = buildBoundThreadTranscript({
    controller,
    threadId,
    initialConversationScrollTop,
  })

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
        panel.setYoloEnabled(!(panel.draft?.yoloEnabled ?? false))
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

  function selectAgent(agentName: string) {
    if (panel.selectAgent(agentName)) {
      setInteraction(null)
    }
  }

  function selectEnvironment(environment: EnvironmentBindingDTO | null) {
    panel.selectEnvironment(environment)
    setInteraction(null)
  }

  const draft = panel.draft
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

  const commands = CANVAS_BOUND_COMMANDS
  const composer: ChatPanelComposerInput = {
    parts: controller.draft,
    pending: controller.pending,
    disabled:
      controller.pending
      || controller.disabled
      || panel.branchState == null
      || panel.effectiveBase == null,
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
      onModelChange: panel.selectModel,
      onYoloChange: panel.setYoloEnabled,
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
      mainView={mainView}
      composer={composer}
      activity={activity}
    />
  )
}
