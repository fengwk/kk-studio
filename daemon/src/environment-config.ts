import type { DaemonConfig, DaemonConfiguration } from './contracts.js'
import {
  EnvironmentConfigurationError,
  resolveDaemonConfiguration,
  serializeConfiguration,
  serializeDaemonCache,
  type InstallationPaths,
} from './config.js'
import type { RuntimeStatusTracker } from './runtime-status.js'
import { writePrivateFile } from './native/private-fs.js'

/** 已准备但尚未发布的运行组件；publish 同步切换，retire 只用于未发布候选。 */
export interface PreparedEnvironmentConfig {
  config: DaemonConfig
  /** 同步发布；返回旧 generation 的异步收尾。 */
  publish(): Promise<void> | void
  /** 未发布候选的清理。 */
  retire(): Promise<void>
}

export interface EnvironmentConfigRuntimeOptions {
  paths: InstallationPaths
  environmentId: string
  initial: DaemonConfig
  tracker: RuntimeStatusTracker
  /** 校验候选并准备网络/LSP；失败不得发布。 */
  prepare(config: DaemonConfig): Promise<PreparedEnvironmentConfig>
  /** 发布新快照；用于暂停旧连接入站、按需主动重连。 */
  onApplied(previous: DaemonConfig, next: DaemonConfig): void
  report(error: unknown): void
  track(promise: Promise<void>): void
}

export type EnvironmentConfigApplyResult =
  | { kind: 'applied'; config: DaemonConfig }
  | { kind: 'confirmed'; config: DaemonConfig }
  | { kind: 'stale'; config: DaemonConfig }
  | { kind: 'failed'; version: bigint; code: string; field?: string }
  | { kind: 'fatal'; message: string }

interface PendingRequest {
  version: bigint
  configuration: DaemonConfiguration
  content: string
  resolve(result: EnvironmentConfigApplyResult): void
}

function configurationOf(config: DaemonConfig): DaemonConfiguration {
  return {
    studioUrl: config.studioUrl,
    bashExecutable: config.bashExecutableSetting,
    lsp: config.lsp,
    proxyUrl: config.proxyUrl,
  }
}

function contentOf(configuration: DaemonConfiguration): string {
  return JSON.stringify(serializeConfiguration(configuration))
}

function validationField(error: unknown): string | undefined {
  return error instanceof EnvironmentConfigurationError ? error.field : undefined
}

/**
 * 单卡片配置热应用。
 *
 * <p>串行处理最高待版本：只保留正在处理的条目和最高待处理条目，不为每条通知排队。版本/content 比较、候选校验与资源
 * 准备、原子缓存写入、不可变快照发布、旧资源异步 drain 都在这里完成；缓存写入成功前不改变运行快照。
 */
export class EnvironmentConfigRuntime {
  private acceptedVersion: bigint
  private acceptedContent: string
  private current: DaemonConfig
  private initialized = false
  private processing = false
  private pending: PendingRequest | null = null
  private closed = false

  constructor(private readonly options: EnvironmentConfigRuntimeOptions) {
    this.current = options.initial
    this.acceptedVersion = options.initial.configVersion
    this.acceptedContent = contentOf(configurationOf(options.initial))
  }

  get config(): DaemonConfig {
    return this.current
  }

  get appliedVersion(): bigint {
    return this.acceptedVersion
  }

  get hasAppliedConfiguration(): boolean {
    return this.initialized
  }

  /** 提交一条服务端完整快照；串行归并到当前/最高待版本。 */
  async submit(version: bigint, configuration: DaemonConfiguration): Promise<EnvironmentConfigApplyResult> {
    if (this.closed) {
      return { kind: 'fatal', message: 'configuration runtime is closed' }
    }
    const content = contentOf(configuration)
    if (version === this.acceptedVersion && content !== this.acceptedContent) {
      throw new Error('the same environment config version must not change its content')
    }
    if (version < this.acceptedVersion) {
      if (!this.initialized) {
        return {
          kind: 'fatal',
          message: 'Studio configuration version is older than the daemon cache',
        }
      }
      return { kind: 'stale', config: this.current }
    }
    if (version === this.acceptedVersion && this.initialized) {
      return { kind: 'confirmed', config: this.current }
    }
    return new Promise<EnvironmentConfigApplyResult>((resolve) => {
      this.enqueue({ version, configuration, content, resolve })
    })
  }

  /** 停机时释放尚未开始的候选。当前网络/LSP 由装配层持有的 facade 统一关闭。 */
  async close(): Promise<void> {
    this.closed = true
    this.pending?.resolve({ kind: 'fatal', message: 'configuration runtime is closed' })
    this.pending = null
  }

