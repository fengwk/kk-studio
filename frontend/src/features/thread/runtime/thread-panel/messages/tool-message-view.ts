import type { DisclosurePhase } from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import {
  previewForToolCall,
  type ToolCallPreview,
} from '@/features/thread/runtime/thread-panel/messages/tool-previews'
import type {
  ToolContent,
  ToolDialogueMessage,
} from '@/features/thread/runtime/thread-timeline-types'

export type ToolVisualState = 'pending' | 'success' | 'error'
export type ToolExecutionState =
  | 'preparing' | 'queued' | 'environment' | 'approval' | 'input'
  | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'unknown'

/** 状态仅取 durable invocation；模型流里的 call.done 只是参数完整。 */
export function toolExecutionState(
  message: Pick<ToolDialogueMessage, 'invocationStatus' | 'waitingForEnvironment' | 'phase' | 'status'>,
): ToolExecutionState {
  switch (message.invocationStatus) {
    case 'READY': return message.waitingForEnvironment ? 'environment' : 'queued'
    case 'WAITING_APPROVAL': return 'approval'
    case 'WAITING_INPUT': return 'input'
    case 'RUNNING': return 'running'
    case 'SUCCEEDED': return 'succeeded'
    case 'FAILED': return 'failed'
    case 'CANCELLED': return 'cancelled'
    case 'UNKNOWN': return 'unknown'
    default:
      return message.phase === 'result' && message.status !== 'streaming'
        ? (message.status === 'error' ? 'failed' : 'succeeded')
        : 'preparing'
  }
}

/**
 * active：生成调用、排队、等待审批/输入/环境、执行中；
 * settled：成功、失败、已停止、结果未知。
 */
export function toolDisclosurePhase(state: ToolExecutionState): DisclosurePhase {
  return ['preparing', 'queued', 'environment', 'approval', 'input', 'running'].includes(state)
    ? 'active'
    : 'settled'
}

/** 调用正文由 diff/正文预览组件承载的工具。 */
const CALL_PREVIEW_TOOLS = new Set(['write', 'edit'])
/** 调用参数下移到正文的工具（task prompt、ask_user 问题）：Header 只保留小型参数。 */
const CALL_RECORD_TOOLS = new Set(['task', 'ask_user'])

export interface ToolMessageView {
  callMessage?: ToolDialogueMessage
  resultMessage?: ToolDialogueMessage
  preview: ToolCallPreview | null
  visualState: ToolVisualState
  executionState: ToolExecutionState
  /** 结果的有序内容：durable result 优先，否则退回瞬态 partial。 */
  contents: ToolContent[]
  /** 必须始终可见的失败摘要（正文已含同一文本时不重复）。 */
  errorText?: string
  /** 结果或调用是否处于失败态（失败摘要不得被折叠隐藏）。 */
  hasError: boolean
  /** 调用正文是否由 write/edit 预览组件渲染。 */
  hasCallPreview: boolean
  /** 调用正文是否由 task renderer / ask_user 记录渲染。 */
  hasCallRecordBody: boolean
  argumentsStreaming: boolean
  arguments: string
  toolName: string
  isMutationSuccess: boolean
}

/**
 * durable result 是唯一展示源；它出现后旧 partial 不得再贡献内容。
 * 结果消息存在但内容为空时不回退 partial（避免旧 partial 盖过终态错误）。
 */
export function toolMessageContents(
  message: ToolDialogueMessage,
  result?: ToolDialogueMessage,
): ToolContent[] {
  const callMessage = message.phase === 'call' ? message : undefined
  const resultMessage = result ?? (message.phase === 'result' ? message : undefined)
  if (resultMessage) {
    return resultMessage.contents
  }
  return callMessage?.partialContents ?? message.partialContents ?? []
}

/**
 * 合并 call/result/partial 事实，并计算宿主 shell 所需的纯展示状态。
 * 展开状态、配额与截取由宿主和 disclosure policy 决定，这里只提供事实。
 */
