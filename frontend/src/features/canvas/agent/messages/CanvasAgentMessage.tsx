import type { AgentRunNode, ThreadMessage } from '@/features/canvas/types'
import { AgentThreadMessage } from '@/features/canvas/agent/messages/AgentThreadMessage'
import { GenerationThreadMessage } from '@/features/canvas/agent/messages/GenerationThreadMessage'
import { RunThreadMessage } from '@/features/canvas/agent/messages/RunThreadMessage'
import { UserThreadMessage } from '@/features/canvas/agent/messages/UserThreadMessage'

/**
 * 按消息 kind 分发的派发器，对应 MessageList 与 pi 的按类型组件。
 * 让 thread 渲染对新消息 kind 保持开放，而不必扩张 dock 外壳。
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
