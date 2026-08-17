import { ToolAttachmentList } from '@/features/ai/runtime/thread-panel/messages/ToolAttachmentList'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import {
  formatToolCallLinePreview,
  formatToolResultPreview,
} from '@/features/ai/runtime/thread-panel/messages/tool-display'
import type { ToolCallPreview } from '@/features/ai/runtime/thread-panel/messages/tool-previews'
import {
  currentToolResultText,
  hasToolResultError,
  type ToolRenderContext,
} from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import { translate } from '@/shared/i18n'

/** 未注册专属 renderer 时的 call 参数/代码/diff 展示。 */
export function DefaultToolCall({
  context,
  preview,
  expanded,
  summaryCoversArguments,
}: {
  context: ToolRenderContext
  preview: ToolCallPreview | null
  expanded: boolean
  summaryCoversArguments: boolean
}) {
  if (preview != null) {
    const fullPreview =
      expanded || (preview.kind === 'edit' && !context.argumentsStreaming)
    const linePreview = formatToolCallLinePreview(preview.lines, {
      expanded,
      streaming: context.argumentsStreaming,
      full: fullPreview,
    })
    if (linePreview.lines.length === 0) {
      return null
    }
    return (
      <div className="thread-tool-call-body thread-tool-preview">
        <pre
          className={[
            'thread-tool-pre',
            'thread-tool-input',
            'thread-tool-preview-body',
            `is-${preview.kind}`,
            context.argumentsStreaming ? 'is-streaming' : '',
            expanded ? 'is-expanded' : '',
            fullPreview ? 'is-unbounded' : '',
          ].filter(Boolean).join(' ')}
        >
          {linePreview.lines.map((line, index) => (
            <span
              key={`${preview.kind}-${index}`}
              className={diffLineClass(line)}
            >
              {line}
              {index < linePreview.lines.length - 1 ? '\n' : ''}
            </span>
          ))}
        </pre>
      </div>
    )
  }
  if (!expanded || summaryCoversArguments) {
    return null
  }
  if (!context.arguments.trim()) {
    return (
      <div className="thread-tool-call-body">
        <span className="thread-tool-placeholder">
          {translate('ai.runtime.message.noArguments')}
        </span>
      </div>
    )
  }
  return (
    <div className="thread-tool-call-body">
      <pre className="thread-tool-pre thread-tool-input">{context.arguments}</pre>
    </div>
  )
}

/** 未注册专属 renderer 时的文本、附件与错误结果展示。 */
export function DefaultToolResult({
  context,
  expanded,
}: {
  context: ToolRenderContext
  expanded: boolean
}) {
  const text = currentToolResultText(context)
  const output = formatToolResultPreview(
    context.toolName,
    text,
    { expanded, error: hasToolResultError(context) },
  )
  const hasText = output.text.trim().length > 0
  const attachments = context.partialAttachments?.length
    ? context.partialAttachments
    : context.attachments
  const hasAttachments = attachments.length > 0
  const emptyPlaceholder = placeholder(context)
  return (
    <>
      {hasText ? (
        <ToolOutputViewport text={output.text} maxLines={output.maxLines} />
      ) : null}
      {!hasText && !hasAttachments ? (
        <p className="thread-tool-placeholder">{emptyPlaceholder}</p>
      ) : null}
      {hasAttachments ? <ToolAttachmentList attachments={attachments} /> : null}
      {context.partialErrorText?.trim() ? (
        <p className="thread-tool-error">{context.partialErrorText}</p>
      ) : null}
      {context.errorMessage
      && context.errorMessage !== text
      && context.errorMessage !== emptyPlaceholder ? (
        <p className="thread-tool-error">{context.errorMessage}</p>
      ) : null}
    </>
  )
}

function diffLineClass(line: string): string {
  if (line.startsWith('+')) {
    return 'is-added'
  }
  if (line.startsWith('-')) {
    return 'is-removed'
  }
  return 'is-context'
}

function placeholder(context: ToolRenderContext): string {
  if (context.status === 'error') {
    return translate('ai.runtime.message.toolFailed')
  }
  if (context.status === 'streaming') {
    return translate('ai.runtime.message.waitingTool')
  }
  return translate('ai.runtime.message.noTextOutput')
}
