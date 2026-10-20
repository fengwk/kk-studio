import { appendFileSync, existsSync, mkdirSync } from 'node:fs'
import { basename, join } from 'node:path'
import type { ManagedProcess, ProcessExit, ProcessSpec } from '../../src/contracts.js'
import type { ExitReport } from '../../src/native/state.js'

/**
 * Windows 进程诊断的 Vitest 侧透明探针。
 *
 * <p>生产代码保持原样：这里只包住测试进程真实加载的 `native/state` 模块与 `WindowsProcessSupervisor.start`，每次调用都委托给原始实现。
 * 探针只记录安全事实（label、可执行文件 basename、helper/命令 PID、状态目录 basename、退出报告、清理快照与有界错误），
 * 绝不记录 ProcessSpec 参数、环境、启动文件、token、调用栈或内存内容。
 *
 * <p>记录在状态目录被自然删除**之前**同步落盘，因此即使被测 worker 随后以原生退出码终止，也能留下最后一次观察。
 * 只有在 Windows 且设置 `KK_WINDOWS_NATIVE_DIAGNOSTIC=1` 时启用；测试可用 `configure` 显式开启以跨平台验证分类逻辑。
 */

export type DiagnosticExitSource = 'command-exit-report' | 'helper-close-fallback' | 'unknown'

export interface WindowsProcessCleanupSnapshot {
  scope: string | null
  commandPid: number | null
  exitReport: ExitReport | null
  exitReadError: { errorType: string; errorMessage: string } | null
  cleanup: boolean | null
  helperError: string | null
}

export interface WindowsProcessDiagnosticFacts {
  label: string
  executable: string | null
  helperPid: number | null
  state: string | null
  createdAt: string
  readExitCalls: number
  readExitNulls: number
  readExitErrors: number
  observedReports: ExitReport[]
  exitResult: ProcessExit | null
  exitRejected: { errorType: string; errorMessage: string } | null
  source: DiagnosticExitSource
  lastSnapshot: WindowsProcessCleanupSnapshot | null
  removals: number
  removed: boolean | null
  termination: 'succeeded' | 'failed' | null
  terminationError: { errorType: string; errorMessage: string } | null
  settled: boolean
}

export interface WindowsProcessDiagnosticOptions {
  enabled?: boolean
  outputDirectory?: string
  sink?: (record: Record<string, unknown>) => void
  label?: string
}

export interface WindowsProcessDiagnosticHealth {
  writeFailures: number
  lastFailure: { errorType: string; errorMessage: string } | null
  healthy: boolean
}

interface ScopeTrace {
  stateDirectory: string
  state: string
  createdAt: string
  label: string
  executable: string | null
  helperPid: number | null
  readExitCalls: number
  readExitNulls: number
  readExitErrors: number
  observedReports: ExitReport[]
  exitResult: ProcessExit | null
  resolvedSource: DiagnosticExitSource
  exitRejected: { errorType: string; errorMessage: string } | null
  lastSnapshot: WindowsProcessCleanupSnapshot | null
  removals: number
  removed: boolean | null
  termination: 'succeeded' | 'failed' | null
  terminationError: { errorType: string; errorMessage: string } | null
  settled: boolean
  observed: boolean
}

interface StartContext {
  label: string
  executable: string | null
}

const MESSAGE_LIMIT = 300
const ENABLE_ENV = 'KK_WINDOWS_NATIVE_DIAGNOSTIC'
const OUTPUT_ENV = 'KK_WINDOWS_NATIVE_DIAGNOSTIC_OUTPUT'
const LABEL_ENV = 'KK_WINDOWS_NATIVE_DIAGNOSTIC_LABEL'
const TERMINATE_WRAPPED = Symbol('kk-studio-windows-diagnostic-terminate')

let options: WindowsProcessDiagnosticOptions = {}
let label = defaultLabel()
let currentStart: StartContext | null = null
const traces = new Map<string, ScopeTrace>()
let outputWriteFailures = 0
let lastOutputFailure: { errorType: string; errorMessage: string } | null = null

