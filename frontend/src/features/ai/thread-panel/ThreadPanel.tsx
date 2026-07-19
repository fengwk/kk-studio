import type { ReactNode, RefObject } from 'react'
import { ThreadComposer } from '@/features/ai/thread-panel/ThreadComposer'
import { ThreadErrorPanel } from '@/features/ai/thread-panel/ThreadErrorPanel'
import { ThreadTranscript } from '@/features/ai/thread-panel/ThreadTranscript'
import { ThreadWidgetStack } from '@/features/ai/thread-panel/ThreadWidgetStack'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import type { DialogueMessage } from '@/features/ai/thread-events'
import type { ThreadStatus } from '@/shared/api/contracts'

/**
 * Full-bleed thread panel:
 * dialogue -> widgets -> input(+// commands) -> footer
 */
export function ThreadPanel({
  sidebar,
  messages,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  composerDisabled,
  composerPending,
  working,
  controlsPending,
  actionError,
  onDismissActionError,
  onDraftChange,
  onSubmit,
  onCommand,
  threadStatus,
  onStop,
  onRetry,
  stopPending,
  retryPending,
  widgets,
  footer,
}: {
  sidebar?: ReactNode
  messages: DialogueMessage[]
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  composerDisabled: boolean
  composerPending: boolean
  working: boolean
  controlsPending: boolean
  actionError?: string | null
  onDismissActionError?: () => void
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  threadStatus?: ThreadStatus
  onStop: () => void
  onRetry: () => void
  stopPending: boolean
  retryPending: boolean
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
          pending={composerPending}
        />
        <ThreadWidgetStack working={working || composerPending}>{widgets}</ThreadWidgetStack>
        {actionError ? (
          <ThreadErrorPanel message={actionError} onDismiss={onDismissActionError} />
        ) : null}
        <ThreadComposer
          draft={draft}
          pending={composerPending}
          disabled={composerDisabled}
          controlsPending={controlsPending}
          onDraftChange={onDraftChange}
          onSubmit={onSubmit}
          onCommand={onCommand}
          threadStatus={threadStatus}
          onStop={onStop}
          onRetry={onRetry}
          stopPending={stopPending}
          retryPending={retryPending}
        />
        {footer}
      </main>
    </section>
  )
}
