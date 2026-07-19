import type { ReactNode, RefObject } from 'react'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadErrorPanel } from '@/features/ai/thread-panel/ThreadErrorPanel'
import { ThreadTranscript } from '@/features/ai/thread-panel/ThreadTranscript'
import { ThreadWidgetStack } from '@/features/ai/thread-panel/ThreadWidgetStack'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import type { DialogueMessage, QueuedThreadMessage } from '@/features/ai/thread-events'

/**
 * Full-bleed thread panel:
 * durable/live dialogue -> decoration widgets/queue -> slash-command input -> footer
 */
export function ThreadPanel({
  sidebar,
  messages,
  queuedMessages,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  composerDisabled,
  composerPending,
  working,
  actionError,
  onDismissActionError,
  onDraftChange,
  onSubmit,
  onCommand,
  widgets,
  footer,
}: {
  sidebar?: ReactNode
  messages: DialogueMessage[]
  queuedMessages: QueuedThreadMessage[]
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  composerDisabled: boolean
  composerPending: boolean
  working: boolean
  actionError?: string | null
  onDismissActionError?: () => void
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  widgets?: ReactNode
  footer?: ReactNode
}) {
  return (
    <section className="chat-shell thread-panel">
      {sidebar}
      <main className="chat-main thread-panel-main">
        <ThreadTranscript
          messages={messages}
          loading={messagesLoading}
          error={messagesError}
          bodyRef={bodyRef}
        />
        <ThreadWidgetStack
          working={working || composerPending}
          queuedMessages={queuedMessages}
        >
          {widgets}
        </ThreadWidgetStack>
        {actionError ? (
          <ThreadErrorPanel message={actionError} onDismiss={onDismissActionError} />
        ) : null}
        <ThreadComposer
          draft={draft}
          pending={composerPending}
          disabled={composerDisabled}
          onDraftChange={onDraftChange}
          onSubmit={onSubmit}
          onCommand={onCommand}
        />
        {footer}
      </main>
    </section>
  )
}
