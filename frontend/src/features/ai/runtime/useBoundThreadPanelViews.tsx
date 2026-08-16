import { useMemo, type RefObject } from 'react'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/ai/runtime/thread-panel'
import { useSystemPromptPreview } from '@/features/ai/runtime/useSystemPromptPreview'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import {
  type ChatPanelLabels,
  type ChatPanelTranscriptInput,
} from '@/features/ai/runtime/ChatPanel'
import { useEnvironmentWorkspaceMetadata } from '@/features/ai/environment/useEnvironmentWorkspaceMetadata'
import type { ToolDialogueMessage, TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/contracts/ai-runtime'

/**
 * 绑定 Thread 面板共享的互斥主视图派生（Bound Chat 与 Canvas Bound 复用）：
 * Conversation/Event mode 与双 scroll 位置、system-prompt preview 按需拉取、
 * 当前选中 Event 的 detail record，以及 Events 主视图的 JSX 构建。
 *
 * 输入的 controller 只需满足被用到的结构化事实（bodyRef/events/working）。
 */
export function useBoundThreadPanelViews(
  threadId: string,
  controller: {
    bodyRef: RefObject<HTMLDivElement | null>
    events: ThreadEventRecord[]
    working: boolean
  },
) {
  const {
    mode,
    switchMode,
    selectedEventId,
    selectEvent,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  } = useThreadPanelViewState(threadId, controller.bodyRef, controller.events)
  const systemPrompt = useSystemPromptPreview(threadId, mode === 'events', controller.working)
  const selectedRecord =
    selectedEventId == null
      ? null
      : (controller.events.find((event) => event.id === selectedEventId) ?? null)
  // 互斥主视图：events 时替换 transcript 滚动区；detail 是 widget zone 的只读展示，
  // 不是 InteractionPanel（不隐藏 Composer、不抢焦点）。
  const mainView: ThreadPanelMainView = {
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
  return {
    mode,
    switchMode,
    selectedEventId,
    selectEvent,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
    systemPrompt,
    selectedRecord,
    mainView,
  }
}

/**
 * Footer 只投影已生效的 snapshot facts；pane-local draft 仅属于下一条 batch。
 * canonical 名称即展示身份；仅携带统一可用性标记（live 列表缺失/未知 => unavailable）。
 */
export function useBoundThreadPanelLabels(
  environments: LiveEnvironmentDTO[],
  controller: {
    runtimeLabels: {
      environment: EnvironmentBindingDTO | null
      contextWindow: number | undefined
    }
    branchUsage: TurnUsage | null
  },
): ChatPanelLabels {
  const environmentReadyByName = useMemo(
    () => new Map(environments.map((environment) => [environment.name, environment.ready])),
    [environments],
  )
  const environment = controller.runtimeLabels.environment
  const environmentReady =
    environment != null
      ? (environmentReadyByName.get(environment.name) ?? false)
      : undefined
  const { gitBranch } = useEnvironmentWorkspaceMetadata(environment, environmentReady)
  return {
    environment,
    environmentReady,
    gitBranch,
    branchUsage: controller.branchUsage,
    contextWindow: controller.runtimeLabels.contextWindow,
  }
}

/**
 * 绑定面板 transcript 输入：timeline/bodyRef/scroll 契约 + 带 id 的审批转发。
 * `onDenyApproval` 是 DENY 审批的附加副作用（Bound Chat 的通知权限回收）；Canvas 不传。
 */
export function buildBoundThreadTranscript(options: {
  controller: {
    timeline: ChatPanelTranscriptInput['timeline']
    bodyRef: RefObject<HTMLDivElement | null>
    entries: HarnessSessionEntryDTO[]
    queuedCommands: HarnessThreadCommandDTO[]
    messagesLoading: boolean
    messagesError: unknown
    approvalPending: boolean
    decideApproval: (invocationId: string, decision: 'ALLOW' | 'DENY') => Promise<void>
  }
  threadId: string
  initialConversationScrollTop: number | null
  onDenyApproval?: () => void
}): ChatPanelTranscriptInput {
  const { controller } = options
  return {
    timeline: controller.timeline,
    bodyRef: controller.bodyRef,
    // conversation 重新挂载时以 initialScrollTop 恢复保存位置（null 首次进入贴底）；
    // resetKey=threadId：重绑后新线程首次进入重新贴底。
    initialScrollTop: options.initialConversationScrollTop,
    resetKey: options.threadId,
    eventCount: controller.entries.length + controller.queuedCommands.length,
    loading: controller.messagesLoading,
    error: controller.messagesError,
    approvalPending: controller.approvalPending,
    onDecideApproval: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => {
      if (!message.invocationId) {
        return
      }
      if (decision === 'DENY') {
        options.onDenyApproval?.()
      }
      void controller.decideApproval(message.invocationId, decision)
    },
  }
}
