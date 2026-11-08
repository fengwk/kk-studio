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
import { ProtocolError } from './protocol.js'

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
  /**
   * 校验候选并准备网络/LSP。previous 是当前已发布运行快照；首次应用尚无运行快照时为 null，
   * 装配层必须真实准备全部资源，不能按启动缓存内容跳过。
   */
  prepare(config: DaemonConfig, previous: DaemonConfig | null): Promise<PreparedEnvironmentConfig>
  /** 候选开始准备；控制地址/代理变化时在此暂停旧连接的新的入站执行。 */
  onCandidate(previous: DaemonConfig, candidate: DaemonConfig): void
  /** 候选失败或被放弃；恢复候选准备前的入站准入。 */
  onCandidateAborted(previous: DaemonConfig, candidate: DaemonConfig): void
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
  resolvers: Array<(result: EnvironmentConfigApplyResult) => void>
}

function closedResult(): EnvironmentConfigApplyResult {
  return { kind: 'fatal', message: 'configuration runtime is closed' }
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
  private active: PendingRequest | null = null
  private pending: PendingRequest | null = null
  private readonly closeWaiters: Array<() => void> = []
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
      return closedResult()
    }
    const content = contentOf(configuration)
    if (version === this.acceptedVersion && content !== this.acceptedContent) {
      throw new ProtocolError('the same environment config version must not change its content')
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

    // 同版本请求共享一次应用结果。未初始化时绝不能先用 stale 放行 READY；
    // 即使该版本已被更高待版本取代，也把等待者并入实际应用的最高版本。
    const sameVersion =
      this.active?.version === version ? this.active : this.pending?.version === version ? this.pending : null
    if (sameVersion !== null) {
      if (sameVersion.content !== content) {
        throw new ProtocolError('the same environment config version must not change its content')
      }
      return this.wait(sameVersion)
    }
    if (this.pending !== null && version < this.pending.version) {
      return this.initialized
        ? { kind: 'stale', config: this.current }
        : this.wait(this.pending)
    }
    if (this.active !== null && version < this.active.version) {
      return this.initialized
        ? { kind: 'stale', config: this.current }
        : this.wait(this.active)
    }

    const request: PendingRequest = { version, configuration, content, resolvers: [] }
    if (this.pending !== null) {
      const replaced = this.pending
      if (this.initialized) {
        this.settle(replaced, { kind: 'stale', config: this.current })
      } else {
        request.resolvers.push(...replaced.resolvers.splice(0))
      }
    }
    this.pending = request
    if (!this.processing) {
      void this.drain()
    }
    return this.wait(request)
  }

  /** 停机时拒绝待处理候选，并等正在准备/发布的候选真正收敛后才返回。 */
  async close(): Promise<void> {
    this.closed = true
    const pending = this.pending
    this.pending = null
    if (pending !== null) {
      this.settle(pending, closedResult())
    }
    if (this.processing) {
      await new Promise<void>((resolve) => this.closeWaiters.push(resolve))
    }
  }

  private wait(request: PendingRequest): Promise<EnvironmentConfigApplyResult> {
    return new Promise<EnvironmentConfigApplyResult>((resolve) => {
      request.resolvers.push(resolve)
    })
  }

  private settle(request: PendingRequest, result: EnvironmentConfigApplyResult): void {
    for (const resolve of request.resolvers.splice(0)) {
      resolve(result)
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
        if (request === null || this.closed) {
          return
        }
        this.active = request
        let result: EnvironmentConfigApplyResult
        try {
          result = await this.apply(request)
        } catch (error) {
          result = {
            kind: 'fatal',
            message: error instanceof Error ? error.message : 'environment config application failed',
          }
        }
        this.active = null
        this.settle(request, result)
      }
    } finally {
      this.processing = false
      this.active = null
      for (const resolve of this.closeWaiters.splice(0)) {
        resolve()
      }
      if (this.pending !== null && !this.closed) {
        void this.drain()
      }
    }
  }

  private async apply(request: PendingRequest): Promise<EnvironmentConfigApplyResult> {
    if (request.version === this.acceptedVersion) {
      if (request.content !== this.acceptedContent) {
        throw new ProtocolError('the same environment config version must not change its content')
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
    const previous = this.current
    // 首次应用没有已发布的运行快照，即使启动缓存内容相同也必须真实准备组件。
    const runtimePrevious = this.initialized ? previous : null
    if (sameContent) {
      // 内容相同只推进确认版本；不重新解析程序，也不重建网络/LSP。
      const candidate = { ...previous, configVersion: request.version }
      const persisted = await this.persist(request, candidate)
      if (persisted !== null) {
        return persisted
      }
      if (this.closed) {
        return closedResult()
      }
      this.publishSnapshot(request, candidate)
      return { kind: 'applied', config: candidate }
    }

    let candidate: DaemonConfig
    try {
      candidate = resolveDaemonConfiguration(
        this.options.paths,
        this.options.environmentId,
        request.version,
        request.configuration,
      )
    } catch (error) {
      return this.failCandidate(request.version, error)
    }
    // 实际控制地址/代理是否变化由会话判定；必须在候选准备前暂停旧连接的新入站执行。
    const admissionCandidate = candidate
    let prepared: PreparedEnvironmentConfig | null = null
    let published = false
    this.options.onCandidate(previous, admissionCandidate)
    try {
      try {
        prepared = await this.options.prepare(admissionCandidate, runtimePrevious)
      } catch (error) {
        return this.failCandidate(request.version, error)
      }
      if (this.closed) {
        return closedResult()
      }
      if (prepared.config.configVersion !== request.version) {
        return this.failCandidate(
          request.version,
          new EnvironmentConfigurationError(undefined, 'prepared configuration version does not match the request'),
        )
      }
      candidate = prepared.config
      const persisted = await this.persist(request, candidate)
      if (persisted !== null) {
        return persisted
      }
      if (this.closed) {
        return closedResult()
      }
      // 同步发布：不 await、不逐字段修改共享对象。候选资源先完成切换，再发布不可变配置快照。
      try {
        const cleanup = prepared.publish()
        if (cleanup !== undefined && cleanup !== null) {
          this.options.track(
            cleanup.catch((error: unknown) => {
              this.options.report(error)
            }),
          )
        }
      } catch (error) {
        this.options.report(error)
        return { kind: 'fatal', message: 'prepared environment resources could not be published' }
      }
      published = true
      this.publishSnapshot(request, candidate)
      return { kind: 'applied', config: candidate }
    } finally {
      if (!published) {
        // 候选失败/放弃时恢复准备前的准入，并回收候选资源。
        this.options.onCandidateAborted(previous, admissionCandidate)
        await this.retirePrepared(prepared)
      }
    }
  }

  /** 原子写私有缓存；失败上报 PERSIST_FAILED，close 后禁止写入。 */
  private async persist(
    request: PendingRequest,
    candidate: DaemonConfig,
  ): Promise<EnvironmentConfigApplyResult | null> {
    if (this.closed) {
      return closedResult()
    }
    try {
      await writePrivateFile(this.options.paths.configPath, serializeDaemonCache(candidate))
      return null
    } catch (error) {
      this.options.tracker.failConfiguration({
        version: request.version.toString(),
        code: 'PERSIST_FAILED',
      })
      this.options.report(error)
      return { kind: 'failed', version: request.version, code: 'PERSIST_FAILED' }
    }
  }

  /** 发布不可变快照并推进状态；资源 publish 已在此前完成。 */
  private publishSnapshot(request: PendingRequest, candidate: DaemonConfig): void {
    const previous = this.current
    this.current = candidate
    this.acceptedVersion = request.version
    this.acceptedContent = request.content
    this.initialized = true
    this.options.tracker.applyConfiguration({
      version: request.version.toString(),
      proxyUrl: candidate.proxyUrl,
    })
    this.options.onApplied(previous, candidate)
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
