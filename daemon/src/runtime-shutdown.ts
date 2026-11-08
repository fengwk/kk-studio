/**
 * 运行期释放与清扫的安全诊断，以及停机释放的失败记录。
 *
 * <p>诊断只输出固定阶段和固定异常类型，绝不输出 message、路径或可写的 error.name。
 * 诊断输出自身失败返回给调用方并入聚合，绝不再写第二次诊断。
 */

/** 只按内建身份判定异常类型的固定枚举：诊断里输出的类型是这里写死的字面量，任何可写文本都无法注入。 */
const BUILT_IN_FAILURE_TYPES: readonly (readonly [Function, string])[] = [
  [AggregateError, 'AggregateError'],
  [TypeError, 'TypeError'],
  [RangeError, 'RangeError'],
  [SyntaxError, 'SyntaxError'],
  [ReferenceError, 'ReferenceError'],
  [URIError, 'URIError'],
  [EvalError, 'EvalError'],
]

/**
 * 释放失败的安全诊断。
 *
 * @returns 诊断输出自身的失败；null 表示诊断已写出。
 */
function reportReleaseFailure(phase: string, error: unknown): unknown {
  try {
    process.stderr.write(`daemon ${phase} failed: ${describeFailureType(error)}\n`)
    return null
  } catch (diagnosticFailure) {
    return diagnosticFailure
  }
}

/** 这次失败的固定类型名：只认内建类型身份与「是不是 Error」，其余一律泛化为 UnknownError。 */
export function describeFailureType(error: unknown): string {
  for (const [type, label] of BUILT_IN_FAILURE_TYPES) {
    if (error instanceof type) {
      return label
    }
  }
  return error instanceof Error ? 'Error' : 'UnknownError'
}

/** 独立执行一个停机步骤：失败记入聚合后继续，绝不把失败当成成功。 */
function releaseStep(failures: unknown[], phase: string, action: () => void | Promise<void>): void {
  try {
    const result = action()
    if (result instanceof Promise) {
      result.catch((error) => {
        recordShutdownFailure(failures, phase, error)
      })
    }
  } catch (error) {
    recordShutdownFailure(failures, phase, error)
  }
}

/** 记录一次停机失败，并留下安全诊断；诊断输出失败同样并入聚合且不递归。 */
function recordShutdownFailure(failures: unknown[], phase: string, error: unknown): void {
  failures.push(error)
  const diagnosticFailure = reportReleaseFailure(phase, error)
  if (diagnosticFailure !== null) {
    failures.push(diagnosticFailure)
  }
}

/** 只留下安全诊断，不把该失败升级为停机失败。诊断输出失败仍并入可见聚合。 */
function collectDiagnosticFailure(failures: unknown[], phase: string, error: unknown): void {
  const diagnosticFailure = reportReleaseFailure(phase, error)
  if (diagnosticFailure !== null) {
    failures.push(diagnosticFailure)
  }
}

/**
 * 停机收敛：跟踪所有已登记的在途执行，逆序释放中的独立失败只记录不阻断，最后等全部在途执行真正结束才发布终止。
 *
 * <p>普通执行与受管更新都计入 in-flight；受管更新准入需额外等待「受保护执行」（普通/恢复执行及其上传收尾）全部结束。
 * 终态发布由装配层在 finalize 回调里完成，本类不解析连接状态。
 */
export class ShutdownRuntime {
  private readonly inFlight = new Set<Promise<void>>()
  private readonly protectedExecutions = new Set<Promise<void>>()
  private readonly shutdownFailures: unknown[] = []

  get failures(): readonly unknown[] {
    return this.shutdownFailures
  }

  /** 是否仍有受保护执行及其拥有的上传收尾未结束。 */
  get hasProtectedExecutions(): boolean {
    return this.protectedExecutions.size > 0
  }

  track(promise: Promise<void>): Promise<void> {
    return this.trackIn(null, promise)
  }

  trackProtected(promise: Promise<void>): Promise<void> {
    return this.trackIn(this.protectedExecutions, promise)
  }

  release(phase: string, action: () => void): void {
    releaseStep(this.shutdownFailures, phase, action)
  }

  /**
   * 异步释放：立即纳入 in-flight 收敛，finalize 会等待它真正结束；失败按固定阶段记录一次。
   *
   * <p>不能只把 Promise 交给 {@link release} 后忽略返回值，否则终止可能在候选清理完成前发布。
   */
  releaseTracked(phase: string, action: () => Promise<void>): void {
    let release: Promise<void>
    try {
      release = action()
    } catch (error) {
      this.record(phase, error)
      return
    }
    this.track(
      release.catch((error: unknown) => {
        this.record(phase, error)
      }),
    )
  }

  record(phase: string, error: unknown): void {
    recordShutdownFailure(this.shutdownFailures, phase, error)
  }

  /** 只留下诊断，不升级为停机失败。 */
  collect(phase: string, error: unknown): void {
    collectDiagnosticFailure(this.shutdownFailures, phase, error)
  }

  /** 释放传输并等待全部在途执行收敛，随后由装配层发布终止；释放失败全部进入聚合。 */
  async finalize(closeTransport: () => Promise<void>, resolveTermination: () => void): Promise<void> {
    try {
      await closeTransport()
    } catch (error) {
      this.record('transport close', error)
    }
    const settled = await Promise.allSettled([...this.inFlight])
    for (const outcome of settled) {
      if (outcome.status === 'rejected') {
        this.record('in-flight execution did not converge', outcome.reason)
      }
    }
    resolveTermination()
  }

  private trackIn(group: Set<Promise<void>> | null, promise: Promise<void>): Promise<void> {
    this.inFlight.add(promise)
    group?.add(promise)
    const remove = (): void => {
      this.inFlight.delete(promise)
      group?.delete(promise)
    }
    promise.then(remove, remove)
    return promise
  }
}
