import { useMemo, type ReactNode, type RefObject } from 'react'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { ThreadConversationView } from '@/features/ai/runtime/thread-panel/ThreadConversationView'
import { ThreadErrorPanel } from '@/features/ai/runtime/thread-panel/ThreadErrorPanel'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import type { ThreadComposerSettingsInput } from '@/features/ai/runtime/thread-panel/ThreadComposerControls'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ToolDialogueMessage,
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
  bodyRef: RefObject<HTMLDivElement | null>
  /** 重新进入 conversation 视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 变化时重置 stick 并重新贴底（Thread 重绑后新线程首次进入）。 */
  resetKey?: string | number | null
  /** 非消息内容的变化计数（控制 Entry/queued 等），用于贴底再评估。 */
  eventCount?: number
  /** 处理待决 ToolInvocation 的审批；当面板没有 Thread 上下文时为空。 */
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: 'ALLOW' | 'DENY',
  ) => void | Promise<void>
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
  approvalPending?: boolean
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
 */
export interface ThreadPanelComposerInput {
  parts: ComposerPart[]
  pending: boolean
  disabled: boolean
  onPartsChange: (parts: ComposerPart[]) => void
  onHistoryPartsChange?: (parts: ComposerPart[]) => void
  /** 提交载荷：payload（server uploadId）与 localDraft（客户端 localId 快照）分开传递。 */
  onSubmit: (payload: ComposerPart[], localDraft: ComposerPart[]) => void
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
  /** 当前交互作用域是否允许 Escape 把焦点恢复到此 Composer。 */
  focusOnEscape?: boolean
  /** 与 Composer 互斥的轻量选择/操作面板；Composer 保持挂载以保留草稿上传状态。 */
  interactionPanel?: ReactNode
  /** 双层 Composer 底栏的受控 Permission 与 Model/Variant 设置。 */
  settings?: ThreadComposerSettingsInput
}

/**
 * Activity 区域：工作状态、排队中的输入，以及可选的可关闭反馈横幅。
 */
export interface ThreadPanelActivityInput {
  working: boolean
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
  composer: ThreadPanelComposerInput
  activity: ThreadPanelActivityInput
  slots?: ThreadPanelSlots
  /** 主列顶部的内容标题（如绑定 Thread 的名称与重命名入口）；不占滚动区。 */
  heading?: ReactNode
}

/**
 * 全宽 thread 面板：
 * Conversation/Debug 互斥主滚动区 -> 装饰性 widget/队列 -> slash 命令输入 -> footer
 */
export function ThreadPanel({ transcript, mainView, composer, activity, slots, heading }: ThreadPanelProps) {
  const interactionOpen = composer.interactionPanel != null
  const historicalUserMessages = useMemo(
    () => transcript.messages.flatMap((message) =>
      message.role === 'user' && message.text.length > 0 ? [message.text] : [],
    ),
    [transcript.messages],
  )
  const queuedUserMessages = useMemo(
    () => transcript.queuedMessages.flatMap((message) =>
      message.role === 'user' && message.text.length > 0 ? [message.text] : [],
    ),
    [transcript.queuedMessages],
  )
  return (
    <section className="chat-shell thread-panel">
      {slots?.sidebar}
      <main className="chat-main thread-panel-main">
        {heading}
        {mainView?.debug ?? (
          <ThreadConversationView
            messages={transcript.messages}
            loading={transcript.loading}
            error={transcript.error}
            bodyRef={transcript.bodyRef}
            initialScrollTop={transcript.initialScrollTop}
            resetKey={transcript.resetKey}
            eventCount={transcript.eventCount}
            onDecideApproval={transcript.onDecideApproval}
            approvalPending={transcript.approvalPending}
          />
        )}
        <ThreadWidgetStack
          working={activity.working || composer.pending}
          queuedMessages={interactionOpen ? [] : transcript.queuedMessages}
        >
          {interactionOpen ? null : activity.widgets}
        </ThreadWidgetStack>
        {activity.actionError ? (
          <ThreadErrorPanel message={activity.actionError} onDismiss={activity.onDismissActionError} />
        ) : null}
        <ThreadComposer
          parts={composer.parts}
          pending={composer.pending}
          disabled={composer.disabled}
          onPartsChange={composer.onPartsChange}
          onHistoryPartsChange={composer.onHistoryPartsChange}
          onSubmit={composer.onSubmit}
          onCommand={composer.onCommand}
          commands={composer.commands}
          focusOnEscape={composer.focusOnEscape && !interactionOpen}
          active={!interactionOpen}
          historicalUserMessages={historicalUserMessages}
          queuedUserMessages={queuedUserMessages}
          settings={composer.settings}
        />
        {composer.interactionPanel}
        {slots?.footer}
      </main>
    </section>
  )
}