import { parseTaskReceipt } from '@/features/ai/runtime/task-tool-parser'
import { formatToolResultPreview } from '@/features/ai/runtime/thread-panel/messages/tool-display'
import type { ToolRendererMessage } from '@/platform/extensions/types'

/** task 专属展开判断：call 参数存在或降级结果被折叠时才需要 toggle。 */
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
  const isError = resultMessage.status === 'error' || Boolean(resultMessage.errorMessage)
  const receipt = isError ? null : parseTaskReceipt(resultMessage.text)
  if (receipt != null) {
    // 成功受理收据是紧凑展示（状态 + Thread ID），无需展开切换
    return false
  }
  return formatToolResultPreview(
    'task',
    resultMessage.text,
    {
      expanded: false,
      error: isError,
    },
  ).truncated
}
