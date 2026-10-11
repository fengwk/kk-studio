import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

import type { CapabilityExecutor, DaemonConfig } from './contracts.js'
import {
  checkConfig,
  decodeBase64Args,
  invalidConfigurationLine,
  loadDaemonConfig,
  printInfoCommand,
} from './config.js'
import { Runtime, detectEnvironmentInfo } from './runtime.js'
import { createWebSocketTransport } from './transport/control.js'
import { installOutboundNetwork, ProxyConfigurationError } from './network.js'
import {
  UnsupportedPlatformError,
  acquireDirectoryLock,
  createProcessSupervisor,
  createSystemProxyResolver,
  ensurePrivateDirectory,
  type DirectoryLock,
} from './native/index.js'
import { fileCapabilities, setFileCapabilityHooks } from './coding/files.js'
import { searchCapabilities } from './coding/search.js'
import { processCapability } from './coding/process.js'
import { createLspCapabilities } from './coding/lsp.js'
import { releaseTextOutputStore, sweepTextOutputStores } from './coding/output.js'
import { createSkillCapability } from './skill/sync.js'
import { createIdeService } from './ide/service.js'
import { handleUpdate } from './update.js'

/**
 * Environment Daemon 独立进程入口与依赖装配。
 *
 * <p>唯一运行参数是 {@code --config}。数据目录在启动期以 owner-only 权限创建并持有目录锁：同一目录上的第二个 Daemon
 * 立即失败，而不是并发写同一份本地数据。子进程统一经 native supervisor。
 *
 * <p>{@code --check-config} 只读取并校验配置、同目录凭证与配置的 bash 可执行程序，不打开数据目录、不建立连接也不启动
 * 进程；失败时只在 stderr 输出单行安全诊断并以非零状态退出。
 *
 * <p>本文件把各 capability/服务切片的真实静态导出装配成运行时依赖：LSP 组的文件钩子注入 Files、受控临时输出的 TTL
 * 清扫入口注入 Runtime、IDE 与更新各自持有生命周期。
 */

/** capability id 常量；与 EnvironmentCapabilityCatalog 对齐。 */
const PROCESS_EXEC_ID = 'process.exec'
const SKILL_SYNC_ID = 'skill.sync'

export async function main(argv: string[]): Promise<number> {
  let args: string[]
  try {
    args = decodeBase64Args(argv)
  } catch {
    process.stderr.write('invalid daemon argument transport\n')
    return 2
  }
  const info = printInfoCommand(args)
  if (info.handled) {
    process.stdout.write(info.output)
    return 0
  }
  if (args.length > 0 && args[0] === '--check-config') {
    return runCheckConfig(args)
  }
  let config: DaemonConfig
  try {
    config = loadDaemonConfig(requireConfigPath(args))
  } catch (error) {
    process.stderr.write(`${invalidConfigurationLine(error)}\n`)
    return 1
  }
  return runDaemon(config)
}

function requireConfigPath(args: string[]): string {
  if (args.length !== 2 || args[0] !== '--config' || typeof args[1] !== 'string') {
    throw new Error('daemon: expected exactly --config <absolute-path>')
  }
  return args[1]
}

function runCheckConfig(args: string[]): number {
  if (args.length !== 2 || typeof args[1] !== 'string') {
    process.stderr.write(
      `${invalidConfigurationLine(new Error('daemon: expected exactly --check-config <absolute-path>'))}\n`,
    )
    return 1
  }
  try {
    checkConfig(args[1])
    process.stdout.write('Daemon configuration is valid\n')
    return 0
  } catch (error) {
    process.stderr.write(`${invalidConfigurationLine(error)}\n`)
    return 1
  }
}

