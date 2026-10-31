import { create } from '@bufbuild/protobuf'

import {
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

const LONG_MAX = 9223372036854775807n

/**
 * 当前进程的完整运行状态快照。
 *
 * <p>revision 只在状态内容变化时递增；心跳与重连重发同一内容沿用同一 revision。更新字节进度是快照的一部分，
 * 因此进度变化也会推进 revision，但由调用方决定是否立即发送 RUNTIME_STATUS。
 */
export class RuntimeStatusTracker {
  private revision = 1n
  private update: RuntimeStatusUpdate | null = null

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
      }),
    })
  }

  getUpdate(): RuntimeStatusUpdate | null {
    return this.update === null ? null : { ...this.update }
  }

  beginUpdate(targetVersion: string): void {
    if (this.update?.targetVersion === targetVersion) {
      return
    }
    this.setUpdate({ targetVersion, downloadedBytes: 0, totalBytes: null })
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
    return this.setUpdate({
      targetVersion: current.targetVersion,
      downloadedBytes: progress.downloadedBytes,
      totalBytes: progress.totalBytes,
    })
  }

  clearUpdate(): boolean {
    return this.setUpdate(null)
  }

  private setUpdate(next: RuntimeStatusUpdate | null): boolean {
    if (sameUpdate(this.update, next)) {
      return false
    }
    const revision = this.revision + 1n
    if (revision > LONG_MAX) {
      throw new RangeError('runtime status revision exceeds the Java long range')
    }
    this.update = next
    this.revision = revision
    return true
  }
}

function sameUpdate(left: RuntimeStatusUpdate | null, right: RuntimeStatusUpdate | null): boolean {
  if (left === null || right === null) {
    return left === right
  }
  return (
    left.targetVersion === right.targetVersion &&
    left.downloadedBytes === right.downloadedBytes &&
    left.totalBytes === right.totalBytes
  )
}
