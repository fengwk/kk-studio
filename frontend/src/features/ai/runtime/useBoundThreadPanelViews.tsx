import { useState, type RefObject } from 'react'
import { useI18n } from '@/shared/i18n'
import '@/features/ai/runtime/thread-panel/debug-view-toolbar.css'
import {
  ThreadEventView,
  useThreadPanelViewState,
  type ThreadPanelMainView,
} from '@/features/ai/runtime/thread-panel'
import { useModelRequestDebug, useHistoricalRequestPreview } from '@/features/ai/runtime/useModelRequestDebug'
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
    /** 当前 Session，用于按需读取历史 Entry 的调用前请求预览（只读）。 */
    sessionId: string | null
  },
  previewOptions?: BoundThreadPreviewOptions,
) {
  const { t } = useI18n()
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
  const historicalPreview = useHistoricalRequestPreview(controller.sessionId)
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
          <div className="thread-debug-toolbar">
            <button
              type="button"
              className="ghost-btn thread-debug-back"
              onClick={() => switchMode('conversation')}
            >
              {t('ai.runtime.debug.backToConversation')}
            </button>
            {draftPreviewTrigger ? (
              <button
                type="button"
                className="ghost-btn thread-debug-preview"
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
              </button>
            ) : null}
          </div>
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
