/** 收起态任务预览的最大字符数；预览只是前端截取，绝不请求模型生成摘要。 */
export const SUBAGENT_TASK_PREVIEW_LIMIT = 72

/**
 * 折叠态的任务短预览：把正文压成单行并截断，展开后仍展示逐字原文。
 * 只做前端文本截取，不解码、不替换任何字符。
 */
export function subagentTaskPreview(task: string | null): string | null {
  if (task == null) {
    return null
  }
  const flattened = task.replace(/\s+/gu, ' ').trim()
  if (!flattened) {
    return null
  }
  return flattened.length > SUBAGENT_TASK_PREVIEW_LIMIT
    ? `${flattened.slice(0, SUBAGENT_TASK_PREVIEW_LIMIT)}…`
    : flattened
}
