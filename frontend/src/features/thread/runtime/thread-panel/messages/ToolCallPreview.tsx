import {
  ApprovalArgumentsSection,
  EditDiffSection,
  EditInputSection,
  EditPreparingSection,
  LossyDecodeWarning,
  PreviewTarget,
  WriteContentSection,
  WriteReviewSection,
} from '@/features/thread/runtime/thread-panel/messages/ToolCallPreviewSections'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/thread/runtime/thread-panel/messages/tool-previews'
import { useI18n } from '@/shared/i18n'
import './tool-card.css'

/**
 * 共享 mutation 审查组件：对话记录与审批卡片使用同一份数据。
 *
 * - `variant="approval"`：保留路径行与有界内部滚动，审批卡片行为不变；
 * - `variant="transcript"`：由调用方传入短窗口或完整详情，Header 已有路径时不重复路径行；
 * - write 审批默认看到待写正文，可另外查看后端冻结 diff；只读记录在等待审批期间直接
 *   展示这份 diff，终态仍沿用待写正文这一既有展示。
 *
 * 分支只决定「渲染哪一段正文」，各段正文在 ToolCallPreviewSections。
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
  // 路径行只有 path；workdir 属于正文目标，仅 write 正文分支附带。
  const target = showTarget && preview?.path ? <PreviewTarget path={preview.path} /> : null

  if (!preview) {
    if (variant !== 'approval' || !argumentsJson) return null
    return <ApprovalArgumentsSection toolName={toolName} argumentsJson={argumentsJson} />
  }

  if (preview.kind === 'write') {
    // 审批卡是冻结材料的交互位：完整正文 + 可展开的审查 diff。
    if (variant === 'approval') {
      const review = preview.review ? <WriteReviewSection preview={preview} /> : null
      if (!preview.hasContent && review == null) {
        return null
      }
      return (
        <div className="thread-tool-call-body">
          {showTarget && preview.path ? (
            <PreviewTarget path={preview.path} workdir={preview.workdir} />
          ) : null}
          <LossyDecodeWarning lossyDecode={preview.review?.lossyDecode} />
          {preview.hasContent ? (
            <WriteContentSection preview={preview} streaming={streaming} bounded={bounded} />
          ) : null}
          {review != null && preview.hasContent ? (
            <details className="thread-tool-approval-review">
              <summary>{t('ai.runtime.mutation.reviewDiff')}</summary>
              {review}
            </details>
          ) : review}
        </div>
      )
    }
    // 只读记录（等待审批期间）：冻结审查 diff 就是权威材料，不把正文再摊一遍。
    if (preview.review != null) {
      return (
        <div className="thread-tool-call-body">
          <LossyDecodeWarning lossyDecode={preview.review.lossyDecode} />
          <WriteReviewSection preview={preview} bounded={false} />
        </div>
      )
    }
    if (!preview.hasContent) {
      return null
    }
    return (
      <div className="thread-tool-call-body">
        <WriteContentSection preview={preview} streaming={streaming} bounded={bounded} />
      </div>
    )
  }

  if (preview.mode !== 'review') {
    const showInput = variant === 'transcript' && preview.entries.some((entry) => entry.oldText || entry.newText)
    if (!showInput && preview.mode === 'input') return null
    return (
      <div className="thread-tool-call-body">
        {target}
        {preview.mode === 'preparing' ? <EditPreparingSection /> : null}
        {showInput ? <EditInputSection preview={preview} bounded={bounded} /> : null}
      </div>
    )
  }

  // Edit tool: real multi-hunk numbered file diff from editReview
  if (preview.diff === '') {
    return null
  }
  return (
    <div className="thread-tool-call-body">
      {target}
      <EditDiffSection preview={preview} streaming={streaming} bounded={bounded} />
    </div>
  )
}
