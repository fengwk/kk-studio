import {
  ApprovalArgumentsSection,
  EditDiffSection,
  EditInputSection,
  EditPreparingSection,
  PreviewTarget,
  WriteContentSection,
  WriteReviewSection,
} from '@/features/thread/runtime/thread-panel/messages/ToolCallPreviewSections'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/thread/runtime/thread-panel/messages/tool-previews'
import './tool-card.css'

/**
 * 共享 mutation 审查组件：对话记录与审批卡片使用同一份数据。
 *
 * - `variant="approval"`：保留路径行与有界内部滚动，审批卡片行为不变；
 * - `variant="transcript"`：普通正文、无内部滚动，Header 已有路径时不重复路径行；
 * - write 展示目标与完整内容（含空文件标识），edit 展示流式输入、准备态或后端冻结 diff。
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
  const bounded = variant === 'approval'
  const showTarget = variant === 'approval'
  const target = showTarget && preview?.path
    ? <PreviewTarget path={preview.path} workdir={preview.kind === 'write' ? preview.workdir : undefined} />
    : null

  if (!preview) {
    if (variant !== 'approval' || !argumentsJson) return null
    return <ApprovalArgumentsSection toolName={toolName} argumentsJson={argumentsJson} />
  }

  if (preview.kind === 'write') {
    if (variant === 'approval' && preview.review) {
      return (
        <div className="thread-tool-call-body">
          {target}
          <WriteReviewSection preview={preview} />
        </div>
      )
    }
    if (!preview.hasContent && !preview.content && !preview.path) {
      return null
    }
    return (
      <div className="thread-tool-call-body">
        {target}
        <WriteContentSection preview={preview} streaming={streaming} bounded={bounded} />
      </div>
    )
  }

  // Edit tool: preparing state while preflight computes editReview
  if (preview.mode === 'preparing') {
    return (
      <div className="thread-tool-call-body">
        {target}
        <EditPreparingSection />
      </div>
    )
  }

  // Edit tool: streamed input, one block per entry, explicitly labeled as model input
  if (preview.mode === 'input') {
    if (!preview.entries.some((entry) => entry.oldText || entry.newText)) {
      return null
    }
    return (
      <div className="thread-tool-call-body">
        {target}
        <EditInputSection preview={preview} bounded={bounded} />
      </div>
    )
  }

  // Edit tool: real multi-hunk numbered file diff from editReview
  if (preview.lines.length === 0) {
    return null
  }
  return (
    <div className="thread-tool-call-body">
      {target}
      <EditDiffSection preview={preview} streaming={streaming} bounded={bounded} />
    </div>
  )
}
