import type { RefObject } from 'react'
import type { DebugInspectorSelection } from '@/features/thread/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadEventRecord } from '@/features/thread/runtime/thread-timeline/thread-events'
import type {
  ThreadModelRequestDebugData,
  ThreadProviderRequestPreviewData,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { TranslationKey } from '@/shared/i18n'

/** Debug 主视图的三个互斥区域；宽面板并排展示，窄面板用 Tab 切换。 */
export type DebugViewTab = 'preview' | 'events' | 'detail'

export const DEBUG_VIEW_TABS: readonly DebugViewTab[] = ['preview', 'events', 'detail']

/** 页签与对应列的可访问名称：窄面板由 tablist 提供，宽面板各列自述。 */
export const DEBUG_TAB_LABEL_KEYS: Record<DebugViewTab, TranslationKey> = {
  preview: 'ai.runtime.debug.tabPreview',
  events: 'ai.runtime.debug.tabEvents',
  detail: 'ai.runtime.debug.tabDetail',
}

/** Debug 视图的全部输入：事件历史、请求投影与两条只读的历史预览通道。 */
export interface ThreadEventViewProps {
  events: ThreadEventRecord[]
  historyLoading?: boolean
  historyError?: string | null
  onRetryHistory?: () => void
  selectedEventId: string | null
  onSelectedEventIdChange: (eventId: string | null) => void
  initialScrollTop?: number | null
  bodyRef?: RefObject<HTMLDivElement | null>
  debug?: ThreadModelRequestDebugData | null
  /** Debug 请求投影的独立读取状态：loading/error 不被吞成「暂无请求预览数据」空态。 */
  debugLoading?: boolean
  debugError?: string | null
  debugSelection?: DebugInspectorSelection | null
  onSelectInspector?: (selection: DebugInspectorSelection | null) => void
  /** 选中历史 ASSISTANT Entry 后按需读取到的调用前请求预览（只读）。 */
  historicalPreview?: ThreadProviderRequestPreviewData | null
  historicalPreviewLoading?: boolean
  historicalPreviewError?: string | null
  onRequestHistoricalPreview?: (entryId: string) => void
  onDismissHistoricalPreview?: () => void
}
