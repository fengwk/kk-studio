import { useEffect, useMemo, useRef, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/thread/runtime/thread-panel'
import { useModelRequestDebug, useHistoricalRequestPreview } from '@/features/thread/runtime/queries/useModelRequestDebug'
import type { DebugInspectorSelection } from '@/features/thread/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadPaneLabels } from '@/features/thread/runtime/panes/ThreadPane'
import type { ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import type { TurnUsage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import type {
  EnvironmentSummaryDTO,
  HarnessModelRequestDebugRequestDTO,
} from '@/shared/api/generated/contracts'

/**
 * Bound Thread 只读视图需要的投影字段；生产 root/readOnly 都传入完整 {@link ThreadProjection}
 * （它是本契约的超集），因此无需另建 controller 或订阅。
 */
export type BoundThreadPanelController = Pick<
  ThreadProjection,
  'bodyRef' | 'events' | 'sessionId' | 'thread' | 'activeAssistantOutput' | 'models'
>

/** Bound Thread 共用的 Conversation/Debug 视图和 Footer 投影（只读视图状态）。 */
export function useBoundThreadPanelViews(threadId: string, controller: BoundThreadPanelController) {
  const { t } = useI18n()
  const viewKey = threadId
  const {
    mode,
    switchMode: internalSwitchMode,
    selectedEventId,
    selectEvent,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  } = useThreadPanelViewState(viewKey, controller.bodyRef, controller.events)

  const rawDebugModel = getBuiltinModelSelection(controller.thread?.branchSettings?.execution)
  const debugModelName = rawDebugModel?.modelName ?? null
  const debugModelVariant = rawDebugModel?.variant ?? null
  const debugEnvironmentName = controller.thread?.branchSettings?.environmentName ?? null

  const debugRequest = useMemo<HarnessModelRequestDebugRequestDTO | null>(() => {
    if (!debugModelName) {
      return null
    }
    return {
      model: {
        modelName: debugModelName,
        variant: debugModelVariant,
      },
      environmentName: debugEnvironmentName,
    }
  }, [debugModelName, debugModelVariant, debugEnvironmentName])

  // Debug 请求投影的读取身份：调用 id/phase/requestHead、head 与规划 settings/目录事实。
  // working 一直为 true 的连续 model -> tool -> 下一 model 期间，调用身份已经改变，
  // 因此 revision 必须由这些事实构成，而不是 working 的一次 true/false。
  const debugRevision = useMemo(() => {
    const settings = controller.thread?.branchSettings
    const model = debugRequest?.model ?? getBuiltinModelSelection(settings?.execution)
    const environmentName = debugRequest !== null
      ? debugRequest.environmentName
      : (settings?.environmentName ?? null)
    const catalog = controller.models
      .map((item) => item.name)
      .join(',')
    return [
      viewKey,
      controller.thread?.threadId ?? '',
      controller.thread?.version ?? '',
      controller.thread?.status ?? '',
      controller.thread?.headEntryId ?? '',
      controller.activeAssistantOutput?.generationId ?? '',
      controller.activeAssistantOutput?.status ?? '',
      controller.activeAssistantOutput?.requestHeadEntryId ?? '',
      model?.modelName ?? '',
      model?.variant ?? '',
      settings?.agentName ?? '',
      environmentName ?? '',
      catalog,
    ].join('|')
  }, [controller.activeAssistantOutput, controller.thread, controller.models, debugRequest, viewKey])

  const {
    debug,
    loading: debugLoading,
    error: debugError,
  } = useModelRequestDebug(threadId, mode === 'debug', debugRevision, debugRequest)
  const historicalPreview = useHistoricalRequestPreview(controller.sessionId, viewKey)
  const lastSelectedRef = useRef(selectedEventId)
  useEffect(() => {
    if (lastSelectedRef.current !== selectedEventId) {
      lastSelectedRef.current = selectedEventId
      historicalPreview.dismiss()
    }
  }, [selectedEventId, historicalPreview])
  const [debugSelection, setDebugSelection] = useState<DebugInspectorSelection | null>(null)

  // 检查器选中与视图状态同属一个身份：身份变化时必须一起清空，否则会残留旧目标的预览。
  const lastViewKeyRef = useRef(viewKey)
  useEffect(() => {
    if (lastViewKeyRef.current === viewKey) {
      return
    }
    lastViewKeyRef.current = viewKey
    setDebugSelection(null)
  }, [viewKey])

  const switchMode = (nextMode: 'conversation' | 'debug') => {
    historicalPreview.dismiss()
    setDebugSelection(null)
    internalSwitchMode(nextMode)
  }

  const selectDebugInspector = (selection: DebugInspectorSelection | null) => {
    historicalPreview.dismiss()
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
          debugLoading={debugLoading}
          debugError={debugError ? t('ai.runtime.debug.previewLoadFailed') : null}
          debugSelection={debugSelection}
          onSelectInspector={(selection) => {
            historicalPreview.dismiss()
            if (selection != null) {
              selectEvent(null)
            }
            setDebugSelection(selection)
          }}
          historicalPreview={historicalPreview.preview}
          historicalPreviewLoading={historicalPreview.loading}
          historicalPreviewError={
            historicalPreview.error ? t('ai.runtime.debug.previewFailed') : null
          }
          onRequestHistoricalPreview={historicalPreview.request}
          onDismissHistoricalPreview={historicalPreview.dismiss}
        />
      ) : undefined,
  }
  return {
    viewKey,
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
  environments: ReadonlyArray<Pick<EnvironmentSummaryDTO, 'id' | 'name' | 'ready'>>,
  controller: {
    runtimeLabels: {
      environmentName: string | null
      contextWindow: number | undefined
    }
    branchUsage: TurnUsage | null
  },
): ThreadPaneLabels {
  const environmentName = controller.runtimeLabels.environmentName
  const boundEnvironment = environmentName
    ? environments.find((e) => e.name === environmentName)
    : undefined
  const environmentReady =
    environmentName == null
      ? undefined
      : boundEnvironment != null
        ? boundEnvironment.ready
        : false
  const environment = environmentName
    ? {
        environmentId: boundEnvironment?.id ?? environmentName,
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
