import type { ReactNode, RefObject } from 'react'
import { SessionComposer } from '@/features/ai/session-panel/SessionComposer'
import { SessionFooter } from '@/features/ai/session-panel/SessionFooter'
import { SessionHeader } from '@/features/ai/session-panel/SessionHeader'
import { SessionTranscript } from '@/features/ai/session-panel/SessionTranscript'
import type { DialogueMessage } from '@/features/ai/session-events'

/**
 * Reusable session panel shell aligned with pi interactive layout:
 * header -> scroll transcript (per-message components) -> composer -> footer.
 */
export function SessionPanel({
  sidebar,
  title,
  subtitle,
  status,
  messages,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  composerDisabled,
  composerPending,
  activeRun,
  controlsPending,
  onDraftChange,
  onSubmit,
  onSteer,
  onFollowUp,
  onAbort,
  banner,
  footer,
}: {
  sidebar?: ReactNode
  title: string
  subtitle?: string
  status?: ReactNode
  messages: DialogueMessage[]
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  composerDisabled: boolean
  composerPending: boolean
  activeRun: boolean
  controlsPending: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
  onSteer: () => void
  onFollowUp: () => void
  onAbort: () => void
  banner?: ReactNode
  footer?: ReactNode
}) {
  return (
    <section className="chat-shell session-panel">
      {sidebar}
      <main className="chat-main session-panel-main">
        <SessionHeader title={title} subtitle={subtitle} status={status} />
        <SessionTranscript
          messages={messages}
          loading={messagesLoading}
          error={messagesError}
          bodyRef={bodyRef}
        />
        {banner}
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
