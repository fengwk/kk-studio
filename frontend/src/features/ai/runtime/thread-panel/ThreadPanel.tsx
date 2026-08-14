import { useMemo, type ReactNode, type RefObject } from 'react'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { ThreadErrorPanel } from '@/features/ai/runtime/thread-panel/ThreadErrorPanel'
import { ThreadTranscript } from '@/features/ai/runtime/thread-panel/ThreadTranscript'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
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
  /** 处理待决 ToolInvocation 的审批；当面板没有 Thread 上下文时为空。 */
  onDecideApproval?: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
  approvalPending?: boolean
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
  composer: ThreadPanelComposerInput
  activity: ThreadPanelActivityInput
  slots?: ThreadPanelSlots
}

/**
 * 全宽 thread 面板：
 * 持久/实时对话 -> 装饰性 widget/队列 -> slash 命令输入 -> footer
 */
export function ThreadPanel({ transcript, composer, activity, slots }: ThreadPanelProps) {
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
        <ThreadTranscript
          messages={transcript.messages}
          loading={transcript.loading}
          error={transcript.error}
          bodyRef={transcript.bodyRef}
          onDecideApproval={transcript.onDecideApproval}
          approvalPending={transcript.approvalPending}
        />
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
        />
        {composer.interactionPanel}
        {slots?.footer}
      </main>
    </section>
  )
}