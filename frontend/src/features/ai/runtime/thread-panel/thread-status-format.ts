import { calculateDecodeTokensPerSecond } from '@/features/ai/runtime/thread-timeline/content-utils'
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
  key: 'environment' | 'context' | 'usage' | 'cache' | 'speed'
  className: string
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
      className: 'thread-status-environment',
      text,
      title: text,
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
      className: 'thread-status-context',
      text,
      title: translate('ai.runtime.status.contextTitle', {
        used: hasContext ? String(usedContext) : '—',
        total: String(contextWindow),
      }),
    })
  }

  // 3. 累计 usage
  const usageText = formatBranchUsage(usage)
  segments.push({
    key: 'usage',
    className: 'thread-status-usage',
    text: usageText,
    title: translate('ai.runtime.status.branchUsageTitle', { usage: usageText }),
  })

  // 4. cache N%：分母为 0 显示 cache —
  const cacheDenominator = usage.input + usage.cacheRead + usage.cacheWrite
  if (cacheDenominator > 0) {
    const percent = Math.round((usage.cacheRead / cacheDenominator) * 100)
    segments.push({
      key: 'cache',
      className: 'thread-status-cache',
      text: translate('ai.runtime.status.cacheHitText', { percent }),
      title: translate('ai.runtime.status.cacheHitTitle', { percent }),
    })
  } else {
    segments.push({
      key: 'cache',
      className: 'thread-status-cache',
      text: translate('ai.runtime.status.cacheHitNoneText'),
      title: translate('ai.runtime.status.cacheHitNoneTitle'),
    })
  }

  // 5. tok/s：复用统一速率计算，无样本显示 — tok/s
  const speed = calculateDecodeTokensPerSecond(usage)
  if (speed != null) {
    segments.push({
      key: 'speed',
      className: 'thread-status-speed',
      text: translate('ai.runtime.status.speedText', { speed }),
      title: translate('ai.runtime.status.speedTitle', { speed }),
    })
  } else {
    segments.push({
      key: 'speed',
      className: 'thread-status-speed',
      text: translate('ai.runtime.status.speedNoneText'),
      title: translate('ai.runtime.status.speedNoneTitle'),
    })
  }

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
