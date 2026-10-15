import type {
  PluginConnectionCheckRequestDTO,
  PluginConnectionCheckResult,
  PluginDTO,
} from '@/shared/api/contracts/ai-plugin'

/**
 * 一次显式连接检查在前端的完整状态：请求所依据的坐标、阶段与结果。
 *
 * 坐标随状态一起保存，才能在任何时刻判断结果是否仍对应当前配置；结果只在身份与坐标都与本次请求核对
 * 一致后才进入 `done`，因此 `done` 之外的阶段绝不携带结果。
 */
export type PluginConnectionCheckPhase = 'pending' | 'done' | 'mismatch' | 'conflict' | 'failed'

/** canonical 正十进制：已应用配置修订的合法形态。 */
const POSITIVE_DECIMAL = /^[1-9]\d*$/

export interface PluginConnectionCheckRun {
  pluginId: string
  expectedEpoch: string
  expectedRevision: string
  phase: PluginConnectionCheckPhase
  result: PluginConnectionCheckResult | null
  /** 只有 `failed` 时的可控错误文案；不外显远端原始值。 */
  errorMessage: string | null
}

/**
 * 可提交的期望坐标：只有实例真实声明能力、已应用坐标存在、修订为正且与本节点 desired 一致时才返回坐标。
 * 未应用、本地 pending、null 坐标或 0 修订一律返回 null，调用方据此禁用入口，绝不提交不可用坐标。
 */
export function pluginConnectionCheckExpected(
  plugin: PluginDTO,
): PluginConnectionCheckRequestDTO | null {
  if (!plugin.connectionCheckSupported) {
    return null
  }
  const { appliedEpoch, appliedRevision } = plugin
  if (appliedEpoch === null || appliedRevision === null) {
    return null
  }
  // 已应用实例的配置修订恒为正；0 表示尚无已应用配置，不得作为检查坐标提交。
  if (!POSITIVE_DECIMAL.test(appliedRevision)) {
    return null
  }
  if (plugin.desiredEpoch !== appliedEpoch || plugin.desiredRevision !== appliedRevision) {
    return null
  }
  return { expectedEpoch: appliedEpoch, expectedRevision: appliedRevision }
}

/**
 * 结果是否已不再对应当前配置：本次检查依据的坐标与调用方当前所见已应用坐标不一致，或该 Plugin 已不再
 * 可检查（含已从列表消失）时，结果只能显式标为陈旧，绝不继续冒充当前健康。
 */
export function pluginConnectionCheckIsStale(
  run: PluginConnectionCheckRun,
  current: PluginConnectionCheckRequestDTO | null,
): boolean {
  if (run.phase !== 'done' || run.result === null) {
    return false
  }
  return (
    current === null
    || current.expectedEpoch !== run.result.appliedEpoch
    || current.expectedRevision !== run.result.appliedRevision
  )
}
