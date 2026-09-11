import {
  DefaultToolCall,
  DefaultToolResult,
} from '@/features/ai/runtime/thread-panel/messages/DefaultToolRenderer'
import { ToolApprovalBar } from '@/features/ai/runtime/thread-panel/messages/ToolApprovalBar'
import {
  buildToolMessageView,
  type ToolApprovalDecision,
} from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type { ComponentType } from 'react'
import { useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import type {
  ToolRendererExpandabilityResolver,
  ToolRendererProps,
} from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

/** Tool 回合以一个全宽 surface 组合摘要、call preview 与按需展示的 result。 */
export function ToolMessageBlock({
  message,
  result,
  renderer: Renderer,
  isRendererExpandable,
  onDecideApproval,
  approvalPending = false,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
  renderer?: ComponentType<ToolRendererProps>
  isRendererExpandable?: ToolRendererExpandabilityResolver
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: ToolApprovalDecision,
  ) => void | Promise<void>
  approvalPending?: boolean
}) {
  const { t } = useI18n()
  const [requestedExpanded, setRequestedExpanded] = useState(false)
  const {
    callMessage,
    resultMessage,
    context,
    preview,
    summary,
    visualState,
    expandable,
    expanded,
    showResult,
  } = buildToolMessageView({
    message,
    result,
    approvalPending,
    requestedExpanded,
    hasCustomRenderer: Renderer != null,
    isRendererExpandable,
  })
  const summaryContent = (
    <span className="thread-tool-summary">
      <span className="thread-tool-name">{summary.name}</span>
      {summary.detail ? (
        <span className="thread-tool-summary-detail">{summary.detail}</span>
      ) : null}
    </span>
  )

  return (
    <div className={`thread-turn thread-turn-tool tool-state-${visualState}`}>
      <section
        className="thread-block thread-block-tool"
        data-tool-state={visualState}
        aria-busy={visualState === 'pending'}
      >
        <div className="thread-tool-surface">
          <div
            className={`thread-tool-header${expandable ? ' has-toggle' : ''}`}
            title={summary.text}
          >
            {summaryContent}
            {expandable ? (
              <button
                type="button"
                className="thread-tool-toggle"
                aria-expanded={expanded}
                aria-label={
                  expanded
                    ? t('ai.runtime.message.collapseTool')
                    : t('ai.runtime.message.expandTool')
                }
                title={
                  expanded
                    ? t('ai.runtime.message.collapseTool')
                    : t('ai.runtime.message.expandTool')
                }
                onClick={() => setRequestedExpanded((current) => !current)}
              >
                {expanded
                  ? <ChevronDown aria-hidden="true" />
                  : <ChevronRight aria-hidden="true" />}
              </button>
            ) : null}
          </div>
          {Renderer ? (
            callMessage ? (
              <div className="thread-tool-call-body">
                <Renderer message={callMessage} expanded={expanded} />
              </div>
            ) : null
          ) : (
            <DefaultToolCall
              context={context}
              preview={preview}
              expanded={expanded}
              summaryCoversArguments={summary.coversArguments}
            />
          )}
          <ToolApprovalBar
            context={context}
            onDecideApproval={onDecideApproval}
            message={callMessage ?? message}
          />
          {showResult ? (
            <div className="thread-tool-result-body">
              {Renderer && resultMessage ? (
                <Renderer message={resultMessage} expanded={expanded} />
              ) : !Renderer ? (
                <DefaultToolResult context={context} expanded={expanded} />
              ) : null}
            </div>
          ) : null}
        </div>
      </section>
    </div>
  )
}
