import {
  formatToolCallLinePreview,
  formatToolCallSummary,
  formatToolResultPreview,
  shouldSuppressTransientToolResult,
  type ToolCallSummary,
} from '@/features/ai/runtime/thread-panel/messages/tool-display'
import {
  previewForToolCall,
  type ToolCallPreview,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'
import type { ToolAttachment, ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type { ToolRendererExpandabilityResolver } from '@/platform/extensions/types'

export type ToolApprovalDecision = 'ALLOW' | 'DENY'
export type ToolVisualState = 'pending' | 'success' | 'error'

export interface ToolRenderContext {
  toolName: string
  arguments: string
  text: string
  attachments: ToolAttachment[]
  status?: ToolDialogueMessage['status']
  errorMessage?: string
  partial?: string
  partialErrorText?: string
  partialAttachments?: ToolAttachment[]
  approval?: ToolDialogueMessage['approval']
  approvalPending?: boolean
  argumentsStreaming: boolean
}

export interface ToolMessageView {
  callMessage?: ToolDialogueMessage
  resultMessage?: ToolDialogueMessage
  context: ToolRenderContext
  preview: ToolCallPreview | null
  summary: ToolCallSummary
  visualState: ToolVisualState
  expandable: boolean
  expanded: boolean
  showResult: boolean
}

/** 合并 call/result/partial 事实，并计算宿主 shell 所需的纯展示状态。 */
export function buildToolMessageView({
  message,
  result,
  approvalPending,
  requestedExpanded,
  hasCustomRenderer,
  isRendererExpandable,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
  approvalPending: boolean
  requestedExpanded: boolean
  hasCustomRenderer: boolean
  isRendererExpandable?: ToolRendererExpandabilityResolver
}): ToolMessageView {
  const callMessage = message.phase === 'call' ? message : undefined
  const resultMessage = result ?? (message.phase === 'result' ? message : undefined)
  const status = resultMessage?.status ?? message.status
  const argumentsStreaming =
    callMessage != null
    && callMessage.subjectEntryId == null
    && callMessage.status === 'streaming'
  const context: ToolRenderContext = {
    toolName: message.toolName || 'Tool',
    arguments: callMessage?.arguments || resultMessage?.arguments || message.arguments,
    text: resultMessage?.text ?? message.text,
    attachments: resultMessage?.attachments ?? message.attachments,
    status,
    errorMessage: resultMessage?.errorMessage ?? message.errorMessage,
    partial: callMessage?.partial ?? resultMessage?.partial ?? message.partial,
    partialErrorText: callMessage?.partialErrorText ?? resultMessage?.partialErrorText,
    partialAttachments: callMessage?.partialAttachments ?? resultMessage?.partialAttachments,
    approval: callMessage?.approval ?? resultMessage?.approval ?? message.approval,
    approvalPending,
    argumentsStreaming,
  }
  const preview = previewForToolCall(context.toolName, context.arguments)
  const summary = formatToolCallSummary(context.toolName, context.arguments)
  const rendererExpandable = isRendererExpandable?.(callMessage, resultMessage)
  const expandable = hasExpandableContent(
    context,
    preview,
    summary.coversArguments,
    hasCustomRenderer,
    rendererExpandable,
  )
  const expanded = expandable && requestedExpanded
  return {
    callMessage,
    resultMessage,
    context,
    preview,
    summary,
    visualState: toolVisualState(resultMessage),
    expandable,
    expanded,
    showResult: shouldRenderResult(
      context,
      resultMessage != null,
      expanded,
      hasCustomRenderer,
    ),
  }
}

export function currentToolResultText(context: ToolRenderContext): string {
  return context.partial?.trim() ? (context.partial ?? '') : context.text
}

export function hasToolResultError(context: ToolRenderContext): boolean {
  return context.status === 'error'
    || Boolean(context.partialErrorText?.trim())
    || Boolean(context.errorMessage)
}

function toolVisualState(
  result?: ToolDialogueMessage,
): ToolVisualState {
  if (result == null || result.status === 'streaming') {
    return 'pending'
  }
  if (result.status === 'error' || Boolean(result.errorMessage)) {
    return 'error'
  }
  return 'success'
}

function shouldRenderResult(
  context: ToolRenderContext,
  hasDurableResult: boolean,
  expanded: boolean,
  customRenderer: boolean,
): boolean {
  if (customRenderer && !hasDurableResult) {
    return false
  }
  const hasPartial = Boolean(context.partial?.trim())
  const text = currentToolResultText(context)
  const attachments = context.partialAttachments?.length
    ? context.partialAttachments
    : context.attachments
  if (hasToolResultError(context) || attachments.length > 0) {
    return true
  }
  if (
    !customRenderer
    && !hasDurableResult
    && hasPartial
    && shouldSuppressTransientToolResult(context.toolName, text)
  ) {
    return false
  }
  if (expanded) {
    return hasDurableResult || text.trim().length > 0
  }
  return formatToolResultPreview(
    context.toolName,
    text,
    { expanded: false, error: false },
  ).text.trim().length > 0
}

function hasExpandableContent(
  context: ToolRenderContext,
  preview: ToolCallPreview | null,
  summaryCoversArguments: boolean,
  customRenderer: boolean,
  rendererExpandable: boolean | undefined,
): boolean {
  if (customRenderer && rendererExpandable !== undefined) {
    return rendererExpandable
  }
  if (preview && formatToolCallLinePreview(preview.lines, {
    expanded: false,
    streaming: context.argumentsStreaming,
    full: preview.kind === 'edit' && !context.argumentsStreaming,
  }).truncated) {
    return true
  }
  const hasArguments = context.arguments.trim().length > 0
  if (!customRenderer && !preview && !summaryCoversArguments && hasArguments) {
    return true
  }
  return formatToolResultPreview(
    context.toolName,
    currentToolResultText(context),
    { expanded: false, error: hasToolResultError(context) },
  ).truncated
}
