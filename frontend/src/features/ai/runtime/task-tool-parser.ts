import type { TaskRunState } from '@/features/ai/runtime/task-status'

export type TaskFinalState = 'completed' | 'error' | 'cancelled'

export interface TaskToolArguments {
  subagentType: string | null
  prompt: string | null
  sessionId: string | null
  maxTurns: number | null
}

export interface TaskFinalResult {
  state: TaskFinalState | null
  report: string | null
  error: string | null
}

/**
 * 解析 task 调用参数 JSON（后端 TaskTool.parseArguments 的只读投影）：
 * subagent_type / prompt 必填，session_id / maxTurns 可选。
 * 非数字的 maxTurns 直接丢弃为 null，绝不 Number() 强转。
 */
export function parseTaskArguments(json: string): TaskToolArguments {
  let value: unknown
  try {
    value = JSON.parse(json)
  } catch {
    return { subagentType: null, prompt: null, sessionId: null, maxTurns: null }
  }
  if (!isRecord(value)) {
    return { subagentType: null, prompt: null, sessionId: null, maxTurns: null }
  }
  return {
    subagentType: nonBlankString(value.subagent_type),
    prompt: nonBlankString(value.prompt),
    sessionId: nonBlankString(value.session_id),
    maxTurns: positiveInteger(value.maxTurns),
  }
}

/**
 * 解析 task 的最终文本（后端 TaskTool.complete 生成）：
 *
 *   完成：<task id="..." state="completed">\n<task_result>\n报告\n</task_result>\n</task>
 *   失败/取消：<task id="..." state="error|cancelled">\n<task_error>原因</task_error>\n</task>
 *
 * 字段顺序与空白变化不影响语义；无法识别为 task 终态时返回 null，
 * 由调用方回退到原始文本展示（绝不能把 task.status 心跳 JSON 当正文）。
 */
export function parseTaskFinalText(text: string): TaskFinalResult | null {
  const openTag = /<task\b[^>]*>/i.exec(text)?.[0]
  if (openTag == null) {
    return null
  }
  const stateMatch = /\bstate\s*=\s*["']([^"']+)["']/i.exec(openTag)
  const rawState = stateMatch?.[1]
  const state =
    rawState === 'completed' || rawState === 'error' || rawState === 'cancelled'
      ? rawState
      : null
  const report = extractTagContent(text, 'task_result')
  const error = extractTagContent(text, 'task_error')
  if (state == null && report == null && error == null) {
    return null
  }
  return { state, report, error }
}

/** 运行状态 -> i18n key（widget 与 renderer 共用同一组文案）。 */
export function runStateKey(state: TaskRunState): string {
  switch (state) {
    case 'queued':
      return 'ai.runtime.task.state.queued'
    case 'running_model':
      return 'ai.runtime.task.state.runningModel'
    case 'running_tool':
      return 'ai.runtime.task.state.runningTool'
    case 'waiting_approval':
      return 'ai.runtime.task.state.waitingApproval'
  }
}

function extractTagContent(text: string, tag: string): string | null {
  const openTag = `<${tag}>`
  const closeTag = `</${tag}>`
  const start = text.indexOf(openTag)
  if (start < 0) {
    return null
  }
  const contentStart = start + openTag.length
  // report/error 来自模型文本，内容本身可能提及同名闭合标签；后端 wrapper 的
  // 闭合标签一定是最后一个，因此从尾部界定才能完整保留报告。
  const end = text.lastIndexOf(closeTag)
  if (end < 0) {
    return null
  }
  const content = text.slice(contentStart, end).trim()
  return content.length > 0 ? content : null
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    ? value
    : null
}

function nonBlankString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
