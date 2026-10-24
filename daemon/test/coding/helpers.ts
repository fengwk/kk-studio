import type { CapabilityCall, CapabilityContext, CapabilityExecutor, CapabilityResult, DaemonConfig } from '../../src/contracts.js'
import { TextOutputStore } from '../../src/coding/output.js'

/** 测试用最小配置：只填能力读取的字段。 */
export function makeConfig(dataDir: string): DaemonConfig {
  return {
    configPath: `${dataDir}/config.json`,
    dataDir,
    tokenPath: `${dataDir}/daemon.token`,
    studioUrl: 'http://studio.test',
    gatewayUri: 'http://studio.test',
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir: dataDir,
  }
}

/**
 * 打开输出存储；默认私有文件端口即生产 native 端口（测试同样走真实 main 安全边界，无 fallback）。
 */
export function openTestStore(
  dir: string,
  captureBudgetBytes?: number,
  now?: () => number,
): Promise<TextOutputStore> {
  return TextOutputStore.open(dir, captureBudgetBytes, now)
}

export interface ContextOverrides {
  signal?: AbortSignal
  config?: DaemonConfig
  timeoutMillis?: number
  processes?: CapabilityContext['processes']
  progress?: (result: CapabilityResult) => Promise<void>
}

/** 测试用最小上下文；文件/搜索能力不触碰 processes/resources。 */
export function makeContext(dataDir: string, overrides: ContextOverrides = {}): CapabilityContext {
  return {
    config: overrides.config ?? makeConfig(dataDir),
    signal: overrides.signal ?? new AbortController().signal,
    processes: overrides.processes ?? ({} as CapabilityContext['processes']),
    resources: {} as CapabilityContext['resources'],
    progress: overrides.progress ?? (async () => {}),
    confirmExecutionExit: () => {},
  }
}

export async function callCapability(
  executor: CapabilityExecutor,
  capabilityId: string,
  args: Record<string, unknown>,
  context: CapabilityContext,
  timeoutMillis = 60000,
): Promise<CapabilityResult> {
  const call: CapabilityCall = {
    invocationId: 'inv-1',
    capabilityId,
    arguments: args,
    timeoutMillis,
  }
  return executor(call, context)
}

export function textOf(result: CapabilityResult): string {
  const first = result.contents[0]
  return first !== undefined && first.type === 'text' ? first.text : ''
}
