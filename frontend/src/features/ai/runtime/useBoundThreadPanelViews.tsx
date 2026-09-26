import { useState, type RefObject } from 'react'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/ai/runtime/thread-panel'
import { useModelRequestDebug } from '@/features/ai/runtime/useModelRequestDebug'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
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
    switchMode: internalSwitchMode,
    selectedEventId,
    selectEvent,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  } = useThreadPanelViewState(threadId, controller.bodyRef, controller.events)
  const { debug } = useModelRequestDebug(threadId, mode === 'debug', controller.working)
  const [debugSelection, setDebugSelection] = useState<DebugInspectorSelection | null>(null)

  const switchMode = (nextMode: 'conversation' | 'debug') => {
    setDebugSelection(null)
    internalSwitchMode(nextMode)
  }

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
          onSelectedEventIdChange={(id) => {
            if (id != null) {
              setDebugSelection(null)
            }
            selectEvent(id)
          }}
          bodyRef={eventsBodyRef}
          initialScrollTop={initialEventsScrollTop}
          debug={debug}
          debugSelection={debugSelection}
          onSelectInspector={(selection) => {
            if (selection != null) {
              selectEvent(null)
            }
            setDebugSelection(selection)
          }}
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
    debug,
    debugSelection,
    setDebugSelection,
    selectedRecord,
    mainView,
  }
}

/** Footer 只展示已经生效的 snapshot facts，不能把 pane-local draft 混进来。 */
export function useBoundThreadPanelLabels(
  environments: EnvironmentCardDTO[],
  controller: {
    runtimeLabels: {
      environmentName: string | null
      contextWindow: number | undefined
    }
    branchUsage: TurnUsage | null
  },
): ChatPanelLabels {
  const environmentName = controller.runtimeLabels.environmentName
  const boundEnvCard = environmentName
    ? environments.find((e) => e.name === environmentName)
    : undefined
  const environmentReady =
    environmentName == null
      ? undefined
      : boundEnvCard != null
        ? boundEnvCard.ready
        : false
  const environment = environmentName
    ? {
        environmentId: boundEnvCard?.id ?? environmentName,
        environmentName,
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
