import { EventEmitter } from 'node:events'

/**
 * 受控假 Worker：在生产代码位置替换 node:worker_threads，让测试完全驱动消息/退出/终止时机；无生产钩子。
 */
export class FakeWorker extends EventEmitter {
  static readonly instances: FakeWorker[] = []
  static nextPostMessageError: Error | null = null
  static nextTerminateError: Error | null = null

  readonly posted: unknown[] = []
  terminated = false
  terminateSettled = false
  unrefCount = 0
  postMessageError: Error | null = null
  terminateError: Error | null = null

  constructor(readonly entry: unknown) {
    super()
    this.postMessageError = FakeWorker.nextPostMessageError
    FakeWorker.nextPostMessageError = null
    this.terminateError = FakeWorker.nextTerminateError
    FakeWorker.nextTerminateError = null
    FakeWorker.instances.push(this)
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
    this.terminateSettled = true
    if (this.terminateError) {
      return Promise.reject(this.terminateError)
    }
    return Promise.resolve(0)
  }

  /** 结束仍挂起的 terminate()，避免悬空 Promise。 */
  release(): void {
  }
}
