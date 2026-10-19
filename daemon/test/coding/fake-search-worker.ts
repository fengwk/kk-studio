import { EventEmitter } from 'node:events'

/**
 * 受控假 Worker：在生产代码位置替换 node:worker_threads，让测试完全驱动消息/退出/终止时机；无生产钩子。
 */
export class FakeWorker extends EventEmitter {
  static readonly instances: FakeWorker[] = []
  readonly posted: unknown[] = []
  terminated = false
  terminateSettled = false
  unrefCount = 0
  private terminatePromise: Promise<number> | null = null
  private settleTerminate: ((code: number) => void) | null = null

  constructor(readonly entry: unknown) {
    super()
    FakeWorker.instances.push(this)
  }

  postMessage(job: unknown): void {
    this.posted.push(job)
  }

  unref(): void {
    this.unrefCount += 1
  }

  terminate(): Promise<number> {
    this.terminated = true
    this.terminateSettled = true
    return Promise.resolve(0)
  }

  /** 结束仍挂起的 terminate()，避免悬空 Promise。 */
  release(): void {
  }
}
