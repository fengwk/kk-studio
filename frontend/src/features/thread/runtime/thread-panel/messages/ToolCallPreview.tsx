import { useI18n } from '@/shared/i18n'
import { ToolOutputViewport } from '@/features/thread/runtime/thread-panel/messages/ToolOutputViewport'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/thread/runtime/thread-panel/messages/tool-previews'
import './tool-card.css'

/**
 * 共享 mutation 审查组件：对话记录与审批卡片使用同一份数据。
 *
 * - `variant="approval"`：保留路径行与有界内部滚动，审批卡片行为不变；
 * - `variant="transcript"`：普通正文、无内部滚动，Header 已有路径时不重复路径行；
 * - write 展示目标与完整内容（含空文件标识），edit 展示流式输入、准备态或后端冻结 diff。
 */
export function ToolCallPreview({
  preview,
  streaming,
  variant,
  toolName,
  argumentsJson,
}: {
  preview?: ToolCallPreviewData | null
  streaming: boolean
  /** 调用方必须显式区分只读记录与审批交互卡片。 */
  variant: 'transcript' | 'approval'
  toolName?: string
  argumentsJson?: string
}) {
  const { t } = useI18n()
  const bounded = variant === 'approval'
  const showTarget = variant === 'approval'

  if (!preview) {
    if (variant !== 'approval' || !argumentsJson) return null
    let fields: Record<string, unknown> = {}
    try {
      const parsed: unknown = JSON.parse(argumentsJson)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        fields = parsed as Record<string, unknown>
      }
    } catch {
      return <div className="thread-tool-approval-unreadable">{t('ai.interaction.unreadableArguments')}</div>
    }
    const entries = toolName?.trim().toLowerCase() === 'bash'
      ? ['command', 'workdir', 'timeout_seconds', 'timeout_ms', 'timeout']
        .filter((key) => key in fields).map((key) => [key, fields[key]] as const)
      : Object.entries(fields)
    if (entries.length === 0) return null
    return (
      <dl className="thread-tool-approval-fields">
        {entries.map(([key, value]) => {
          const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2) ?? String(value)
          const long = text.length > 320
          return (
            <div className="thread-tool-approval-field" key={key}>
              <dt>{key.startsWith('timeout') ? 'timeout' : key}</dt>
              <dd>{long ? (
                <details>
                  <summary>{text.slice(0, 160)}…</summary>
                  <pre>{text}</pre>
                </details>
              ) : <pre className={key === 'command' ? 'is-command' : ''}>{text}</pre>}</dd>
            </div>
          )
        })}
      </dl>
    )
  }

  if (preview.kind === 'write') {
    if (!preview.hasContent && !preview.content && !preview.path) {
      return null
    }
    return (
      <div className="thread-tool-call-body">
        {showTarget && preview.path ? (
          <div className="thread-tool-preview-target">
            <span className="thread-tool-preview-target-path">{preview.path}</span>
            {preview.workdir ? (
              <span className="thread-tool-preview-target-workdir">· {preview.workdir}</span>
            ) : null}
          </div>
        ) : null}
        <ToolOutputViewport
          bounded={bounded}
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
        {showTarget && preview.path ? (
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
        {showTarget && preview.path ? (
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
                bounded={bounded}
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
                bounded={bounded}
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
      {showTarget && preview.path ? (
        <div className="thread-tool-preview-target">
          <span className="thread-tool-preview-target-path">{preview.path}</span>
        </div>
      ) : null}
      <ToolOutputViewport
        bounded={bounded}
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
