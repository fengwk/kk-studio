import { useMemo, type RefObject } from 'react'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/ai/runtime/thread-panel'
import { useSystemPromptPreview } from '@/features/ai/runtime/useSystemPromptPreview'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import type {
  ChatPanelLabels,
  ChatPanelTranscriptInput,
} from '@/features/ai/runtime/ChatPanel'
import type { ToolDialogueMessage, TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import type {
  HarnessSessionEntryDTO,
  HarnessThreadCommandDTO,
} from '@/shared/api/contracts/ai-runtime'

/** Bound Thread 共用的 Conversation/Debug 视图和 Footer 投影。 */
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
  const systemPrompt = useSystemPromptPreview(threadId, mode === 'debug', controller.working)
  const selectedRecord =
    selectedEventId == null
      ? null
      : (controller.events.find((event) => event.id === selectedEventId) ?? null)
  const mainView: ThreadPanelMainView = {
    debug:
      mode === 'debug' ? (
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

/** Footer 只展示已经生效的 snapshot facts，不能把 pane-local draft 混进来。 */
export function useBoundThreadPanelLabels(
  environments: EnvironmentCardDTO[],
  controller: {
    runtimeLabels: {
      environment: string | null
      contextWindow: number | undefined
    }
    branchUsage: TurnUsage | null
  },
): ChatPanelLabels {
  const environmentReadyById = useMemo(
    () => new Map(environments.map((environment) => [environment.id, environment.ready])),
    [environments],
  )
  const environmentId = controller.runtimeLabels.environment
  const boundEnvCard = environmentId
    ? environments.find((e) => e.id === environmentId)
    : undefined
  const environmentReady =
    boundEnvCard != null
      ? boundEnvCard.ready
      : environmentId != null
        ? (environmentReadyById.get(environmentId) ?? false)
        : undefined
  const environment = environmentId
    ? {
        environmentId,
        environmentName: boundEnvCard?.name ?? environmentId,
      }
    : null
  return {
    environment,
    environmentReady,
    branchUsage: controller.branchUsage,
    contextWindow: controller.runtimeLabels.contextWindow,
  }
}

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
      // 返回精确的决策请求：审批条据此在本地 pending 与 settle 之间同步反馈。
      return controller.decideApproval(message.invocationId, decision)
    },
  }
}
