import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/ai/runtime/thread-panel/messages/tool-previews'

/**
 * call 阶段下移的大参数正文：write 写入内容与 edit 执行前 diff。
 *
 * 默认即完整可见并可内部回看（不再按行截断或省略行数）；参数仍在流式生成时标记为
 * streaming，提醒这不是最终审查材料。edit 用 old/new 生成拟执行修改预览，绝不重复原文。
 */
export function ToolCallPreview({
  preview,
  streaming,
}: {
  preview: ToolCallPreviewData
  streaming: boolean
}) {
  if (preview.lines.length === 0) {
    return null
  }
  return (
    <div className="thread-tool-call-body">
      <ToolOutputViewport
        followKey={preview.lines.join('\n')}
        className={[
          'thread-tool-preview-body',
          `is-${preview.kind}`,
          streaming ? 'is-streaming' : '',
        ].filter(Boolean).join(' ')}
      >
        {preview.lines.map((line, index) => (
          <span
            key={`${preview.kind}-${index}`}
            className={preview.kind === 'edit' ? diffLineClass(line) : undefined}
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
  return 'is-context'
}
