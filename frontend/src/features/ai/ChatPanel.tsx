import type { RefObject } from 'react'
import { ChatComposer, ChatRuntimeBar, ChatSidebar } from '@/features/ai/ChatPanelParts'
import { ChatObservabilityPanel } from '@/features/ai/ChatObservabilityPanel'
import { ChatTranscript } from '@/features/ai/ChatTranscript'
import type { SessionTimeline } from '@/features/ai/session-events'
import type {
  AgentDefinitionDTO,
  HarnessRunDTO,
  HarnessSessionDTO,
  ModelUsageSummaryDTO,
  SessionYoloDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts'

export function ChatPanel({
  sessions,
  agentsById,
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
  observability,
  onDraftChange,
  onSubmit,
}: {
  sessions: HarnessSessionDTO[]
  agentsById: Map<string, AgentDefinitionDTO>
  activeSessionId: string
  title: string
  onBack: () => void
  session?: HarnessSessionDTO
  agent?: AgentDefinitionDTO
  timeline: SessionTimeline
  runs: HarnessRunDTO[]
  activeRun: boolean
  messagesLoading: boolean
  messagesError: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  draft: string
  pending: boolean
  disabled: boolean
  observability: {
    yolo?: SessionYoloDTO
    usage?: ModelUsageSummaryDTO
    toolInvocations: ToolInvocationDTO[]
    observabilityError: unknown
    yoloPending: boolean
    decisionPending: boolean
    setYolo: (enabled: boolean) => void
    decideTool: (invocationId: string, decision: 'allow' | 'deny') => void
  }
  onDraftChange: (draft: string) => void
  onSubmit: () => void
}) {
  return (
    <section className="chat-shell">
      <ChatSidebar sessions={sessions} agentsById={agentsById} activeSessionId={activeSessionId} title={title} onBack={onBack} />

      <main className="chat-main">
        <ChatRuntimeBar session={session} agent={agent} timeline={timeline} runs={runs} activeRun={activeRun} />
        <ChatObservabilityPanel
          yolo={observability.yolo}
          usage={observability.usage}
          toolInvocations={observability.toolInvocations}
          error={observability.observabilityError}
          yoloPending={observability.yoloPending}
          decisionPending={observability.decisionPending}
          onYoloChange={observability.setYolo}
          onDecision={observability.decideTool}
        />
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
