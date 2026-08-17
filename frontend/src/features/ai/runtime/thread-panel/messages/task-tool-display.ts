import { parseTaskFinalText } from '@/features/ai/runtime/task-tool-parser'
import { formatToolResultPreview } from '@/features/ai/runtime/thread-panel/messages/tool-display'
import type { ToolRendererMessage } from '@/platform/extensions/types'

/** task 专属展开判断：call 参数或被折叠的最终报告存在时才需要 toggle。 */
export function isTaskToolRendererExpandable(
  call: ToolRendererMessage | undefined,
  result: ToolRendererMessage | undefined,
): boolean {
  if (call?.phase === 'call' && call.arguments.trim()) {
    return true
  }
  const resultMessage = result ?? (call?.phase === 'result' ? call : undefined)
  if (!resultMessage) {
    return false
  }
  const parsed = parseTaskFinalText(resultMessage.text)
  if (parsed != null) {
    return parsed.report != null
      && formatToolResultPreview(
        'task',
        parsed.report,
        { expanded: false, error: false },
      ).truncated
  }
  return formatToolResultPreview(
    'task',
    resultMessage.text,
    {
      expanded: false,
      error: resultMessage.status === 'error' || Boolean(resultMessage.errorMessage),
    },
  ).truncated
}