export function buildToolMessageView({
  message,
  result,
  hasCallRecordRenderer,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
  /** 正文侧是否具备渲染 task/ask_user 调用正文的能力（扩展 renderer 或内置记录）。 */
  hasCallRecordRenderer: boolean
}): ToolMessageView {
  const callMessage = message.phase === 'call' ? message : undefined
  const resultMessage = result ?? (message.phase === 'result' ? message : undefined)
  const toolName = message.toolName || 'Tool'
  const normalizedName = toolName.trim().toLowerCase()
  const argumentsValue = callMessage?.arguments || resultMessage?.arguments || message.arguments
  const argumentsStreaming =
    callMessage != null
    && callMessage.subjectEntryId == null
    && callMessage.status === 'streaming'
  const contents = toolMessageContents(message, result)
  const facts = toolErrorFacts({ message, result })
  const rawExecutionState = toolExecutionState(
    callMessage?.invocationStatus != null ? callMessage : resultMessage ?? message,
  )
  const executionState = facts.hasError && rawExecutionState === 'succeeded' ? 'failed' : rawExecutionState

  const editReview =
    message.approval?.editReview
    ?? callMessage?.approval?.editReview
    ?? message.editReview
    ?? callMessage?.editReview
    ?? result?.editReview
    ?? null

  const isTerminal =
    resultMessage != null
    || ['succeeded', 'failed', 'cancelled', 'unknown'].includes(rawExecutionState)
  const isTerminalWithoutReview = isTerminal && !editReview
  const isWaitingReview = !isTerminal && (
    callMessage?.invocationStatus === 'WAITING_APPROVAL'
    || message.approval?.required === true
    || (callMessage != null && !argumentsStreaming)
  )

  const preview = previewForToolCall(toolName, argumentsValue, {
    editReview,
    streaming: argumentsStreaming,
    isWaitingReview,
    isTerminalWithoutReview,
  })

  const hasCallPreview =
    CALL_PREVIEW_TOOLS.has(normalizedName)
    && preview != null
    && (
      preview.kind === 'write'
        ? (preview.hasContent || preview.content.length > 0 || preview.path.length > 0)
        : (
          preview.mode === 'preparing'
          || (preview.mode === 'input' && preview.entries.some(
            (entry) => entry.oldText.length > 0 || entry.newText.length > 0,
          ))
          || (preview.mode === 'review' && preview.lines.length > 0)
        )
    )
  const hasCallRecordBody =
    CALL_RECORD_TOOLS.has(normalizedName)
    && hasCallRecordRenderer
    && hasArgumentsBody(argumentsValue)

  const isMutationTool = normalizedName === 'write' || normalizedName === 'edit'
  const isMutationSuccess =
    isMutationTool
    && executionState === 'succeeded'
    && !facts.hasError
    && (resultMessage != null || message.phase === 'result' || callMessage?.invocationStatus === 'SUCCEEDED')

  return {
    callMessage,
    resultMessage,
    preview,
    visualState: facts.visualState,
    executionState,
    contents,
    errorText: facts.errorText,
    hasError: facts.hasError,
    hasCallPreview,
    hasCallRecordBody,
    argumentsStreaming,
    arguments: argumentsValue,
    toolName,
    isMutationSuccess,
  }
}

/**
 * 工具卡片的默认展开状态（不含用户选择）。
 *
 * 仅 write/edit 审查期间默认完整显示审查材料；审查完成后与其他工具一样收起，
 * 由各自配额决定收起时可见的行数。用户主动选择由调用方覆盖。
 */
export function toolDefaultExpanded({
  toolName,
  phase,
}: {
  toolName: string
  phase: DisclosurePhase
}): boolean {
  const normalizedName = toolName.trim().toLowerCase()
  return phase === 'active' && (normalizedName === 'write' || normalizedName === 'edit')
}

/** 参数是否承载可展示的调用正文（避免空对象渲染空正文）。 */
function hasArgumentsBody(rawArguments: string): boolean {
  const compact = rawArguments.replaceAll(/\s/g, '')
  return compact !== '' && compact !== '{}' && compact !== '[]' && compact !== 'null'
}

/**
 * 调用/结果的失败事实：失败摘要与配色必须覆盖「调用本身失败但还没有结果」的状态，
 * 否则时间线里的失败调用会被渲染成仍在等待。
 */
export function toolErrorFacts({
  message,
  result,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
}): {
  errorText?: string
  hasError: boolean
  visualState: ToolVisualState
} {
  const callMessage = message.phase === 'call' ? message : undefined
  const resultMessage = result ?? (message.phase === 'result' ? message : undefined)
  const errorText = resolveErrorText(message, callMessage, resultMessage)
  const source = resultMessage ?? callMessage
  const hasError =
    errorText != null || source?.status === 'error' || Boolean(source?.errorMessage)
    || callMessage?.invocationStatus === 'FAILED'
  return {
    errorText,
    hasError,
    visualState: toolVisualState(callMessage, resultMessage, hasError),
  }
}

function resolveErrorText(
  message: ToolDialogueMessage,
  callMessage: ToolDialogueMessage | undefined,
  resultMessage: ToolDialogueMessage | undefined,
): string | undefined {
  const terminalError = resultMessage?.errorMessage ?? message.errorMessage
  if (terminalError?.trim()) {
    return terminalError
  }
  // durable result 出现后旧 partial 的错误不再展示。
  if (resultMessage) {
    return undefined
  }
  const partialError = callMessage?.partialErrorText ?? message.partialErrorText
  return partialError?.trim() ? partialError : undefined
}

function toolVisualState(
  callMessage: ToolDialogueMessage | undefined,
  resultMessage: ToolDialogueMessage | undefined,
  hasError: boolean,
): ToolVisualState {
  if (hasError) {
    return 'error'
  }
  if (callMessage?.invocationStatus != null) {
    return callMessage.invocationStatus === 'SUCCEEDED' ? 'success' : 'pending'
  }
  const source = resultMessage ?? callMessage
  if (source == null || source.status === 'streaming') {
    return 'pending'
  }
  // 只有终态结果能判定成功；durable call 的 done 只代表参数已就绪。
  return resultMessage == null ? 'pending' : 'success'
}