/** 测试或 CI 显式启用/覆盖输出；默认路径只读取环境变量。 */
export function configureWindowsProcessDiagnostics(next: WindowsProcessDiagnosticOptions): void {
  options = { ...options, ...next }
  if (next.label !== undefined) {
    label = next.label
  }
}

/** 清理本测试文件的探针状态；已写出的 JSONL 记录不会丢失。 */
export function resetWindowsProcessDiagnostics(): void {
  options = {}
  label = defaultLabel()
  currentStart = null
  traces.clear()
  outputWriteFailures = 0
  lastOutputFailure = null
}

/** 诊断证据写入健康度；调用方必须在结束前显式断言，缺失证据不得伪装成绿。 */
export function windowsProcessDiagnosticHealth(): WindowsProcessDiagnosticHealth {
  return {
    writeFailures: outputWriteFailures,
    lastFailure: lastOutputFailure,
    healthy: outputWriteFailures === 0,
  }
}

/** 读取某个状态目录的已观察事实（不返回路径本身）。 */
export function windowsProcessDiagnosticFacts(stateDirectory: string): WindowsProcessDiagnosticFacts | null {
  const trace = traces.get(stateDirectory)
  if (trace === undefined) {
    return null
  }
  return {
    label: trace.label,
    executable: trace.executable,
    helperPid: trace.helperPid,
    state: trace.state,
    createdAt: trace.createdAt,
    readExitCalls: trace.readExitCalls,
    readExitNulls: trace.readExitNulls,
    readExitErrors: trace.readExitErrors,
    observedReports: [...trace.observedReports],
    exitResult: trace.exitResult,
    exitRejected: trace.exitRejected,
    source: classify(trace),
    lastSnapshot: trace.lastSnapshot,
    removals: trace.removals,
    removed: trace.removed,
    termination: trace.termination,
    terminationError: trace.terminationError,
    settled: trace.settled,
  }
}

/**
 * 透明包装真实 `native/state` 命名空间。
 *
 * <p>`createStateDir` 建立观察上下文；`readExit` 记录真实观察到的报告；`removeStateDir` 在删除前快照 scope/exit/cleanup。
 * 其它导出原样透传，因此生产辅助进程、主进程与测试看到的仍是同一实现。
 */
export function traceStateModule<T extends typeof import('../../src/native/state.js')>(actual: T): T {
  return {
    ...actual,
    async createStateDir(): Promise<string> {
      const directory = await actual.createStateDir()
      if (!isEnabled()) {
        return directory
      }
      const start = currentStart
      const trace: ScopeTrace = {
        stateDirectory: directory,
        state: basename(directory),
        createdAt: new Date().toISOString(),
        label: start?.label ?? label,
        executable: start?.executable ?? null,
        helperPid: null,
        readExitCalls: 0,
        readExitNulls: 0,
        readExitErrors: 0,
        observedReports: [],
        exitResult: null,
        resolvedSource: 'unknown',
        exitRejected: null,
        lastSnapshot: null,
        removals: 0,
        removed: null,
        termination: null,
        terminationError: null,
        settled: false,
        observed: false,
      }
      traces.set(directory, trace)
      emit('state-created', trace)
      return directory
    },
    readExit(stateDirectory: string): ExitReport | null {
      const trace = traces.get(stateDirectory)
      try {
        const report = actual.readExit(stateDirectory)
        if (trace !== undefined) {
          trace.readExitCalls += 1
          if (report === null) {
            trace.readExitNulls += 1
          } else {
            trace.observedReports.push(report)
            emit('read-exit', trace, exitFields(report))
          }
        }
        return report
      } catch (error) {
        if (trace !== undefined) {
          trace.readExitCalls += 1
          trace.readExitErrors += 1
          emit('read-exit-error', trace, { error: safeError(error) })
        }
        throw error
      }
    },
    removeStateDir(stateDirectory: string): void {
      const trace = traces.get(stateDirectory)
      if (trace !== undefined) {
        const snapshot = captureSnapshot(actual, stateDirectory)
        trace.lastSnapshot = snapshot
        trace.removals += 1
        emit('before-remove', trace, {
          cleanupFacts: snapshot,
          sourceAtRemoval: classify(trace),
        })
      }
      try {
        actual.removeStateDir(stateDirectory)
      } catch (error) {
        if (trace !== undefined) {
          emit('state-remove-error', trace, { error: safeError(error) })
        }
        throw error
      }
      if (trace !== undefined) {
        trace.removed = !existsSync(stateDirectory)
        emit('state-removed', trace, { removed: trace.removed })
      }
    },
  } as T
}

