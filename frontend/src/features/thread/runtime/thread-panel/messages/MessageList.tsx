import { AssistantMessageBlock } from '@/features/thread/runtime/thread-panel/messages/AssistantMessageBlock'
import { EntryMessageBlock } from '@/features/thread/runtime/thread-panel/messages/EntryMessageBlock'
import { MetaMessageBlock } from '@/features/thread/runtime/thread-panel/messages/MetaMessageBlock'
import { ModelAttemptFailureMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ModelAttemptFailureMessageBlock'
import { ToolMessageBlock } from '@/features/thread/runtime/thread-panel/messages/ToolMessageBlock'
import { UserMessageBlock } from '@/features/thread/runtime/thread-panel/messages/UserMessageBlock'
import {
  sameToolCall,
  type DialogueMessage,
  type ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { useOptionalApplicationContributions } from '@/platform/extensions/ApplicationContributionsContext'
import type { ApplicationContributions } from '@/platform/extensions/types'
import type { ReactNode } from 'react'

/** 将每条对话消息分派给对应的块组件。 */
export function MessageList({ messages }: { messages: DialogueMessage[] }) {
  const contributions = useOptionalApplicationContributions()
  const rendered: ReactNode[] = []
  for (const item of groupDialogueMessages(messages)) {
    if (item.kind === 'single') {
      rendered.push(renderSingleMessage(item.message, contributions))
      continue
    }
    const renderer = contributions?.toolRenderers.get(item.call.rendererKey)
    rendered.push(
      <ToolMessageBlock
        key={item.call.cardKey ?? item.call.id}
        message={item.call}
        result={item.result}
        renderer={renderer?.component}
      />,
    )
  }
  return <>{rendered}</>
}

function renderSingleMessage(
  message: DialogueMessage,
  contributions: ApplicationContributions | null,
): ReactNode {
  switch (message.role) {
    case 'user':
      return <UserMessageBlock key={message.id} message={message} />
    case 'assistant':
      return <AssistantMessageBlock key={message.id} message={message} />
    case 'model_attempt_failure':
      return <ModelAttemptFailureMessageBlock key={message.id} message={message} />
    case 'tool': {
      const renderer = contributions?.toolRenderers.get(message.rendererKey)
      return (
        <ToolMessageBlock
          key={message.id}
          message={message}
          renderer={renderer?.component}
        />
      )
    }
    case 'meta':
      return <MetaMessageBlock key={message.id} message={message} />
    case 'entry':
      return <EntryMessageBlock key={message.id} message={message} />
    default:
      return null
  }
}

function groupDialogueMessages(messages: DialogueMessage[]): Array<
  | { kind: 'single'; message: DialogueMessage }
  | { kind: 'tool'; call: ToolDialogueMessage; result?: ToolDialogueMessage }
> {
  const grouped: Array<
    | { kind: 'single'; message: DialogueMessage }
    | { kind: 'tool'; call: ToolDialogueMessage; result?: ToolDialogueMessage }
  > = []
  const consumed = new Set<string>()
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]
    if (message == null || consumed.has(message.id)) {
      continue
    }
    if (message.role !== 'tool') {
      grouped.push({ kind: 'single', message })
      continue
    }
    if (message.phase === 'call') {
      const result = findPairedResult(messages, index + 1, message)
      if (result != null) {
        consumed.add(result.id)
      }
      grouped.push({ kind: 'tool', call: message, result: result ?? undefined })
      continue
    }
    grouped.push({ kind: 'single', message })
  }
  return grouped
}

function findPairedResult(
  messages: DialogueMessage[],
  start: number,
  call: ToolDialogueMessage,
): ToolDialogueMessage | null {
  for (let index = start; index < messages.length; index += 1) {
    const candidate = messages[index]
    if (candidate == null || candidate.role !== 'tool') {
      continue
    }
    if (candidate.phase !== 'result') {
      continue
    }
    if (sameToolCall(call, candidate)) {
      return candidate
    }
  }
  return null
}