  private enqueue(request: PendingRequest): void {
    if (this.pending !== null) {
      if (request.version < this.pending.version) {
        request.resolve({ kind: 'stale', config: this.current })
        return
      }
      if (request.version === this.pending.version) {
        if (request.content !== this.pending.content) {
          throw new Error('the same pending environment config version must not change its content')
        }
        request.resolve({ kind: 'stale', config: this.current })
        return
      }
      // 更高版本替换尚未开始的待处理项；较低版本已确定不会被应用。
      this.pending.resolve({ kind: 'stale', config: this.current })
    }
    this.pending = request
    if (!this.processing) {
      void this.drain()
    }
  }

  private async drain(): Promise<void> {
    if (this.processing) {
      return
    }
    this.processing = true
    try {
      for (;;) {
        const request = this.pending
        this.pending = null
        if (request === undefined || request === null || this.closed) {
          return
        }
        try {
          request.resolve(await this.apply(request))
        } catch (error) {
          request.resolve({
            kind: 'fatal',
            message: error instanceof Error ? error.message : 'environment config application failed',
          })
        }
      }
    } finally {
      this.processing = false
      if (this.pending !== null && !this.closed) {
        void this.drain()
      }
    }
  }

  private async apply(request: PendingRequest): Promise<EnvironmentConfigApplyResult> {
    if (request.version === this.acceptedVersion) {
      if (request.content !== this.acceptedContent) {
        throw new Error('the same environment config version must not change its content')
      }
      if (this.initialized) {
        return { kind: 'confirmed', config: this.current }
      }
    }
    if (request.version < this.acceptedVersion) {
      return this.initialized
        ? { kind: 'stale', config: this.current }
        : { kind: 'fatal', message: 'Studio configuration version is older than the daemon cache' }
    }

    const sameContent = this.initialized && request.content === this.acceptedContent
    let candidate: DaemonConfig
    let prepared: PreparedEnvironmentConfig | null = null
    if (sameContent) {
      // 内容相同只推进确认版本；不重新解析程序，也不重建网络/LSP。
      candidate = { ...this.current, configVersion: request.version }
    } else {
      try {
        candidate = resolveDaemonConfiguration(
          this.options.paths,
          this.options.environmentId,
          request.version,
          request.configuration,
        )
        prepared = await this.options.prepare(candidate)
      } catch (error) {
        return this.failCandidate(request.version, error)
      }
      if (prepared.config.configVersion !== request.version) {
        await this.retirePrepared(prepared)
        return this.failCandidate(
          request.version,
          new EnvironmentConfigurationError(undefined, 'prepared configuration version does not match the request'),
        )
      }
      candidate = prepared.config
    }

    try {
      await writePrivateFile(this.options.paths.configPath, serializeDaemonCache(candidate))
    } catch (error) {
      await this.retirePrepared(prepared)
      this.options.tracker.failConfiguration({
        version: request.version.toString(),
        code: 'PERSIST_FAILED',
      })
      this.options.report(error)
      return { kind: 'failed', version: request.version, code: 'PERSIST_FAILED' }
    }

    // 同步发布：不 await、不逐字段修改共享对象。候选资源先完成切换，再发布不可变配置快照。
    const previous = this.current
    try {
      const cleanup = prepared?.publish()
      if (cleanup !== undefined && cleanup !== null) {
        this.options.track(
          cleanup.catch((error: unknown) => {
            this.options.report(error)
          }),
        )
      }
    } catch (error) {
      await this.retirePrepared(prepared)
      this.options.report(error)
      return { kind: 'fatal', message: 'prepared environment resources could not be published' }
    }
    this.current = candidate
    this.acceptedVersion = request.version
    this.acceptedContent = request.content
    this.initialized = true
    this.options.tracker.applyConfiguration({
      version: request.version.toString(),
      proxyUrl: candidate.proxyUrl,
    })
    this.options.onApplied(previous, candidate)
    return { kind: 'applied', config: candidate }
  }

  private failCandidate(version: bigint, error: unknown): EnvironmentConfigApplyResult {
    this.options.tracker.failConfiguration({
      version: version.toString(),
      code: 'VALIDATION_FAILED',
      field: validationField(error),
    })
    this.options.report(error)
    return {
      kind: 'failed',
      version,
      code: 'VALIDATION_FAILED',
      field: validationField(error),
    }
  }

  private async retirePrepared(prepared: PreparedEnvironmentConfig | null): Promise<void> {
    if (prepared === null) {
      return
    }
    try {
      await prepared.retire()
    } catch (error) {
      this.options.report(error)
    }
  }
}

export { configurationOf as daemonConfigurationOf }