/**
 * 透明包装 `WindowsProcessSupervisor`：子类只观察 `start` 的返回值，所有行为仍由原类实现。
 */
export function traceWindowsSupervisorModule<T extends typeof import('../../src/native/process-supervisor-windows.js')>(
  actual: T,
): T {
  const Original = actual.WindowsProcessSupervisor
  class TracedWindowsProcessSupervisor extends Original {
    override async start(spec: ProcessSpec): Promise<ManagedProcess> {
      return traceStart(spec, () => super.start(spec))
    }
  }
  return { ...actual, WindowsProcessSupervisor: TracedWindowsProcessSupervisor } as T
}

async function traceStart(spec: ProcessSpec, start: () => Promise<ManagedProcess>): Promise<ManagedProcess> {
  if (!isEnabled()) {
    return start()
  }
  const previous = currentStart
  const startContext: StartContext = {
    label,
    executable: safeBaseName(spec.executable),
  }
  currentStart = startContext
  try {
    const managed = await start()
    observeManagedProcess(managed, spec)
    return managed
  } catch (error) {
    emit('start-error', null, { executable: startContext.executable, error: safeError(error) })
    throw error
  } finally {
    currentStart = previous
  }
}

/**
 * 观察单个真实 WindowsManagedProcess。
 *
 * <p>`stateDir` 与 `settled` 是 Windows 实现的公开字段；这里只读它们，不访问 helper/job 私有字段，也不替代任何生产清理。
 */
export function observeManagedProcess(managed: ManagedProcess, spec?: ProcessSpec): void {
  if (!isEnabled()) {
    return
  }
  const candidate = managed as ManagedProcess & { stateDir?: unknown; settled?: Promise<void> }
  if (typeof candidate.stateDir !== 'string') {
    return
  }
  const trace = traces.get(candidate.stateDir)
  if (trace === undefined || trace.observed) {
    return
  }
  trace.observed = true
  trace.helperPid = managed.pid
  if (trace.executable === null && spec !== undefined) {
    trace.executable = safeBaseName(spec.executable)
  }

  managed.exit.then(
    (result) => {
      trace.exitResult = result
      // 来源只在观察到 exit 落定时确定；之后即使出现同码的迟到报告，也不能改写回退事实。
      trace.resolvedSource = trace.observedReports.some((report) => sameExit(report, result))
        ? 'command-exit-report'
        : trace.readExitCalls > 0
          ? 'helper-close-fallback'
          : 'unknown'
      emit('exit-resolved', trace, exitFields(result))
    },
    (error: unknown) => {
      trace.exitRejected = safeError(error)
      emit('exit-rejected', trace, { error: trace.exitRejected })
    },
  )

  const originalTerminate = managed.terminate.bind(managed)
  const wrapped = managed as ManagedProcess & { [TERMINATE_WRAPPED]?: true }
  if (wrapped[TERMINATE_WRAPPED] !== true) {
    wrapped[TERMINATE_WRAPPED] = true
    managed.terminate = async (graceMillis?: number): Promise<void> => {
      try {
        await originalTerminate(graceMillis)
        trace.termination = 'succeeded'
        emit('terminate-succeeded', trace)
      } catch (error) {
        trace.termination = 'failed'
        trace.terminationError = safeError(error)
        emit('terminate-failed', trace, { error: trace.terminationError })
        throw error
      }
    }
  }

  if (candidate.settled !== undefined) {
    candidate.settled.then(
      () => {
        trace.settled = true
        emit('scope-settled', trace, { source: classify(trace) })
      },
      (error: unknown) => {
        emit('scope-settle-rejected', trace, { error: safeError(error) })
      },
    )
  }
}

