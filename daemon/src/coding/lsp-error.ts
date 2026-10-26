export class LspError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'LspError'
  }
}

/**
 * LSP 启动引导失败，以及失败后受管范围是否已被证明收敛。
 *
 * <p>{@code converged} 为 true 表示这次启动没有遗留进程，可作为本次调用尚未发出目标请求时的正向退出证据；
 * false 表示清理没有完成或没有被证明，调用方不得确认退出。原始失败始终保留在 {@code cause} 中。
 */
export class LspBootstrapError extends LspError {
  readonly converged: boolean

  constructor(message: string, options: { cause?: unknown; converged: boolean }) {
    super(message, options)
    this.name = 'LspBootstrapError'
    this.converged = options.converged
  }
}

/** 池关闭时仍有未收敛的清理；保留每一条原始失败，绝不只报告计数。 */
export class LspCleanupError extends LspError {
  readonly failures: readonly Error[]

  constructor(failures: readonly Error[]) {
    super(`LSP client pool failed to release all resources (${failures.length} cleanup failure(s))`, {
      cause: new AggregateError(failures, 'LSP client pool cleanup failures'),
    })
    this.name = 'LspCleanupError'
    this.failures = failures
  }
}

export class LspTimeoutError extends LspError {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options)
    this.name = 'LspTimeoutError'
  }
}
