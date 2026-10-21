import { AssistantMessageBlock } from '@/features/chat/runtime/thread-panel/messages/AssistantMessageBlock'
import { EntryMessageBlock } from '@/features/chat/runtime/thread-panel/messages/EntryMessageBlock'
import { MetaMessageBlock } from '@/features/chat/runtime/thread-panel/messages/MetaMessageBlock'
import { ModelAttemptFailureMessageBlock } from '@/features/chat/runtime/thread-panel/messages/ModelAttemptFailureMessageBlock'
import { ToolMessageBlock } from '@/features/chat/runtime/thread-panel/messages/ToolMessageBlock'
import { UserMessageBlock } from '@/features/chat/runtime/thread-panel/messages/UserMessageBlock'
import {
  sameToolCall,
  type DialogueMessage,
  type ToolDialogueMessage,
} from '@/features/chat/runtime/thread-timeline-types'
import { useOptionalApplicationContributions } from '@/platform/extensions/ApplicationContributionsContext'
import type { ApplicationContributions } from '@/platform/extensions/types'
import type { ReactNode } from 'react'

/** 将每条对话消息分派给对应的块组件（pi 风格按消息类型分发）。 */
export function MessageList({ messages }: { messages: DialogueMessage[] }) {
  const contributions = useOptionalApplicationContributions()
  const usedKeys = new Set<string>()
  const rendered: ReactNode[] = []
  for (const item of groupDialogueMessages(messages)) {
    if (item.kind === 'single') {
      rendered.push(renderSingleMessage(item.message, contributions))
      continue
    }
    const renderer = contributions?.toolRenderers.get(item.call.rendererKey)
    rendered.push(
      <ToolMessageBlock
        key={toolCardKey(item.call, usedKeys)}
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
          key={message.toolCallId || message.id}
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

/**
 * 工具卡片 key：优先使用 toolCallId，使实时草稿与持久 call 在流式转终态时保持同一
 * React 身份（展开、滚动与选择不重置）。真正复用同一 toolCallId 的调用才追加
 * callIdentity 区分，避免 key 冲突。
 */
function toolCardKey(call: ToolDialogueMessage, usedKeys: Set<string>): string {
  const base = call.toolCallId || call.id
  const key = usedKeys.has(base)
    ? `${base}:${call.callIdentity ?? usedKeys.size}`
    : base
  usedKeys.add(key)
  return key
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