async function runDaemon(config: DaemonConfig): Promise<number> {
  const closers: Array<() => Promise<void>> = []
  let lock: DirectoryLock | null = null
  let runtime: Runtime | null = null
  let outcome = 0
  let failure: unknown = null
  // 唯一由本 Daemon 注册的信号处理；finally 必须精确移除它，绝不动用进程上其它外部监听。
  const onDaemonSignal = (): void => runtime?.close()
  try {
    // native 工厂是最严格的启动前提：平台无法提供严格收敛时，必须在任何本地副作用（mkdir/lock）之前失败关闭。
    // 立即登记 closer，保证后续任一装配步骤失败时这个真实资源也会被释放。
    const supervisor = createProcessSupervisor()
    closers.push(() => supervisor.close())

    const dataDir = await ensurePrivateDirectory(config.dataDir)
    // 目录锁的参数是数据目录本身：native 边界会在其中创建并持有 daemon.lock。
    lock = await acquireDirectoryLock(dataDir)

    const systemProxy = createSystemProxyResolver()
    let closeNetwork = (): Promise<void> => systemProxy.close()
    closers.push(() => closeNetwork())
    const network = await installOutboundNetwork(process.env, systemProxy)
    closeNetwork = () => network.close()

    const lspGroup = createLspCapabilities(config, supervisor)
    closers.push(() => lspGroup.close())
    // Files 的 read 通过该钩子输出 lsp: header；write/edit 提交后通知 LSP 刷新文档。
    setFileCapabilityHooks({
      lspSupport: (file) => {
        const support = lspGroup.support(file)
        return { language: support.language, available: support.available }
      },
      fileChanged: (file) => lspGroup.fileChanged(file),
    })

    const ide = await createIdeService(config, supervisor)
    closers.push(() => ide.close())

    const capabilities: Record<string, CapabilityExecutor> = {
      ...fileCapabilities,
      ...searchCapabilities,
      [PROCESS_EXEC_ID]: processCapability,
      ...lspGroup.capabilities,
      [SKILL_SYNC_ID]: createSkillCapability(config),
    }
    runtime = new Runtime({
      config,
      transport: createWebSocketTransport(config.gatewayUri),
      capabilities,
      processes: supervisor,
      environment: detectEnvironmentInfo(config, join(dataDir, 'tmp')),
      ide,
      // 受管更新以 supervisor 运行受控子进程；装配层绑定 native 端口。
      update: { handleUpdate: (command, cfg, signal) => handleUpdate(command, cfg, signal, supervisor) },
      // WELCOME 下发的 TTL 热更直接驱动 Files 受控临时输出的同一份 lease 清扫。
      temporaryResources: {
        async sweep(ttlSeconds: number): Promise<void> {
          await sweepTextOutputStores(ttlSeconds)
        },
      },
    })

    process.once('SIGINT', onDaemonSignal)
    process.once('SIGTERM', onDaemonSignal)
    runtime.start()
    const finalState = await runtime.awaitTermination()
    if (finalState === 'FAILED') {
      process.stderr.write(`daemon failed: ${runtime.getFailureReason() ?? 'unknown'}\n`)
      outcome = 1
    }
  } catch (error) {
    failure = error
    process.stderr.write(`${startupFailureLine(error)}\n`)
    outcome = 1
  } finally {
    // 无论正常退出、注册失败还是释放异常，都只移除本 Daemon 自己注册的信号监听；外部监听原样保留。
    process.removeListener('SIGINT', onDaemonSignal)
    process.removeListener('SIGTERM', onDaemonSignal)
    if (runtime !== null) {
      runtime.close()
      // 停机只发布终止；必须等所有在途执行真正收敛后，才释放锁与 native 资源。
      await runtime.awaitCompletion()
    }
    const cleanupErrors = await closeAll(closers)
    // 受控临时输出缓存必须在全部能力调用结束后释放；无在途租约才真正移除，绝不提前删缓存。
    try {
      await releaseTextOutputStore(config.dataDir)
    } catch (error) {
      cleanupErrors.push(error)
    }
    if (lock !== null) {
      try {
        await lock.close()
      } catch (error) {
        cleanupErrors.push(error)
      }
    }
    if (cleanupErrors.length > 0) {
      // 释放失败绝不静默：以失败退出并携带原始错误，诊断只保留安全摘要。
      throw aggregateFailure(failure, cleanupErrors)
    }
  }
  return outcome
}

/** 逆序释放全部已装配资源，逐个捕获失败而不吞掉，交由调用方汇总。 */
async function closeAll(closers: Array<() => Promise<void>>): Promise<unknown[]> {
  const errors: unknown[] = []
  for (let index = closers.length - 1; index >= 0; index--) {
    const closer = closers[index]
    if (closer === undefined) {
      continue
    }
    try {
      await closer()
    } catch (error) {
      errors.push(error)
    }
  }
  return errors
}

/**
 * 启动失败的安全单行诊断。
 *
 * <p>平台无法提供严格收敛时使用 `daemon failed:` 前缀，避免把它误当成配置错误而诱导 operator 改配置；其它启动失败继续复用
 * 配置诊断的脱敏规则。这里只输出 native 构造的固定文案，绝不回显原始异常、路径或凭证。
 */
function startupFailureLine(error: unknown): string {
  if (error instanceof UnsupportedPlatformError || error instanceof ProxyConfigurationError) {
    return `daemon failed: ${error.message}`
  }
  return invalidConfigurationLine(error)
}

function aggregateFailure(primary: unknown, cleanupErrors: unknown[]): Error {
  const errors = primary === null ? cleanupErrors : [primary, ...cleanupErrors]
  return new AggregateError(errors, 'daemon could not release every resource')
}

const entry = process.argv[1]
if (entry !== undefined && import.meta.url === pathToFileURL(entry).href) {
  main(process.argv.slice(2)).then(
    (code) => {
      process.exitCode = code
    },
    (error: unknown) => {
      process.stderr.write(`${invalidConfigurationLine(error)}\n`)
      process.exitCode = 1
    },
  )
}
