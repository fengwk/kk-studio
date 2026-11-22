import type { TranslationKey } from '@/shared/i18n'
import type { IssueActivityKind, IssueRunStatus } from '@/shared/api/generated/contracts'

/** 工作流阶段编码到名称的映射：界面以阶段名称为主，编码只留在提示与诊断里。 */
export type IssueStateNames = ReadonlyMap<string, string>

/** Run 状态按执行事实命名；看板卡片与 Issue 详情共用这一套文案。 */
export const RUN_STATUS_LABEL_KEY: Record<IssueRunStatus, TranslationKey> = {
  RUNNING: 'projects.issue.running',
  WAITING: 'projects.issue.runStatus.waiting',
  COMPLETED: 'projects.issue.runStatus.completed',
  FAILED: 'projects.issue.runStatus.failed',
  CANCELLED: 'projects.issue.runStatus.cancelled',
}

/** 活动类型同样按事实命名，原始枚举码只留在提示里。 */
export const ACTIVITY_KIND_LABEL_KEY: Record<IssueActivityKind, TranslationKey> = {
  COMMENT: 'projects.issue.activityKind.comment',
  RUN: 'projects.issue.activityKind.run',
  INSTRUCTION: 'projects.issue.activityKind.instruction',
  SPEC_CHANGE: 'projects.issue.activityKind.specChange',
  STATE_CHANGE: 'projects.issue.activityKind.stateChange',
  CONTROL: 'projects.issue.activityKind.control',
}

/** 由工作流声明构建阶段名称映射；没有工作流（快照缺失）时得到空映射。 */
export function toStateNames(
  states: readonly { state: string; name: string }[] | undefined,
): IssueStateNames {
  return new Map((states ?? []).map((state) => [state.state, state.name]))
}

/** 阶段名称查找：工作流未声明的编码（如历史状态）原样返回，保证该阶段仍可读。 */
export function stateNameOf(stateNames: IssueStateNames, state: string): string {
  return stateNames.get(state) ?? state
}

/**
 * 面向说明文案的阶段名称：只有工作流为该阶段声明了可读名称时才返回，
 * 未声明或名称与编码相同时返回 null，让文案退回通用说法而不是露出内部编码。
 */
export function readableStateNameOf(stateNames: IssueStateNames, state: string): string | null {
  const name = stateNames.get(state)?.trim()
  return name && name !== state ? name : null
}
