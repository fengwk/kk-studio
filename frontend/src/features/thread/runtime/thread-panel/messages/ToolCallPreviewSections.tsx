import { AlertTriangle } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import type { EditReviewLossyDecode } from '@/features/thread/runtime/state/mutation-review'
import { ToolOutputViewport } from '@/features/thread/runtime/thread-panel/messages/ToolOutputViewport'
import { diffLineClass } from '@/features/thread/runtime/thread-panel/messages/tool-preview-diff'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/thread/runtime/thread-panel/messages/tool-previews'

/**
 * mutation 预览的各分支正文。
 *
 * 每个 section 只渲染一种状态（write 的审查 diff / write 正文 / edit 准备态 /
 * edit 流式输入 / edit 多 hunk diff），不含分支判定与副作用；
 * 判定留在 {@link ToolCallPreview}，审批与记录两种 variant 的差异只有
 * `bounded`（内部滚动）与 `showTarget`（路径行）。
 */

/** 审批卡片专用的路径行；记录态由 Header 展示路径，不重复。 */
export function PreviewTarget({
  path,
  workdir,
}: {
  path: string
  workdir?: string
}) {
  return (
    <div className="thread-tool-preview-target">
      <span className="thread-tool-preview-target-path">{path}</span>
      {workdir ? (
        <span className="thread-tool-preview-target-workdir">· {workdir}</span>
      ) : null}
    </div>
  )
}

/** 有损解码警示：只在审查 JSON 声明 lossyDecode 时显示在 diff 上方。 */
export function LossyDecodeWarning({
  lossyDecode,
}: {
  lossyDecode?: EditReviewLossyDecode
}) {
  const { t } = useI18n()
  if (!lossyDecode) {
    return null
  }
  return (
    <div className="thread-tool-lossy-warning" role="note">
      <AlertTriangle size={14} aria-hidden="true" />
      <span>
        {lossyDecode.invalidSequences === 1
          ? t('ai.runtime.mutation.lossyDecode.one', { encoding: lossyDecode.encoding })
          : t('ai.runtime.mutation.lossyDecode.other', {
            count: lossyDecode.invalidSequences,
            encoding: lossyDecode.encoding,
          })}
      </span>
    </div>
  )
}

/** write 的审批审查 diff：后端冻结的 review diff，有界滚动。 */
export function WriteReviewSection({ preview }: { preview: Extract<ToolCallPreviewData, { kind: 'write' }> }) {
  const review = preview.review
  if (!review) {
    return null
  }
  return (
    <ToolOutputViewport bounded className="thread-tool-preview-body is-edit">
      {review.diff.split('\n').map((line, index, lines) => (
        <span key={`diff-${index}`} className={diffLineClass(line)}>
          {line}
          {index < lines.length - 1 ? '\n' : ''}
        </span>
      ))}
    </ToolOutputViewport>
  )
}

/** write 正文；空文件显式标识，流式写入时带过渡样式。 */
export function WriteContentSection({
  preview,
  streaming,
  bounded,
}: {
  preview: Extract<ToolCallPreviewData, { kind: 'write' }>
  streaming: boolean
  bounded: boolean
}) {
  const { t } = useI18n()
  return (
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
  )
}

/** edit 的准备态：preflight 还在计算 editReview。 */
export function EditPreparingSection() {
  const { t } = useI18n()
  return (
    <div className="thread-tool-preview-preparing">
      <span className="thread-tool-spinner" aria-hidden="true">⏳</span>
      <span>{t('ai.runtime.mutation.preparing')}</span>
    </div>
  )
}

/** edit 的流式输入：每条一个块，显式标注为模型输入而非 diff。 */
export function EditInputSection({
  preview,
  bounded,
}: {
  preview: Extract<ToolCallPreviewData, { entries: unknown[] }>
  bounded: boolean
}) {
  const { t } = useI18n()
  const total = preview.entries.length
  const entries = preview.entries
    .map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => entry.oldText || entry.newText)
  if (entries.length === 0) {
    return null
  }
  return (
    <div className="thread-tool-preview-input-section">
      <div className="thread-tool-preview-input-label">{t('ai.runtime.mutation.inputLabel')}</div>
      {entries.map(({ entry, index }) => (
        <div className="thread-tool-preview-input-block" key={`entry-${index}`}>
          {total > 1 || entry.replaceAll ? (
            <span className="thread-tool-preview-input-heading">
              {total > 1
                ? t('ai.runtime.mutation.changeOf', { index: index + 1, total })
                : null}
              {entry.replaceAll ? (
                <span className="thread-tool-preview-badge">
                  {t('ai.runtime.mutation.replaceAll')}
                </span>
              ) : null}
            </span>
          ) : null}
          {entry.oldText ? (
            <>
              <span className="thread-tool-preview-input-sublabel">
                {t('ai.runtime.mutation.oldString')}
              </span>
              <ToolOutputViewport
                bounded={bounded}
                className="thread-tool-preview-body is-input-old"
              >
                {entry.oldText}
              </ToolOutputViewport>
            </>
          ) : null}
          {entry.newText ? (
            <>
              <span className="thread-tool-preview-input-sublabel">
                {t('ai.runtime.mutation.newString')}
              </span>
              <ToolOutputViewport
                bounded={bounded}
                className="thread-tool-preview-body is-input-new"
              >
                {entry.newText}
              </ToolOutputViewport>
            </>
          ) : null}
        </div>
      ))}
    </div>
  )
}

/** edit 的真实多 hunk 行号 diff（review 已冻结）。 */
export function EditDiffSection({
  preview,
  streaming,
  bounded,
}: {
  preview: Extract<ToolCallPreviewData, { kind: 'edit'; mode: 'review' }>
  streaming: boolean
  bounded: boolean
}) {
  if (preview.lines.length === 0) {
    return null
  }
  return (
    <>
      <LossyDecodeWarning lossyDecode={preview.lossyDecode} />
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
    </>
  )
}

/** 审批卡片在没有 preview 时的兜底：展示将要执行的全部参数。 */
export function ApprovalArgumentsSection({
  toolName,
  argumentsJson,
}: {
  toolName?: string
  argumentsJson: string
}) {
  const { t } = useI18n()
  let fields: Record<string, unknown> = {}
  try {
    const parsed: unknown = JSON.parse(argumentsJson)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      fields = parsed as Record<string, unknown>
    }
  } catch {
    return <div className="thread-tool-approval-unreadable">{t('ai.interaction.unreadableArguments')}</div>
  }
  // 审批前用户必须看到将要执行的全部参数；bash 只把最关键的字段排在前面。
  const leading = toolName?.trim().toLowerCase() === 'bash' ? BASH_LEADING_FIELDS : []
  const entries = [
    ...leading.filter((key) => key in fields).map((key) => [key, fields[key]] as const),
    ...Object.entries(fields).filter(([key]) => !leading.includes(key)),
  ]
  if (entries.length === 0) return null
  return (
    <dl className="thread-tool-approval-fields">
      {entries.map(([key, value]) => {
        const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2) ?? String(value)
        const long = text.length > 320
        return (
          <div className="thread-tool-approval-field" key={key}>
            <dt>{key}</dt>
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

const BASH_LEADING_FIELDS = ['command', 'workdir', 'timeout_seconds']
