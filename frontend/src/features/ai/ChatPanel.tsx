import type { RefObject } from 'react'
import { ChatComposer, ChatRuntimeBar, ChatSidebar } from '@/features/ai/ChatPanelParts'
import { ChatTranscript } from '@/features/ai/ChatTranscript'
import type { SessionTimeline } from '@/features/ai/session-events'
import type { AgentDefinitionDTO, AgentRunDTO, AgentSessionDTO } from '@/shared/api/contracts'

export function ChatPanel({
  sessions,
  agentsByName,
  activeSessionId,
  title,
  onBack,
  session,
  agent,
  timeline,
  runs,
  activeRun,
  messagesLoading,
  messagesError,
  bodyRef,
  draft,
  pending,
  disabled,
  onDraftChange,
  onSubmit,
}: {
  sessions: AgentSessionDTO[]
  agentsByName: Map<string, AgentDefinitionDTO>
  activeSessionId: string
  title: string
  onBack: () => void
  session?: AgentSessionDTO
  agent?: AgentDefinitionDTO
  timeline: SessionTimeline
  runs: AgentRunDTO[]
  activeRun: boolean
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  pending: boolean
  disabled: boolean
  onDraftChange: (draft: string) => void
  onSubmit: () => void
}) {
  return (
    <section className="chat-shell">
      <ChatSidebar sessions={sessions} agentsByName={agentsByName} activeSessionId={activeSessionId} title={title} onBack={onBack} />

      <main className="chat-main">
        <ChatRuntimeBar session={session} agent={agent} timeline={timeline} runs={runs} activeRun={activeRun} />
        <ChatTranscript messages={timeline.messages} loading={messagesLoading} error={messagesError} bodyRef={bodyRef} />
        <ChatComposer
          draft={draft}
          activeRun={activeRun}
          pending={pending}
          disabled={disabled}
          onDraftChange={onDraftChange}
          onSubmit={onSubmit}
        />
      </main>
    </section>
  )
}
