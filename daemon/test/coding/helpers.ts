import { chmod, mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import type { CapabilityCall, CapabilityContext, CapabilityExecutor, CapabilityResult, DaemonConfig } from '../../src/contracts.js'
import { TextOutputStore, type PrivateFs } from '../../src/coding/output.js'
import { configureSearchWorker } from '../../src/coding/search.js'

// 源码/测试态：让搜索能力在 Worker 线程加载 .ts 入口（真实并发，非假后端），钩子把相对 ./x.js 重写为 ./x.ts。
// 生产默认直接引用编译后的 ./search-worker.js，不经过该注入点，也不引用测试文件。
configureSearchWorker({
  workerPath: new URL('../../src/coding/search-worker.ts', import.meta.url),
  execArgv: [
    '--import',
    fileURLToPath(new URL('./ts-strip-hook.mjs', import.meta.url)),
    '--experimental-transform-types',
    '--no-warnings',
  ],
})

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
 * 测试用私有文件端口替身：行为等价于 native 的 owner-only 创建（0700/0600）。
 *
 * <p>生产默认端口惰性加载 native 切片，测试态不引入 native 依赖；替身只用于本片测试，不进入生产路径。
 */
export const testPrivateFs: PrivateFs = {
  async ensurePrivateDirectory(path: string): Promise<string> {
    await mkdir(path, { recursive: true, mode: 0o700 })
    await chmod(path, 0o700)
    return path
  },
  async writePrivateFile(path: string, data: string | Uint8Array): Promise<void> {
    await writeFile(path, typeof data === 'string' ? Buffer.from(data, 'utf8') : data, {
      mode: 0o600,
    })
    await chmod(path, 0o600)
  },
}

/** 打开注入了测试私有文件端口的输出存储。 */
export function openTestStore(
  dir: string,
  captureBudgetBytes?: number,
  now?: () => number,
): Promise<TextOutputStore> {
  return TextOutputStore.open(dir, captureBudgetBytes, now, testPrivateFs)
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
