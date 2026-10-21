import type { PaneTarget } from '@/features/ai/runtime/agent-pane'

export type PaneInteraction =
  | 'subagent'
  | 'agent'
  | 'shortcuts'
  | 'history'
  | 'thread-sessions'
  | 'thread-threads'
  | 'rename-session'
  | 'rename-thread'
  | 'goal'
  | null

export type RenameKind = 'session' | 'thread'

/**
 * 单输入重命名面板的目标实体。name 是当前权威名称：thread 目标在 bound 快照
 * 就绪后直接得到；session 目标先为 null，owner Sessions 摘要 on-demand 到达后
 * 由 effect 填充。绝不把名称持久化进 PaneTarget。
 */
export interface RenameTarget {
  kind: RenameKind
  id: string
  /** 当前权威名称；null 表示仍在解析（panel 进入 busy 状态）。 */
  name: string | null
  /** 面板关闭后返回的交互；null 表示回到主面板。 */
  backTo: 'thread-sessions' | 'thread-threads' | null
}

export interface AgentPaneCapabilities {
  allowNewSession?: boolean
  readOnly?: boolean
  allowSwitchAgent?: boolean
  allowBranching?: boolean
}

export interface AgentPaneDefaults {
  agentName?: string
  yoloEnabled?: boolean
  environmentName?: string | null
}

/** 新建分支请求：命名与目标 pane 由 Chat workspace 统一持有。 */
export interface BranchRequestInput {
  sessionId: string
  startEntryId: string
  /** 触发分支的 pane 当前分支名（用于目标选择与提示）；未知时为 null。 */
  sourceLabel: string | null
}

/**
 * 面板运行时摘要。只用于 workspace 的目标路由（草稿确认/在途阻止）与顶栏面包屑，
 * 不参与任何持久化，也不是跨 pane 的 storage sidechannel。
 */
export interface PaneReport {
  target: PaneTarget
  draftKey: string
  pending: boolean
  hasUnsentDraft: boolean
  sessionId: string | null
  branchName: string | null
}

export function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  return fallback
}
