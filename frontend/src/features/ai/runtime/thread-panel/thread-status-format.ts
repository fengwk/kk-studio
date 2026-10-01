import { formatTurnUsageText } from '@/features/ai/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/ai/runtime/thread-timeline-types'
import { translate } from '@/shared/i18n'

/**
 * Footer 展示所需的 Environment 身份。
 */
export interface EnvironmentStatusIdentity {
  environmentId: string
  environmentName?: string | null
}

export interface ThreadStatusSegment {
  key: 'environment' | 'context' | 'usage'
  text: string
  title: string
}

export interface ThreadStatusModel {
  segments: ThreadStatusSegment[]
}

export interface ThreadStatusModelInput {
  /** Environment 身份；null 时只读展示 `none env`。 */
  environment?: EnvironmentStatusIdentity | null
  /** 实时可用标记；false 时展示 unavailable 事实。 */
  environmentReady?: boolean
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
  const environmentName = binding ? clean(binding.environmentName || binding.environmentId) : ''
  if (environmentName) {
    const text = input.environmentReady === false
      ? translate('ai.runtime.status.environmentUnavailableText', {
        name: environmentName,
      })
      : translate('ai.runtime.status.environmentText', {
        name: environmentName,
      })
    segments.push({
      key: 'environment',
      text,
      title: text,
    })
  } else {
    const noneText = translate('ai.runtime.status.environmentNoneText')
    segments.push({
      key: 'environment',
      text: noneText,
      title: noneText,
    })
  }

  const usage = input.branchUsage ?? EMPTY_USAGE

  // 上下文只使用最新调用的输入估计；缺失时不能用分支累计量替代。
  const contextWindow = positiveFinite(input.contextWindow)
  const usedContext = usage.contextInputTokens
  const hasContext = usedContext != null && Number.isFinite(usedContext) && usedContext >= 0
  if (contextWindow != null) {
    const text = translate('ai.runtime.status.contextText', {
      used: hasContext ? formatCompactNumber(usedContext) : '—',
      total: formatCompactNumber(contextWindow),
    })
    segments.push({
      key: 'context',
      text,
      title: translate('ai.runtime.status.contextTitle', {
        used: hasContext ? String(usedContext) : '—',
        total: String(contextWindow),
      }),
    })
  }

  // 累计用量与回合摘要共用格式，避免缓存率和速率口径漂移。
  const usageText = formatTurnUsageText(usage)
  segments.push({
    key: 'usage',
    text: usageText,
    title: translate('ai.runtime.status.branchUsageTitle', { usage: usageText }),
  })

  return { segments }
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

/**
 * 格式化 Thread 运行状态，为 WAITING_CHILDREN / QUEUED 等提供准确的本地化文案。
 */
export function formatThreadStatusLabel(
  status?: string | null,
  t: (key: string) => string = translate,
): string {
  if (!status) {
    return ''
  }
  switch (status) {
    case 'WAITING_CHILDREN':
      return t('ai.runtime.thread.status.WAITING_CHILDREN')
    case 'QUEUED':
      return t('ai.runtime.thread.status.QUEUED')
    case 'IDLE':
      return t('ai.runtime.thread.status.IDLE')
    case 'CONTINUATION_DUE':
      return t('ai.runtime.thread.status.CONTINUATION_DUE')
    case 'APPLYING':
      return t('ai.runtime.thread.status.APPLYING')
    case 'MODEL_READY':
      return t('ai.runtime.thread.status.MODEL_READY')
    case 'MODEL_DISPATCHING':
      return t('ai.runtime.thread.status.MODEL_DISPATCHING')
    case 'MODEL_RUNNING':
      return t('ai.runtime.thread.status.MODEL_RUNNING')
    case 'TOOL_WAITING_APPROVAL':
      return t('ai.runtime.thread.status.TOOL_WAITING_APPROVAL')
    case 'TOOL_READY':
      return t('ai.runtime.thread.status.TOOL_READY')
    case 'TOOL_DISPATCHING':
      return t('ai.runtime.thread.status.TOOL_DISPATCHING')
    case 'TOOL_RUNNING':
      return t('ai.runtime.thread.status.TOOL_RUNNING')
    default:
      return status
  }
}
