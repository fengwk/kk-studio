import { AssistantMessageBlock } from '@/features/ai/runtime/thread-panel/messages/AssistantMessageBlock'
import { EntryMessageBlock } from '@/features/ai/runtime/thread-panel/messages/EntryMessageBlock'
import { MetaMessageBlock } from '@/features/ai/runtime/thread-panel/messages/MetaMessageBlock'
import { ModelAttemptFailureMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ModelAttemptFailureMessageBlock'
import { ToolMessageBlock } from '@/features/ai/runtime/thread-panel/messages/ToolMessageBlock'
import { UserMessageBlock } from '@/features/ai/runtime/thread-panel/messages/UserMessageBlock'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'
import { useOptionalExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import type { ReactNode } from 'react'

/** 将每条对话消息分派给对应的块组件（pi 风格按消息类型分发）。 */
export function MessageList({
  messages,
  onDecideApproval,
  approvalPending = false,
}: {
  messages: DialogueMessage[]
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: 'ALLOW' | 'DENY',
  ) => void | Promise<void>
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
  approvalPending?: boolean
}) {
  const extensionHost = useOptionalExtensionHostSnapshot()
  return (
    <>
      {groupDialogueMessages(messages).map((item) => {
        if (item.kind === 'single') {
          return renderSingleMessage(
            item.message,
            extensionHost,
            onDecideApproval,
            approvalPending,
          )
        }
        const renderer = extensionHost?.toolRenderers.get(item.call.rendererKey)
        return (
          <ToolMessageBlock
            key={item.call.id}
            message={item.call}
            result={item.result}
            renderer={renderer?.component}
            isRendererExpandable={renderer?.isExpandable}
            onDecideApproval={onDecideApproval}
            approvalPending={approvalPending}
          />
        )
      })}
    </>
  )
}

function renderSingleMessage(
  message: DialogueMessage,
  extensionHost: ReturnType<typeof useOptionalExtensionHostSnapshot>,
  onDecideApproval:
    | ((message: ToolDialogueMessage, decision: 'ALLOW' | 'DENY') => void | Promise<void>)
    | undefined,
  approvalPending: boolean,
): ReactNode {
  switch (message.role) {
    case 'user':
      return <UserMessageBlock key={message.id} message={message} />
    case 'assistant':
      return <AssistantMessageBlock key={message.id} message={message} />
    case 'model_attempt_failure':
      return <ModelAttemptFailureMessageBlock key={message.id} message={message} />
    case 'tool': {
      const renderer = extensionHost?.toolRenderers.get(message.rendererKey)
      return (
        <ToolMessageBlock
          key={message.id}
          message={message}
          renderer={renderer?.component}
          isRendererExpandable={renderer?.isExpandable}
          onDecideApproval={onDecideApproval}
          approvalPending={approvalPending}
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
    if (sameToolIdentity(call, candidate)) {
      return candidate
    }
  }
  return null
}

function sameToolIdentity(left: ToolDialogueMessage, right: ToolDialogueMessage): boolean {
  if (left.toolCallId && right.toolCallId) {
    return left.toolCallId === right.toolCallId
  }
  return left.rendererKey === right.rendererKey && left.toolName === right.toolName
}
