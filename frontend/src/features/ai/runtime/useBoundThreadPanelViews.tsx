import { useEffect, useMemo, useRef, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import '@/features/ai/runtime/thread-panel/debug-view-toolbar.css'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/ai/runtime/thread-panel'
import { useModelRequestDebug, useHistoricalRequestPreview } from '@/features/ai/runtime/useModelRequestDebug'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadPaneLabels } from '@/features/ai/runtime/ThreadPane'
import type { ThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

/**
 * Bound Thread 只读视图需要的投影字段；生产 root/readOnly 都传入完整 {@link ThreadProjection}
 * （它是本契约的超集），因此无需另建 controller 或订阅。
 */
export type BoundThreadPanelController = Pick<
  ThreadProjection,
  'bodyRef' | 'events' | 'sessionId' | 'thread' | 'modelInvocation' | 'models'
>

/** Bound Thread 共用的 Conversation/Debug 视图和 Footer 投影（只读视图状态）。 */
export interface BoundThreadPreviewOptions {
  /**
   * 本地视图身份：本地分支/新建草稿没有 API threadId（都传 ""），Debug 模式、滚动位置
   * 与检查器选中必须按目标稳定字段区分，否则换绑到另一份草稿会复用上一份的 Debug 视图
   * 与预览结果。缺省时回退到真实 threadId（已绑定 Thread 与只读查看的唯一身份）。
   */
  viewKey?: string
  onPreview?: () => void
  previewLoading?: boolean
  previewDisabled?: boolean
  previewDisabledReason?: string | null
  previewError?: string | null
  historyLoading?: boolean
  historyError?: string | null
  onRetryHistory?: () => void
}

export function useBoundThreadPanelViews(
  threadId: string,
  controller: BoundThreadPanelController,
  previewOptions?: BoundThreadPreviewOptions,
) {
  const { t } = useI18n()
  // 视图身份只用于本地视图状态（模式/滚动/选中）；API 调用仍只用真实 threadId。
  const viewKey = previewOptions?.viewKey ?? threadId
  const {
    mode,
    switchMode: internalSwitchMode,
    selectedEventId,
    selectEvent,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  } = useThreadPanelViewState(viewKey, controller.bodyRef, controller.events)

  // Debug 请求投影的读取身份：调用 id/phase/requestHead、head 与规划 settings/目录事实。
  // working 一直为 true 的连续 model -> tool -> 下一 model 期间，调用身份已经改变，
  // 因此 revision 必须由这些事实构成，而不是 working 的一次 true/false。
  const debugRevision = useMemo(() => {
    const settings = controller.thread?.branchSettings
    const catalog = controller.models
      .map((item) => `${item.providerName}/${item.name}`)
      .join(',')
    return [
      viewKey,
      controller.thread?.threadId ?? '',
      controller.thread?.version ?? '',
      controller.thread?.status ?? '',
      controller.thread?.headEntryId ?? '',
      controller.modelInvocation?.id ?? '',
      controller.modelInvocation?.status ?? '',
      controller.modelInvocation?.requestHeadEntryId ?? '',
      settings?.model?.providerName ?? '',
      settings?.model?.modelName ?? '',
      settings?.model?.variant ?? '',
      settings?.agentName ?? '',
      settings?.goal?.id ?? '',
      settings?.environmentName ?? '',
      catalog,
    ].join('|')
  }, [controller.modelInvocation, controller.thread, controller.models, viewKey])

  const {
    debug,
    loading: debugLoading,
    error: debugError,
  } = useModelRequestDebug(threadId, mode === 'debug', debugRevision)
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
  // 本地分支草稿没有可读取的 per-thread Debug 投影，预览入口因此不在请求预览面板里；
  // 由 Debug 工具条提供与绑定 Thread 同名的「下一次请求预览」触发点，走同一份
  // previewOptions（会话级 branch preview，不创建 Thread）。绑定 Thread 仍只由面板内的
  // 标题按钮触发，避免出现两个入口。
  const draftPreviewTrigger = threadId === '' && previewOptions?.onPreview != null
  const previewTitle = t('ai.runtime.debug.previewTitle')
  const previewDisabledReason = previewOptions?.previewDisabledReason ?? null
  const previewTriggerLabel = previewOptions?.previewLoading
    ? t('ai.runtime.composer.previewLoading')
    : previewDisabledReason
      ? `${previewTitle} (${previewDisabledReason})`
      : previewTitle
  const mainView: ThreadPanelMainView = {
    debug:
      mode === 'debug' ? (
        <>
          {/* 控制区保持挂载但隐藏；退出只切换视图，不修改草稿或 pane 绑定。 */}
          {draftPreviewTrigger ? <div className="thread-debug-toolbar">
              <Button
                variant="ghost"
                size="compact"
                className="thread-debug-preview"
                disabled={
                  previewOptions?.previewDisabled
                  || previewOptions?.previewLoading
                  || previewOptions?.onPreview == null
                }
                aria-label={previewTriggerLabel}
                title={previewTriggerLabel}
                onClick={() => previewOptions?.onPreview?.()}
              >
                <span>{previewTitle}</span>
              </Button>
          </div> : null}
          <ThreadEventView
            events={controller.events}
            historyLoading={previewOptions?.historyLoading}
            historyError={previewOptions?.historyError}
            onRetryHistory={previewOptions?.onRetryHistory}
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
            onPreview={previewOptions?.onPreview}
            previewLoading={previewOptions?.previewLoading}
            previewDisabled={previewOptions?.previewDisabled}
            previewDisabledReason={previewOptions?.previewDisabledReason}
            previewError={previewOptions?.previewError}
            historicalPreview={historicalPreview.preview}
            historicalPreviewLoading={historicalPreview.loading}
            historicalPreviewError={
              historicalPreview.error ? t('ai.runtime.debug.previewFailed') : null
            }
            onRequestHistoricalPreview={historicalPreview.request}
            onDismissHistoricalPreview={historicalPreview.dismiss}
          />
        </>
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
