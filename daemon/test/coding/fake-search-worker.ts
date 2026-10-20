import { EventEmitter } from 'node:events'

/**
 * 受控假 Worker：在生产代码位置替换 node:worker_threads，让测试完全驱动消息/退出/终止时机；无生产钩子。
 */
export class FakeWorker extends EventEmitter {
  static readonly instances: FakeWorker[] = []
  static nextPostMessageError: Error | null = null
  static nextTerminateError: Error | null = null
  static nextAutoResolve: boolean | null = null
  static onCreated: ((worker: FakeWorker) => void) | null = null

  readonly posted: unknown[] = []
  terminated = false
  terminateSettled = false
  unrefCount = 0
  postMessageError: Error | null = null
  terminateError: Error | null = null
  autoResolve: boolean
  private readonly terminateGate = Promise.withResolvers<number>()

  constructor(readonly entry: unknown) {
    super()
    this.postMessageError = FakeWorker.nextPostMessageError
    FakeWorker.nextPostMessageError = null
    this.terminateError = FakeWorker.nextTerminateError
    FakeWorker.nextTerminateError = null
    this.autoResolve = FakeWorker.nextAutoResolve ?? true
    FakeWorker.nextAutoResolve = null
    // 挂兜底处理避免悬空未订阅时的全局 unhandled rejection
    this.terminateGate.promise.catch(() => {})
    FakeWorker.instances.push(this)
    FakeWorker.onCreated?.(this)
  }

  postMessage(job: unknown): void {
    if (this.postMessageError) {
      throw this.postMessageError
    }
    this.posted.push(job)
  }

  unref(): void {
    this.unrefCount += 1
  }

  terminate(): Promise<number> {
    this.terminated = true
    if (this.autoResolve) {
      this.terminateSettled = true
      if (this.terminateError) {
        return Promise.reject(this.terminateError)
      }
      return Promise.resolve(0)
    }
    return this.terminateGate.promise
  }

  /** 结束仍挂起的 terminate()，避免悬空 Promise。未 terminate 时只 resolve 或 no-op，不 reject。 */
  release(): void {
    if (!this.terminateSettled) {
      this.terminateSettled = true
      if (this.terminated && this.terminateError) {
        this.terminateGate.reject(this.terminateError)
      } else {
        this.terminateGate.resolve(0)
      }
    }
  }
}
