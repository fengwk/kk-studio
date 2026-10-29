import {
  calculateCacheHitRate,
  calculateDecodeTokensPerSecond,
  formatTurnUsageText,
  formatUsageCost,
} from '@/features/chat/runtime/thread-timeline/content-utils'
import type { TurnUsage } from '@/features/chat/runtime/thread-timeline-types'
import { translate, type TranslationKey } from '@/shared/i18n'

/** 注入式翻译函数：默认使用全局 catalog，便于对格式化本身做确定性验证。 */
export type TranslateFn = (key: TranslationKey, values?: Record<string, string | number>) => string

/** Footer 唯一一行的分组分隔符（U+2223）；组内统计项由 formatTurnUsageText 使用 U+00B7。 */
export const FOOTER_SEGMENT_SEPARATOR = ' ∣ '

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
  /** Environment 身份；null 时只读展示 `未选择环境`。 */
  environment?: EnvironmentStatusIdentity | null
  /** 实时可用标记；false 时展示 unavailable 事实。 */
  environmentReady?: boolean
  /** 当前 root-to-head 累计 usage；缺失费用与测速样本保持未知。 */
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
export function buildThreadStatusModel(
  input: ThreadStatusModelInput,
  t: TranslateFn = translate,
): ThreadStatusModel {
  const segments: ThreadStatusSegment[] = []
  const binding = input.environment
  const environmentName = binding ? clean(binding.environmentName || binding.environmentId) : ''
  if (environmentName) {
    const unavailable = input.environmentReady === false
    segments.push({
      key: 'environment',
      text: unavailable
        ? t('ai.runtime.status.environmentUnavailableText', { name: environmentName })
        : t('ai.runtime.status.environmentText', { name: environmentName }),
      title: unavailable
        ? t('ai.runtime.status.environmentUnavailableTitle', { name: environmentName })
        : t('ai.runtime.status.environmentTitle', { name: environmentName }),
    })
  } else {
    const noneText = t('ai.runtime.status.environmentNoneText')
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
    segments.push({
      key: 'context',
      text: t('ai.runtime.status.contextText', {
        used: hasContext ? formatCompactNumber(usedContext) : t('ai.runtime.status.noData'),
        total: formatCompactNumber(contextWindow),
      }),
      title: hasContext
        ? t('ai.runtime.status.contextUsageTitleKnown', {
          used: String(usedContext),
          total: String(contextWindow),
        })
        : t('ai.runtime.status.contextUsageTitleUnknown', {
          total: String(contextWindow),
        }),
    })
  }

  // 累计用量与回合摘要共用 U+00B7 统计项分隔；主行保持紧凑，hover 给完整数字与全称明细。
  segments.push({
    key: 'usage',
    text: formatTurnUsageText(usage, t),
    title: formatUsageDetails(usage, t),
  })

  return { segments }
}

/**
 * 用量 hover 读数：完整数字 + 全称字段（含推理），不带冗余的累计标题；
 * 无可用定价/无测速样本时如实标注未知，绝不伪造成 $0。
 *
 * Footer 的累计用量与回合 footer 的单回合用量共用同一份详情格式化：调用方必须
 * 传入真实存在的 usage facts（缺失时不得调用，避免伪造读数）。
 */
export function formatUsageDetails(usage: TurnUsage, t: TranslateFn): string {
  const cacheHitRate = calculateCacheHitRate(usage)
  const speed = calculateDecodeTokensPerSecond(usage)
  const noData = t('ai.runtime.status.noData')
  const cost = formatUsageCost(usage.cost)
  return [
    t('ai.runtime.status.usageTokensDetail', {
      input: usage.input != null ? String(usage.input) : noData,
      output: usage.output != null ? String(usage.output) : noData,
      reasoning: usage.reasoning != null ? String(usage.reasoning) : noData,
    }),
    t('ai.runtime.status.usageCacheDetail', {
      cacheRead: usage.cacheRead != null ? String(usage.cacheRead) : noData,
      cacheWrite: usage.cacheWrite != null ? String(usage.cacheWrite) : noData,
    }),
    t('ai.runtime.status.usageCostDetail', {
      cost: cost ?? noData,
      cache: cacheHitRate != null ? `${cacheHitRate.toFixed(2)}%` : noData,
      speed: `${speed ?? noData} tok/s`,
    }),
  ].join('\n')
}

const EMPTY_USAGE: TurnUsage = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  reasoning: null,
  providerTotal: null,
  cost: null,
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
 * 格式化 Thread 运行状态（RUNNING / WAITING / IDLE）。
 */
export function formatThreadStatusLabel(
  status?: string | null,
  t: (key: TranslationKey) => string = translate,
): string {
  if (!status) {
    return ''
  }
  switch (status) {
    case 'RUNNING':
      return t('ai.runtime.thread.status.RUNNING')
    case 'WAITING':
      return t('ai.runtime.thread.status.WAITING')
    case 'IDLE':
      return t('ai.runtime.thread.status.IDLE')
    default:
      return status
  }
}
