import type { ReactNode, RefObject } from 'react'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { ThreadErrorPanel } from '@/features/ai/runtime/thread-panel/ThreadErrorPanel'
import { ThreadTranscript } from '@/features/ai/runtime/thread-panel/ThreadTranscript'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import type {
  DialogueMessage,
  QueuedThreadMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/**
 * Transcript zone: durable timeline + live mailbox messages.
 * Consumers provide already-loaded data; this surface never reaches into a controller.
 */
export interface ThreadPanelTranscriptInput {
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
  loading: boolean
  error: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  /** Decides a pending ToolInvocation approval; absent when the surface has no Thread context. */
  onDecideApproval?: (message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void
  /** Global approval request in flight: every undecided approval bar disables its buttons. */
  approvalPending?: boolean
}

/**
 * Composer zone: slash command input + send button. All callbacks are required because
 * the composer is a pure controlled component and owns no state of its own.
 */
export interface ThreadPanelComposerInput {
  draft: string
  pending: boolean
  disabled: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  commands?: ThreadCommand[]
}

/**
 * Activity zone: working status, queued inputs, and an optional dismissible feedback banner.
 */
export interface ThreadPanelActivityInput {
  working: boolean
  widgets?: ReactNode
  actionError?: string | null
  onDismissActionError?: () => void
}

/**
 * Optional layout slots: permanent sidebar rendered before the main column, and footer rendered
 * after the composer. They are intentionally raw ReactNodes so callers can compose any
 * presentation contract (status, history shortcut, etc.) without altering this surface.
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
 * Full-bleed thread panel:
 * durable/live dialogue -> decoration widgets/queue -> slash-command input -> footer
 */
export function ThreadPanel({ transcript, composer, activity, slots }: ThreadPanelProps) {
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
          queuedMessages={transcript.queuedMessages}
        >
          {activity.widgets}
        </ThreadWidgetStack>
        {activity.actionError ? (
          <ThreadErrorPanel message={activity.actionError} onDismiss={activity.onDismissActionError} />
        ) : null}
        <ThreadComposer
          draft={composer.draft}
          pending={composer.pending}
          disabled={composer.disabled}
          onDraftChange={composer.onDraftChange}
          onSubmit={composer.onSubmit}
          onCommand={composer.onCommand}
          commands={composer.commands}
        />
        {slots?.footer}
      </main>
    </section>
  )
}