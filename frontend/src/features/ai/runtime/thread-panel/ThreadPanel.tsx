import { type ReactNode, type Ref, type RefObject } from 'react'
import { ThreadConversationView } from '@/features/ai/runtime/thread-panel/ThreadConversationView'
import { ThreadErrorPanel } from '@/features/ai/runtime/thread-panel/ThreadErrorPanel'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'
import type {
  ComposerPreviewReadiness,
  ThreadComposerHandle,
} from '@/features/ai/runtime/thread-panel/ThreadComposer'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import type { ThreadComposerSettingsInput } from '@/features/ai/runtime/thread-panel/ThreadComposerControls'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type {
  DialogueMessage,
  QueuedThreadMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/**
 * Transcript 区域：持久 timeline + 实时 mailbox 消息。
 * 由调用方提供已加载的数据；该面板不会访问 controller。
 */
export interface ThreadPanelTranscriptInput {
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
  loading: boolean
  error: unknown
  errorText?: string
  onRetry?: () => void
  emptyText?: string
  bodyRef: RefObject<HTMLDivElement | null>
  /** 重新进入 conversation 视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 变化时重置 stick 并重新贴底（Thread 重绑后新线程首次进入）。 */
  resetKey?: string | number | null
  /** 非消息内容的变化计数（控制 Entry/queued 等），用于贴底再评估。 */
  eventCount?: number
}

/**
 * 主视图槽位：Conversation 与 Debug 互斥，只渲染一个主滚动区（不并排、无 tabs）。
 * 传入 debug 时替换 transcript 滚动区；Composer/queue/working 保持挂载，切换不丢状态。
 */
export interface ThreadPanelMainView {
  debug?: ReactNode
}

/**
 * Composer 区域：slash 命令输入 + 附件 strip + 发送按钮。所有回调都必填，因为
 * composer 是纯受控组件，自身不持有 parts 状态（上传注册表在组件内部）。
 *
 * 该输入契约由根控制区（`RootThreadControlArea`）实现；只读子代理视图不构造它，
 * 因此不会挂载草稿、上传与人工执行 Hook。
 */
export interface ThreadPanelComposerInput {
  parts: ComposerPart[]
  pending: boolean
  disabled: boolean
  onPartsChange: (parts: ComposerPart[]) => void
  onHistoryPartsChange?: (parts: ComposerPart[]) => void
  /** 提交载荷：payload（server uploadId）与 localDraft（客户端 localId 快照）分开传递。 */
  onSubmit: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  onSubmitGoal?: (goalText: string, localDraft: ComposerPart[]) => void
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
  /** 当前交互作用域是否允许 Escape 把焦点恢复到此 Composer。 */
  focusOnEscape?: boolean
  /**
   * 被覆盖（例如根面板正在被查看层覆盖）时挂起：不激活编辑器、不响应快捷键，
   * 也不抢焦点；草稿、上传与展开状态原地保留。
   */
  suspended?: boolean
  /** 与 Composer 互斥的轻量选择/操作面板；Composer 保持挂载以保留草稿上传状态。 */
  interactionPanel?: ReactNode
  /** 双层 Composer 底栏的受控 Permission 与 Model/Variant 设置。 */
  settings?: ThreadComposerSettingsInput
  scope?: string
  composerRef?: Ref<ThreadComposerHandle>
  onPreviewReadinessChange?: (readiness: ComposerPreviewReadiness) => void
}

/**
 * Activity 区域：工作状态、排队中的输入，以及可选的可关闭反馈横幅。
 */
export interface ThreadPanelActivityInput {
  working: boolean
  workingLabel?: string
  widgets?: ReactNode
  actionError?: string | null
  onDismissActionError?: () => void
}

/**
 * 可选的布局槽位：在主列之前渲染常驻侧边栏，以及在 composer 之后渲染 footer。
 * 它们刻意暴露为原生 ReactNode，以便调用方自由组合任意展示契约
 * （status、history 快捷键等），而无需改动本面板。
 */
interface ThreadPanelSlots {
  sidebar?: ReactNode
  footer?: ReactNode
}

interface ThreadPanelProps {
  transcript: ThreadPanelTranscriptInput
  /** 互斥主视图：传入 debug 时替换 transcript（Debug view）。 */
  mainView?: ThreadPanelMainView
  /**
   * 根面板的输入与控制区；只读子代理只在顶部标题区提供返回导航。
   * 缺省时面板不渲染任何输入区，也不存在草稿或提交入口。
   */
  controls?: ReactNode
  activity: ThreadPanelActivityInput
  slots?: ThreadPanelSlots
  /** 主列顶部的内容标题（如绑定 Thread 的名称与重命名入口）；不占滚动区。 */
  heading?: ReactNode
}

/**
 * 全宽 thread 面板（只读展示）：
 * Conversation/Debug 互斥主滚动区 -> 装饰性 widget/队列 -> 可选输入与控制区 -> footer
 */
export function ThreadPanel({ transcript, mainView, controls, activity, slots, heading }: ThreadPanelProps) {
  return (
    <section className="chat-shell thread-panel">
      {slots?.sidebar}
      <main className="chat-main thread-panel-main">
        {mainView?.debug == null ? heading : null}
        {mainView?.debug ?? (
          <ThreadConversationView
            messages={transcript.messages}
            loading={transcript.loading}
            error={transcript.error}
            errorText={transcript.errorText}
            onRetry={transcript.onRetry}
            emptyText={transcript.emptyText}
            bodyRef={transcript.bodyRef}
            initialScrollTop={transcript.initialScrollTop}
            resetKey={transcript.resetKey}
            eventCount={transcript.eventCount}
          />
        )}
        {mainView?.debug == null ? <ThreadWidgetStack
          working={activity.working}
          workingLabel={activity.workingLabel}
          queuedMessages={transcript.queuedMessages}
        >
          {activity.widgets}
        </ThreadWidgetStack> : null}
        {mainView?.debug == null && activity.actionError ? (
          <ThreadErrorPanel message={activity.actionError} onDismiss={activity.onDismissActionError} />
        ) : null}
        {controls}
        {mainView?.debug == null ? slots?.footer : null}
      </main>
    </section>
  )
}
