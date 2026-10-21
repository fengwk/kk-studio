import { useI18n } from '@/shared/i18n'
import { ToolOutputViewport } from '@/features/chat/runtime/thread-panel/messages/ToolOutputViewport'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/chat/runtime/thread-panel/messages/tool-previews'
import './tool-card.css'

/**
 * Shared mutation review component used by chat tool cards and actionable ApprovalCard.
 *
 * - write: shows target path/workdir and exact complete content before approval in bounded scroll default expanded,
 *   preserving all whitespace and newlines without line truncation (including explicit empty files).
 * - edit: streamed incoming old/new input explicitly labeled as input, then real persisted
 *   approvalJson.editReview / result details.editReview with real multi-hunk diff, or preparing state while waiting.
 */
export function ToolCallPreview({
  preview,
  streaming,
}: {
  preview: ToolCallPreviewData
  streaming: boolean
}) {
  const { t } = useI18n()

  if (preview.kind === 'write') {
    if (!preview.hasContent && !preview.content && !preview.path) {
      return null
    }
    return (
      <div className="thread-tool-call-body">
        <div className="thread-tool-preview-target">
          <span className="thread-tool-preview-target-path">{preview.path}</span>
          {preview.workdir ? (
            <span className="thread-tool-preview-target-workdir">({preview.workdir})</span>
          ) : null}
        </div>
        <ToolOutputViewport
          followKey={preview.content}
          className={[
            'thread-tool-preview-body',
            'is-write',
            streaming ? 'is-streaming' : '',
          ].filter(Boolean).join(' ')}
        >
          {preview.content.length > 0 ? (
            preview.content
          ) : (
            <span className="thread-tool-empty-content">{t('ai.runtime.mutation.emptyFile')}</span>
          )}
        </ToolOutputViewport>
      </div>
    )
  }

  // Edit tool: preparing state while preflight computes editReview
  if (preview.mode === 'preparing') {
    return (
      <div className="thread-tool-call-body">
        {preview.path ? (
          <div className="thread-tool-preview-target">
            <span className="thread-tool-preview-target-path">{preview.path}</span>
          </div>
        ) : null}
        <div className="thread-tool-preview-preparing">
          <span className="thread-tool-spinner" aria-hidden="true">⏳</span>
          <span>{t('ai.runtime.mutation.preparing')}</span>
        </div>
      </div>
    )
  }

  // Edit tool: streamed input (old_string / new_string) explicitly labeled as input
  if (preview.mode === 'input') {
    if (!preview.oldText && !preview.newText) {
      return null
    }
    return (
      <div className="thread-tool-call-body">
        {preview.path ? (
          <div className="thread-tool-preview-target">
            <span className="thread-tool-preview-target-path">{preview.path}</span>
          </div>
        ) : null}
        <div className="thread-tool-preview-input-section">
          <div className="thread-tool-preview-input-label">{t('ai.runtime.mutation.inputLabel')}</div>
          {preview.oldText ? (
            <div className="thread-tool-preview-input-block">
              <span className="thread-tool-preview-input-sublabel">{t('ai.runtime.mutation.oldString')}</span>
              <ToolOutputViewport
                followKey={preview.oldText}
                className="thread-tool-preview-body is-input-old"
              >
                {preview.oldText}
              </ToolOutputViewport>
            </div>
          ) : null}
          {preview.newText ? (
            <div className="thread-tool-preview-input-block">
              <span className="thread-tool-preview-input-sublabel">{t('ai.runtime.mutation.newString')}</span>
              <ToolOutputViewport
                followKey={preview.newText}
                className="thread-tool-preview-body is-input-new"
              >
                {preview.newText}
              </ToolOutputViewport>
            </div>
          ) : null}
        </div>
      </div>
    )
  }

  // Edit tool: real multi-hunk numbered file diff from editReview
  if (preview.lines.length === 0) {
    return null
  }
  return (
    <div className="thread-tool-call-body">
      {preview.path ? (
        <div className="thread-tool-preview-target">
          <span className="thread-tool-preview-target-path">{preview.path}</span>
        </div>
      ) : null}
      <ToolOutputViewport
        followKey={preview.diff}
        className={[
          'thread-tool-preview-body',
          'is-edit',
          streaming ? 'is-streaming' : '',
        ].filter(Boolean).join(' ')}
      >
        {preview.lines.map((line, index) => (
          <span
            key={`diff-${index}`}
            className={diffLineClass(line)}
          >
            {line}
            {index < preview.lines.length - 1 ? '\n' : ''}
          </span>
        ))}
      </ToolOutputViewport>
    </div>
  )
}

function diffLineClass(line: string): string {
  if (line.startsWith('+')) {
    return 'is-added'
  }
  if (line.startsWith('-')) {
    return 'is-removed'
  }
  if (line.startsWith('@')) {
    return 'is-hunk-header'
  }
  return 'is-context'
}
