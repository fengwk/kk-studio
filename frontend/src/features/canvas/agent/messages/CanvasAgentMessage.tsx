import type { AgentRunNode, ThreadMessage } from '@/features/canvas/types'
import { AgentThreadMessage } from '@/features/canvas/agent/messages/AgentThreadMessage'
import { GenerationThreadMessage } from '@/features/canvas/agent/messages/GenerationThreadMessage'
import { RunThreadMessage } from '@/features/canvas/agent/messages/RunThreadMessage'
import { UserThreadMessage } from '@/features/canvas/agent/messages/UserThreadMessage'

/**
 * Message-kind dispatcher, analogous to ChatMessageBubble and pi's per-type components.
 * Keeps thread rendering open for new message kinds without growing the dock shell.
 */
export function CanvasAgentMessage({
  message,
  run,
  onRunAction,
}: {
  message: ThreadMessage
  run: AgentRunNode | undefined
  onRunAction: (action: 'pause' | 'resume' | 'retry') => void
}) {
  if (message.kind === 'user') {
    return <UserThreadMessage text={message.text} />
  }
  if (message.kind === 'agent') {
    return <AgentThreadMessage text={message.text} />
  }
  if (message.kind === 'generation') {
    return (
      <GenerationThreadMessage
        mode={message.mode}
        parameters={message.parameters}
        text={message.text}
      />
    )
  }
  if (!run) {
    return null
  }
  return <RunThreadMessage run={run} onAction={onRunAction} />
}
