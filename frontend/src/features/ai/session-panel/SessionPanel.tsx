import type { ReactNode, RefObject } from 'react'
import { SessionComposer } from '@/features/ai/session-panel/SessionComposer'
import { SessionErrorPanel } from '@/features/ai/session-panel/SessionErrorPanel'
import { SessionFooter } from '@/features/ai/session-panel/SessionFooter'
import { SessionTranscript } from '@/features/ai/session-panel/SessionTranscript'
import { SessionWidgetStack } from '@/features/ai/session-panel/SessionWidgetStack'
import type { DialogueMessage } from '@/features/ai/session-events'

/**
 * Full-bleed session panel aligned with pi layout zones:
 * dialogue (blocks) -> widgets (working / subagents) -> input dock -> footer.
 */
export function SessionPanel({
  sidebar,
  messages,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  composerDisabled,
  composerPending,
  activeRun,
  controlsPending,
  actionError,
  onDismissActionError,
  onDraftChange,
  onSubmit,
  onSteer,
  onFollowUp,
  onAbort,
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
  activeRun: boolean
  controlsPending: boolean
  actionError?: string | null
  onDismissActionError?: () => void
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onSteer: () => void
  onFollowUp: () => void
  onAbort: () => void
  widgets?: ReactNode
  footer?: ReactNode
}) {
  return (
    <section className="chat-shell session-panel">
      {sidebar}
      <main className="chat-main session-panel-main">
        <SessionTranscript
          messages={messages}
          loading={messagesLoading}
          error={messagesError}
          bodyRef={bodyRef}
          pending={composerPending}
        />
        <SessionWidgetStack activeRun={activeRun || composerPending}>{widgets}</SessionWidgetStack>
        {actionError ? (
          <SessionErrorPanel message={actionError} onDismiss={onDismissActionError} />
        ) : null}
        <SessionComposer
          draft={draft}
          activeRun={activeRun}
          pending={composerPending}
          disabled={composerDisabled}
          controlsPending={controlsPending}
          onDraftChange={onDraftChange}
          onSubmit={onSubmit}
          onSteer={onSteer}
          onFollowUp={onFollowUp}
          onAbort={onAbort}
        />
        <SessionFooter>{footer}</SessionFooter>
      </main>
    </section>
  )
}
