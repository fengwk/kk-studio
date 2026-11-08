import { create } from '@bufbuild/protobuf'
import { LONG_MAX } from './validation-constants.js'

import {
  ConfigurationErrorSchema,
  ConfigurationStatusSchema,
  RuntimeBodySchema,
  RuntimeSnapshotSchema,
  UpdateProgressSchema,
  type RuntimeSnapshot,
} from './generated/control_pb.js'

/** 更新器可上报的发行归档下载字节进度；总长未知时 totalBytes 为 null。 */
export interface UpdateByteProgress {
  downloadedBytes: number
  totalBytes: number | null
}

export interface RuntimeStatusUpdate {
  targetVersion: string
  downloadedBytes: number
  totalBytes: number | null
}

export interface RuntimeConfigurationError {
  version: string
  code: string
  field?: string
}

export interface RuntimeConfigurationStatus {
  /** 当前进程已应用版本；尚无可用配置时为 null。 */
  appliedVersion: string | null
  /** 已应用代理；null 表示显式直连。appliedVersion 为 null 时无意义。 */
  proxyUrl: string | null
  error: RuntimeConfigurationError | null
}

export interface RuntimeStatus {
  revision: string
  status: {
    daemonVersion: string
    update: RuntimeStatusUpdate | null
    configuration: RuntimeConfigurationStatus
  }
}

export interface AppliedRuntimeConfiguration {
  version: string
  proxyUrl: string | null
}

export interface RuntimeConfigurationFailure {
  version: string
  code: string
  field?: string
}

/**
 * 当前进程唯一的完整运行状态快照。
 *
 * <p>revision 只在状态内容变化时递增；心跳与重连重发同一内容沿用同一 revision。更新字节进度与配置应用状态共用
 * 这一份 tracker，任何变化都推进 revision，但由调用方决定是否立即发送 RUNTIME_STATUS。
 */
export class RuntimeStatusTracker {
  private revision = 1n
  private update: RuntimeStatusUpdate | null = null
  private configuration: AppliedRuntimeConfiguration | null = null
  private configurationError: RuntimeConfigurationFailure | null = null

  constructor(private readonly daemonVersion: string) {}

  snapshot(): RuntimeSnapshot {
    return create(RuntimeSnapshotSchema, {
      revision: this.revision,
      status: create(RuntimeBodySchema, {
        daemonVersion: this.daemonVersion,
        ...(this.update === null
          ? {}
          : {
              update: create(UpdateProgressSchema, {
                targetVersion: this.update.targetVersion,
                downloadedBytes: BigInt(this.update.downloadedBytes),
                totalBytes: this.update.totalBytes === null ? undefined : BigInt(this.update.totalBytes),
              }),
            }),
        configuration: create(ConfigurationStatusSchema, {
          ...(this.configuration === null
            ? {}
            : {
                appliedVersion: BigInt(this.configuration.version),
                ...(this.configuration.proxyUrl === null
                  ? {}
                  : { proxyUrl: this.configuration.proxyUrl }),
              }),
          ...(this.configurationError === null
            ? {}
            : {
                error: create(ConfigurationErrorSchema, {
                  version: BigInt(this.configurationError.version),
                  code: this.configurationError.code,
                  field: this.configurationError.field,
                }),
              }),
        }),
      }),
    })
  }

  plainSnapshot(): RuntimeStatus {
    return {
      revision: this.revision.toString(),
      status: {
        daemonVersion: this.daemonVersion,
        update: this.update === null ? null : { ...this.update },
        configuration: {
          appliedVersion: this.configuration?.version ?? null,
          proxyUrl: this.configuration?.proxyUrl ?? null,
          error: this.configurationError === null ? null : { ...this.configurationError },
        },
      },
    }
  }

  getUpdate(): RuntimeStatusUpdate | null {
    return this.update === null ? null : { ...this.update }
  }

  getConfiguration(): RuntimeConfigurationStatus {
    return {
      appliedVersion: this.configuration?.version ?? null,
      proxyUrl: this.configuration?.proxyUrl ?? null,
      error: this.configurationError === null ? null : { ...this.configurationError },
    }
  }

  hasAppliedConfiguration(): boolean {
    return this.configuration !== null
  }

  beginUpdate(targetVersion: string): void {
    if (this.update?.targetVersion === targetVersion) {
      return
    }
    this.update = { targetVersion, downloadedBytes: 0, totalBytes: null }
    this.advance()
  }

  reportProgress(progress: UpdateByteProgress): boolean {
    const current = this.update
    if (current === null) {
      throw new RangeError('update progress cannot be reported without an active update')
    }
    if (!Number.isSafeInteger(progress.downloadedBytes) || progress.downloadedBytes < 0) {
      throw new RangeError('update downloadedBytes must be a non-negative safe integer')
    }
    if (
      progress.totalBytes !== null &&
      (!Number.isSafeInteger(progress.totalBytes) || progress.totalBytes < progress.downloadedBytes)
    ) {
      throw new RangeError('update totalBytes must be null or a safe integer not below downloadedBytes')
    }
    if (progress.downloadedBytes < current.downloadedBytes) {
      // 推送式回调可能乱序；旧字节数不得回退 revision，也不得让更新器因延迟事件失败。
      return false
    }
    if (
      progress.downloadedBytes === current.downloadedBytes &&
      progress.totalBytes === current.totalBytes
    ) {
      return false
    }
    this.update = {
      targetVersion: current.targetVersion,
      downloadedBytes: progress.downloadedBytes,
      totalBytes: progress.totalBytes,
    }
    this.advance()
    return true
  }

  clearUpdate(): boolean {
    if (this.update === null) {
      return false
    }
    this.update = null
    this.advance()
    return true
  }

  /** 发布一个已应用的完整配置；成功应用会清除旧错误。 */
  applyConfiguration(configuration: AppliedRuntimeConfiguration): boolean {
    const sameConfiguration =
      this.configuration?.version === configuration.version &&
      this.configuration?.proxyUrl === configuration.proxyUrl
    if (sameConfiguration && this.configurationError === null) {
      return false
    }
    this.configuration = { ...configuration }
    this.configurationError = null
    this.advance()
    return true
  }

  /** 记录候选应用失败；已有可用配置保持发布，不被错误状态覆盖。 */
  failConfiguration(failure: RuntimeConfigurationFailure): boolean {
    const current = this.configurationError
    if (
      current?.version === failure.version &&
      current.code === failure.code &&
      current.field === failure.field
    ) {
      return false
    }
    this.configurationError = { ...failure }
    this.advance()
    return true
  }

  private advance(): void {
    const revision = this.revision + 1n
    if (revision > LONG_MAX) {
      throw new RangeError('runtime status revision exceeds the Java long range')
    }
    this.revision = revision
  }
}
