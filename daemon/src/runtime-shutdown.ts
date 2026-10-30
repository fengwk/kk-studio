/**
 * 停机释放的失败记录与安全诊断。
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
export function reportReleaseFailure(phase: string, error: unknown): unknown {
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
export function releaseStep(failures: unknown[], phase: string, action: () => void): void {
  try {
    action()
  } catch (error) {
    recordShutdownFailure(failures, phase, error)
  }
}

/** 记录一次停机失败，并留下安全诊断；诊断输出失败同样并入聚合且不递归。 */
export function recordShutdownFailure(failures: unknown[], phase: string, error: unknown): void {
  failures.push(error)
  const diagnosticFailure = reportReleaseFailure(phase, error)
  if (diagnosticFailure !== null) {
    failures.push(diagnosticFailure)
  }
}

/** 只留下安全诊断，不把该失败升级为停机失败。诊断输出失败仍并入可见聚合。 */
export function collectDiagnosticFailure(failures: unknown[], phase: string, error: unknown): void {
  const diagnosticFailure = reportReleaseFailure(phase, error)
  if (diagnosticFailure !== null) {
    failures.push(diagnosticFailure)
  }
}
