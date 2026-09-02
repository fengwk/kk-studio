import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

/**
 * Environment binding 的只读形状。
 */
export interface EnvironmentBindingShape {
  environmentId?: string
  environmentName?: string
  name?: string
  workspacePath: string
}

export interface ThreadStatusSegment {
  key: 'environment' | 'git' | 'usage' | 'context' | 'cache'
  className: string
  text: string
  title: string
}

export interface ThreadStatusModel {
  segments: ThreadStatusSegment[]
}

export interface ThreadStatusModelInput {
  /** 完整 Environment binding；null 时只读展示 `none env`。 */
  environment?: EnvironmentBindingShape | null
  /** 实时可用标记；false 时展示 unavailable 事实。 */
  environmentReady?: boolean
  gitBranch?: string | null
  /** 当前 root-to-head 已关闭 Turn 的累计 usage；缺失时按 0 展示。 */
  branchUsage?: TurnUsage | null
  contextWindow?: number
}

/** 规范化空白占位字符串；对 null/undefined/'undefined'/'null'/'-' 返回 ""。 */
function clean(value?: string | null): string {
  const text = (value ?? '').trim()
  if (!text || text === '-' || text === 'undefined' || text === 'null') {
    return ''
  }
  return text
}

/** 由真实存在的 facts 构建稳定只读状态模型；缺失事实整段省略。 */
export function buildThreadStatusModel(input: ThreadStatusModelInput): ThreadStatusModel {
  const segments: ThreadStatusSegment[] = []
  const binding = input.environment
  const environmentName = binding ? clean(binding.environmentName || binding.name || binding.environmentId) : ''
  const environmentWorkspacePath = binding ? clean(binding.workspacePath) : ''
  if (environmentName && environmentWorkspacePath) {
    const fullWorkspace = workspaceDisplayPath(environmentWorkspacePath)
    const compactWorkspace = middleEllipsis(fullWorkspace, 42)
    const text = input.environmentReady === false
      ? translate('ai.runtime.status.environmentUnavailableText', {
        name: environmentName,
        workspace: compactWorkspace,
      })
      : translate('ai.runtime.status.environmentText', {
        name: environmentName,
        workspace: compactWorkspace,
      })
    const title = input.environmentReady === false
      ? translate('ai.runtime.status.environmentUnavailableText', {
        name: environmentName,
        workspace: fullWorkspace,
      })
      : translate('ai.runtime.status.environmentText', {
        name: environmentName,
        workspace: fullWorkspace,
      })
    segments.push({
      key: 'environment',
      className: 'thread-status-environment',
      text,
      title,
    })
  } else {
    const noneText = translate('ai.runtime.status.environmentNoneText')
    segments.push({
      key: 'environment',
      className: 'thread-status-environment',
      text: noneText,
      title: noneText,
    })
  }

  const gitBranch = clean(input.gitBranch)
  if (gitBranch) {
    segments.push({
      key: 'git',
      className: 'thread-status-git',
      text: translate('ai.runtime.status.gitBranchText', { branch: gitBranch }),
      title: translate('ai.runtime.status.gitBranchTitle', { branch: gitBranch }),
    })
  }

  const usage = input.branchUsage ?? EMPTY_USAGE
  const usageText = formatBranchUsage(usage)
  segments.push({
    key: 'usage',
    className: 'thread-status-usage',
    text: usageText,
    title: translate('ai.runtime.status.branchUsageTitle', { usage: usageText }),
  })
  const used = usage.input + usage.cacheRead + usage.cacheWrite
  const contextWindow = positiveFinite(input.contextWindow)
  if (contextWindow != null) {
    const text = translate('ai.runtime.status.contextText', {
      used: formatCompactNumber(used),
      total: formatCompactNumber(contextWindow),
    })
    segments.push({
      key: 'context',
      className: 'thread-status-context',
      text,
      title: translate('ai.runtime.status.contextTitle', {
        used: String(used),
        total: String(contextWindow),
      }),
    })
  }
  const percent = used > 0 ? Math.round((usage.cacheRead / used) * 100) : 0
  const cacheText = translate('ai.runtime.status.cacheHitText', { percent })
  segments.push({
    key: 'cache',
    className: 'thread-status-cache',
    text: cacheText,
    title: translate('ai.runtime.status.cacheHitTitle', { percent }),
  })
  return { segments }
}

function workspaceDisplayPath(path: string): string {
  return path === '.' ? '@/' : `@/${path}`
}

/** 中间省略保留路径首尾，title 始终携带完整安全 wire path。 */
export function middleEllipsis(value: string, maxLength: number): string {
  if (value.length <= maxLength || maxLength < 5) {
    return value
  }
  const remaining = maxLength - 1
  const head = Math.ceil(remaining / 2)
  const tail = Math.floor(remaining / 2)
  return `${value.slice(0, head)}…${value.slice(value.length - tail)}`
}

const EMPTY_USAGE: TurnUsage = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  reasoning: 0,
  providerTotal: 0,
  cost: 0,
}

function formatBranchUsage(usage: TurnUsage): string {
  const parts = [
    `↑${formatCompactNumber(usage.input)}`,
    `↓${formatCompactNumber(usage.output)}`,
  ]
  if (usage.cacheRead > 0) {
    parts.push(`R${formatCompactNumber(usage.cacheRead)}`)
  }
  if (usage.cacheWrite > 0) {
    parts.push(`W${formatCompactNumber(usage.cacheWrite)}`)
  }
  parts.push(`$${usage.cost.toFixed(3)}`)
  return parts.join(' · ')
}

function formatCompactNumber(value: number): string {
  if (value < 1_000) {
    return String(Math.round(value))
  }
  if (value < 10_000) {
    return `${(value / 1_000).toFixed(1)}k`
  }
  if (value < 1_000_000) {
    return `${Math.round(value / 1_000)}k`
  }
  return `${(value / 1_000_000).toFixed(1)}M`
}

function positiveFinite(value: number | undefined): number | null {
  return value != null && Number.isFinite(value) && value > 0 ? value : null
}