function captureSnapshot(
  actual: typeof import('../../src/native/state.js'),
  stateDirectory: string,
): WindowsProcessCleanupSnapshot {
  const scope = actual.read(stateDirectory, actual.SCOPE_FILE)
  let exitReport: ExitReport | null = null
  let exitReadError: { errorType: string; errorMessage: string } | null = null
  try {
    exitReport = actual.readExit(stateDirectory)
  } catch (error) {
    exitReadError = safeError(error)
  }
  let cleanup: boolean | null = null
  try {
    cleanup = actual.readCleanup(stateDirectory)
  } catch {
    cleanup = null
  }
  const helperErrorValue = actual.read(stateDirectory, actual.ERROR_FILE)
  const commandPid = scope === null ? null : Number(scope)
  return {
    scope: scope === null ? null : scope.slice(0, 40),
    commandPid: Number.isSafeInteger(commandPid) && Number(commandPid) > 0 ? Number(commandPid) : null,
    exitReport,
    exitReadError,
    cleanup,
    helperError: helperErrorValue === null ? null : helperErrorValue.replace(/\s+/g, ' ').slice(0, MESSAGE_LIMIT),
  }
}

function classify(trace: ScopeTrace): DiagnosticExitSource {
  return trace.resolvedSource
}

function sameExit(report: ExitReport, result: ProcessExit): boolean {
  return report.exitCode === result.exitCode && report.signal === result.signal
}

function exitFields(result: ProcessExit): Record<string, unknown> {
  return {
    exitCode: result.exitCode,
    exitCodeHex: result.exitCode === null ? null : hexStatus(result.exitCode),
    signal: result.signal,
    source: result.exitCode === null ? 'unknown' : 'command-exit-report',
  }
}

function emit(kind: string, trace: ScopeTrace | null, fields: Record<string, unknown> = {}): void {
  if (!isEnabled()) {
    return
  }
  const exitCode = typeof fields.exitCode === 'number' ? fields.exitCode : null
  const record: Record<string, unknown> = {
    kind,
    timestamp: new Date().toISOString(),
    session: `pid-${process.pid}`,
    label: trace?.label ?? label,
    executable: trace?.executable ?? null,
    helperPid: trace?.helperPid ?? null,
    state: trace?.state ?? null,
    ...(exitCode === null || 'exitCodeHex' in fields ? {} : { exitCodeHex: hexStatus(exitCode) }),
    ...fields,
    ...(trace === null ? {} : { source: classify(trace) }),
  }
  if (options.sink !== undefined) {
    try {
      options.sink(record)
    } catch (error) {
      recordWriteFailure(error)
    }
  }
  const directory = options.outputDirectory ?? process.env[OUTPUT_ENV]
  if (directory === undefined || directory.length === 0) {
    return
  }
  try {
    mkdirSync(directory, { recursive: true })
    appendFileSync(join(directory, `windows-process-main-${process.pid}.jsonl`), `${JSON.stringify(record)}\n`)
  } catch (error) {
    recordWriteFailure(error)
  }
}

function recordWriteFailure(error: unknown): void {
  // 探针绝不把输出错误抛进被测调用；调用方通过健康度在诊断结束处显式失败。
  outputWriteFailures += 1
  lastOutputFailure = safeError(error)
}

function isEnabled(): boolean {
  return options.enabled ?? (process.platform === 'win32' && process.env[ENABLE_ENV] === '1')
}

function defaultLabel(): string {
  return process.env[LABEL_ENV] ?? 'main'
}

function safeBaseName(value: string): string {
  const parts = value.split(/[\\/]/)
  return parts[parts.length - 1] ?? value
}

function hexStatus(code: number): string {
  return `0x${(code >>> 0).toString(16)}`
}

/** 有界错误诊断：不加栈、不读取 cause，绝不把命令参数或环境带进记录。 */
export function safeError(error: unknown): { errorType: string; errorMessage: string } {
  const message = error instanceof Error ? error.message : String(error)
  return {
    errorType: error instanceof Error ? error.name : typeof error,
    errorMessage: message.replace(/\s+/g, ' ').slice(0, MESSAGE_LIMIT),
  }
}
