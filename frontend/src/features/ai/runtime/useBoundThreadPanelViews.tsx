import { useState, type RefObject } from 'react'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/ai/runtime/thread-panel'
import { useModelRequestDebug } from '@/features/ai/runtime/useModelRequestDebug'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import type { ThreadPaneLabels } from '@/features/ai/runtime/ThreadPane'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

/** Bound Thread 共用的 Conversation/Debug 视图和 Footer 投影（只读视图状态）。 */
export interface BoundThreadPreviewOptions {
  onPreview?: () => void
  previewLoading?: boolean
  previewDisabled?: boolean
  previewDisabledReason?: string | null
  previewError?: string | null
}

export function useBoundThreadPanelViews(
  threadId: string,
  controller: {
    bodyRef: RefObject<HTMLDivElement | null>
    events: ThreadEventRecord[]
    working: boolean
  },
  previewOptions?: BoundThreadPreviewOptions,
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

  const selectDebugInspector = (selection: DebugInspectorSelection | null) => {
    selectEvent(null)
    setDebugSelection(selection)
    if (selection != null && mode !== 'debug') {
      internalSwitchMode('debug')
    }
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
          onPreview={previewOptions?.onPreview}
          previewLoading={previewOptions?.previewLoading}
          previewDisabled={previewOptions?.previewDisabled}
          previewDisabledReason={previewOptions?.previewDisabledReason}
          previewError={previewOptions?.previewError}
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
    selectDebugInspector,
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
): ThreadPaneLabels {
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
