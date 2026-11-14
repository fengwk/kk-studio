import { asRecord } from '@/features/thread/runtime/payload-json'
import { extractResultEditReview } from '@/features/thread/runtime/mutation-review'
import { parseToolErrorText } from '@/features/thread/runtime/thread-realtime/tool-stream'
import type { RealtimeToolStream } from '@/features/thread/runtime/thread-realtime/tool-stream'
import {
  sameToolCall,
  toolCallIdentity,
  type DialogueMessage,
  type ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline-types'
import { projectToolResultContent } from '@/features/thread/runtime/thread-timeline/tool-result-projection'
import { parseApproval } from '@/features/thread/runtime/thread-timeline/approval-parsing'
import type { ToolInvocationDTO } from '@/shared/api/generated/contracts'

export function projectInvocationOverlays(
  messages: DialogueMessage[],
  toolInvocations: ToolInvocationDTO[],
  toolStreams: ReadonlyMap<string, RealtimeToolStream> | null,
  threadId?: string,
): void {
  if (toolInvocations.length === 0) {
    return
  }
  // 持久身份键（assistantEntryId, callIndex）：一次 invocation 只属于一个 assistant Entry 的
  // 一个调用位置。复用的 toolCallId 出现在不同的 assistant Entry 上时，绝不能接收该
  // invocation 的 overlay，因此不存在「第一个候选」回退。
  const byDurableIdentity = new Map<string, ToolInvocationDTO>()
  for (const invocation of toolInvocations) {
    const key = `${invocation.assistantEntryId}:${invocation.callIndex}`
    if (!byDurableIdentity.has(key)) {
      byDurableIdentity.set(key, invocation)
    }
  }
  for (let index = 0; index < messages.length; index += 1) {
    const message = messages[index]
    if (message == null || message.role !== 'tool' || message.phase !== 'call') {
      continue
    }
    const invocation = byDurableIdentity.get(
      `${message.subjectEntryId ?? ''}:${toolCallOrdinal(message.id)}`,
    )
    if (
      invocation == null
      || message.subjectEntryId !== invocation.assistantEntryId
      || invocation.toolCallId !== message.toolCallId
      || invocation.rendererKey !== message.rendererKey
    ) {
      continue
    }
    const terminalResult = terminalInvocationResult(invocation, message)
    const stream = toolStreams?.get(invocation.id) ?? null
    const overlay =
      stream != null && stream.attempt === invocation.attempt ? stream : null
    const approval = parseApproval(invocation.approval)
    const failed =
      terminalResult != null
      && (
        terminalResult.status === 'error'
        || invocation.error != null
        || isFailedInvocationStatus(invocation.status)
      )
    const failureText = invocation.error != null
      ? parseToolErrorText(invocation.error)
      : null
    const effectiveEditReview =
      approval?.editReview
      ?? terminalResult?.editReview
      ?? extractResultEditReview(invocation.result)
      ?? undefined
    const queuedPredecessor = invocation.queuedBehindToolCallId == null
      ? null
      : toolInvocations.find((candidate) =>
        candidate.assistantEntryId === invocation.assistantEntryId
        && candidate.toolCallId === invocation.queuedBehindToolCallId,
      ) ?? null
    const projected: ToolDialogueMessage = {
      ...message,
      callIdentity: toolCallIdentity(invocation.assistantEntryId, invocation.callIndex),
      // 终态以 invocation 自己的 result/error 为准。call.status=done 只表示
      // arguments 已完成，不能在 durable result 或 invocation 结果到达前当成成功。
      status: terminalResult == null
        ? (invocation.status === 'RUNNING' ? 'streaming' : 'done')
        : (failed ? 'error' : 'done'),
      invocationId: invocation.id,
      invocationStatus: invocation.status,
      queuedBehindToolCallId: invocation.queuedBehindToolCallId,
      queuedBehindToolCallUnfinished:
        queuedPredecessor != null && !isTerminalInvocationStatus(queuedPredecessor.status),
      requiredEnvironmentId: invocation.requiredEnvironmentId,
      waitingForEnvironment: invocation.waitingForEnvironment,
      requiredEnvironmentName: invocation.requiredEnvironmentName,
      environmentWaitFreshnessAt: invocation.environmentWaitFreshnessAt,
      // environmentId 是环境路由身份，绝不能当作审批目标 Thread。
      threadId: threadId || undefined,
      // 终态结果已经可见时，partial 不能盖过正文和错误。
      partialContents:
        terminalResult == null && overlay?.text
          ? [{ type: 'text', text: overlay.text }]
          : undefined,
      partialErrorText: terminalResult == null ? overlay?.errorText : undefined,
      approval: approval?.required ? approval : undefined,
      editReview: effectiveEditReview,
      errorMessage: failed ? failureText ?? undefined : message.errorMessage,
    }
    messages[index] = projected
    if (terminalResult != null && !hasDurableToolResult(messages, projected)) {
      messages.splice(index + 1, 0, {
        ...terminalResult,
        id: `transient:tool-result:${invocation.id}:${invocation.attempt}`,
        role: 'tool',
        phase: 'result',
        subjectEntryId: projected.subjectEntryId,
        callIdentity: projected.callIdentity,
        toolCallId: projected.toolCallId,
        toolName: projected.toolName,
        rendererKey: projected.rendererKey,
        createdAt: invocation.updatedAt,
        invocationId: invocation.id,
        threadId: projected.threadId,
        status: failed ? 'error' : terminalResult.status,
        editReview: terminalResult.editReview,
        errorMessage: failed
          ? terminalResult.errorMessage ?? failureText ?? undefined
          : undefined,
      })
      index += 1
    }
  }
}

/**
 * invocation 自身的终态结果。result 里 error=true 优先于 SUCCEEDED；
 * FAILED / UNKNOWN / CANCELLED 即使没有结果正文也是错误终态。
 * 没有 result/error 且状态仍活动时返回 null，调用保持 pending。
 */
function terminalInvocationResult(
  invocation: ToolInvocationDTO,
  call: ToolDialogueMessage,
): Pick<ToolDialogueMessage, 'arguments' | 'contents' | 'details' | 'errorMessage' | 'status' | 'editReview'> | null {
  if (invocation.result != null) {
    const projected = projectToolResultContent(asRecord(invocation.result), call.arguments)
    return {
      ...projected,
      editReview: extractResultEditReview(invocation.result) ?? projected.editReview,
    }
  }
  if (invocation.error != null || isFailedInvocationStatus(invocation.status)) {
    return {
      arguments: call.arguments,
      contents: [],
      // 错误原文来自 error；没有可解析原文时只有错误态，不补通用失败文案。
      errorMessage: parseToolErrorText(invocation.error) ?? undefined,
      status: 'error',
    }
  }
  return null
}

function isFailedInvocationStatus(status: string): boolean {
  return status === 'FAILED' || status === 'UNKNOWN' || status === 'CANCELLED'
}

function isTerminalInvocationStatus(status: string): boolean {
  return status === 'SUCCEEDED' || isFailedInvocationStatus(status)
}

/** durable result 到达后是唯一展示源；transient overlay 不得再重复输出。 */
function hasDurableToolResult(
  messages: readonly DialogueMessage[],
  call: ToolDialogueMessage,
): boolean {
  return messages.some((candidate) =>
    candidate.role === 'tool'
    && candidate.phase === 'result'
    && !candidate.id.startsWith('transient:tool-result:')
    && sameToolCall(call, candidate),
  )
}

function toolCallOrdinal(messageId: string): number {
  const last = messageId.split(':').pop() ?? ''
  const parsed = Number(last)
  return Number.isSafeInteger(parsed) ? parsed : -1
}
