import {
  formatToolCallSummary,
  type ToolCallSummary,
} from '@/features/ai/runtime/thread-panel/messages/tool-display'
import {
  previewForToolCall,
  type ToolCallPreview,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'
import type {
  ToolContent,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

export type ToolVisualState = 'pending' | 'success' | 'error'
export type ToolExecutionState =
  | 'preparing' | 'queued' | 'environment' | 'approval' | 'input' | 'dispatching'
  | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'unknown'

/** 状态仅取 durable invocation；模型流里的 call.done 只是参数完整。 */
export function toolExecutionState(
  message: Pick<ToolDialogueMessage, 'invocationStatus' | 'waitingForEnvironment' | 'phase' | 'status'>,
): ToolExecutionState {
  switch (message.invocationStatus) {
    case 'READY': return message.waitingForEnvironment ? 'environment' : 'queued'
    case 'WAITING_APPROVAL': return 'approval'
    case 'WAITING_INPUT': return 'input'
    case 'DISPATCHING': return 'dispatching'
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

/** 正文默认可见的工具：写入正文、执行前 diff、持续输出、任务 prompt 与问答记录。 */
const BODY_VISIBLE_BY_DEFAULT_TOOLS = new Set(['write', 'edit', 'bash', 'task', 'ask_user'])
/** 调用正文由 diff/正文预览组件承载的工具。 */
const CALL_PREVIEW_TOOLS = new Set(['write', 'edit'])
/** 调用参数下移到正文的工具（task prompt、ask_user 问题）：Header 只保留小型参数。 */
const CALL_RECORD_TOOLS = new Set(['task', 'ask_user'])

export interface ToolMessageView {
  callMessage?: ToolDialogueMessage
  resultMessage?: ToolDialogueMessage
  preview: ToolCallPreview | null
  summary: ToolCallSummary
  visualState: ToolVisualState
  executionState: ToolExecutionState
  /** 结果的有序内容：durable result 优先，否则退回瞬态 partial。 */
  contents: ToolContent[]
  /** 必须始终可见的失败摘要（正文已含同一文本时不重复）。 */
  errorText?: string
  /** 结果或调用是否处于失败态（失败摘要不得被折叠隐藏）。 */
  hasError: boolean
  /** 存在可展开/收起的正文（调用正文或结果内容）。 */
  hasBody: boolean
  /** 调用正文是否由 write/edit 预览组件渲染。 */
  hasCallPreview: boolean
  /** 调用正文是否由 task renderer / ask_user 记录渲染。 */
  hasCallRecordBody: boolean
  /** 正文当前是否可见。 */
  showBody: boolean
  argumentsStreaming: boolean
  /** 正文内容是否仍来自瞬态流式消息（bash 持续输出）：唯一允许跟随尾部的信号。 */
  contentsStreaming: boolean
  arguments: string
  toolName: string
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
 *
 * `expanded` 是用户选择与默认值合成后的最终展开状态，同时覆盖调用正文与结果内容：
 * 所有存在有意义正文的卡片都可折叠，prompt/diff 默认可见不等于不可收起。
 */
export function buildToolMessageView({
  message,
  result,
  expanded,
  hasCallRecordRenderer,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
  expanded: boolean
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
  // partial 属于执行中的调用，即使调用 Entry 已持久化，输出仍是瞬态流。
  const contentsSource = resultMessage ?? callMessage
  const contentsStreaming =
    contentsSource != null
    && contentsSource.status === 'streaming'
  const contents = toolMessageContents(message, result)
  const preview = previewForToolCall(toolName, argumentsValue)
  const facts = toolErrorFacts({ message, result })
  const executionState = toolExecutionState(
    callMessage?.invocationStatus != null ? callMessage : resultMessage ?? message,
  )
  const hasCallPreview =
    CALL_PREVIEW_TOOLS.has(normalizedName) && preview != null && preview.lines.length > 0
  const hasCallRecordBody =
    CALL_RECORD_TOOLS.has(normalizedName)
    && hasCallRecordRenderer
    && hasArgumentsBody(argumentsValue)
  return {
    callMessage,
    resultMessage,
    preview,
    summary: formatToolCallSummary(toolName, argumentsValue),
    visualState: facts.visualState,
    executionState: facts.hasError && executionState === 'succeeded' ? 'failed' : executionState,
    contents,
    errorText: facts.errorText,
    hasError: facts.hasError,
    hasBody: contents.length > 0 || hasCallPreview || hasCallRecordBody,
    hasCallPreview,
    hasCallRecordBody,
    showBody: expanded,
    argumentsStreaming,
    contentsStreaming,
    arguments: argumentsValue,
    toolName,
  }
}

/**
 * 工具卡片的默认展开状态。
 *
 * 默认值只由工具与首次结果事实决定（不含用户选择），因此流式转终态、参数增长和
 * 重新渲染都不会改变它；用户主动选择由调用方覆盖。
 */
export function toolDefaultExpanded({
  toolName,
  contents,
  hasError,
  mediaDefault = false,
}: {
  toolName: string
  contents: ToolContent[]
  hasError: boolean
  mediaDefault?: boolean
}): boolean {
  const normalizedName = toolName.trim().toLowerCase()
  // 失败事实优先于 read 的媒体默认值：错误结果默认展开，不让 read 分支把错误藏起来；
  // 但空白 Text 不构成可展开正文（避免只展开一个空框）。
  if (hasError && hasMeaningfulContents(contents)) {
    return true
  }
  if (BODY_VISIBLE_BY_DEFAULT_TOOLS.has(normalizedName)) {
    return true
  }
  if (normalizedName === 'read') {
    return mediaDefault
  }
  // 未知/MCP 的成功结果默认折叠。
  return false
}

/** 结果内容是否包含可展示的非空事实：空/空白 Text 不算有意义正文。 */
function hasMeaningfulContents(contents: ToolContent[]): boolean {
  return contents.some((content) => content.type !== 'text' || content.text.trim() !== '')
}

/**
 * 正文已完整呈现同一失败文本时不再重复渲染摘要；正文被收起时摘要必须可见。
 */
export function shouldShowErrorNotice({
  showBody,
  bodyTexts,
  errorNotice,
}: {
  showBody: boolean
  bodyTexts: string[]
  errorNotice: string
}): boolean {
  const notice = errorNotice.trim()
  if (!notice) {
    return false
  }
  if (!showBody) {
    return true
  }
  return !bodyTexts.some((text) => {
    const bodyText = text.trim()
    return bodyText !== '' && (bodyText === notice || bodyText.includes(notice))
  })
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
