import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { join } from 'node:path'
import type { Readable, Writable } from 'node:stream'
import type { ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../contracts.js'
import { resolveHelperPath } from './helper-path.js'
import { verifyPrivateFile } from './private-fs.js'
import * as state from './state.js'
import { jobNameFor, openJob, type WindowsJobScope } from './win32.js'

/** 等待 helper 发布范围并确认的预算。 */
const PREPARE_BUDGET_MILLIS = 20_000

/** Job 收敛的预算。 */
const CONVERGENCE_BUDGET_MILLIS = 4_000

/** 强杀 helper 之后的等待预算。 */
const FORCE_KILL_BUDGET_MILLIS = 1_000

/** 轮询步长。 */
const POLL_INTERVAL_MILLIS = 5

/** 对外错误里附加的 helper 自述长度上限：单行短记录，绝不整段外泄。 */
const MESSAGE_LIMIT = 300

/**
 * Windows 执行范围的父进程侧：先确认 helper 建立的范围，再登记父进程自己的 Job 句柄，然后放行用户命令。
 *
 * <p>登记父进程的 Job 句柄是 Windows 特有的一步：只有拿到这个句柄，父进程才能在 helper 之外终止整组并查询整组是否已经结束。
 * 收敛的唯一证据是 Job 报告 `ActiveProcesses == 0`；「首个进程退出」不过是兜底，绝不作为收敛判定。
 */
export class WindowsProcessSupervisor implements ProcessSupervisor {
  private readonly processes = new Set<WindowsManagedProcess>()
  private closed = false

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    if (this.closed) {
      throw new Error('the process supervisor is closed')
    }
    if (spec.signal?.aborted === true) {
      throw new Error('the process scope was cancelled before the user command was started')
    }
    const stateDir = await state.createStateDir()
    let helper: ChildProcessWithoutNullStreams
    try {
      state.writeLaunch(stateDir, { cwd: spec.cwd, args: [spec.executable, ...spec.args] })
      // 写入后读回：确认启动规格文件确实落在属主独占路径上，而不是继承来的宽松权限。
      await verifyPrivateFile(join(stateDir, state.LAUNCH_FILE))
      // 第 4 个条目是专用 IPC 通道：用户命令继承 0/1/2，不会拿到它。
      helper = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), stateDir], {
        env: spec.env ?? process.env,
        stdio: ['pipe', 'pipe', 'pipe', 'ipc'],
      }) as ChildProcessWithoutNullStreams
    } catch (error) {
      state.removeStateDir(stateDir)
      throw error
    }
    const managed = new WindowsManagedProcess(stateDir, helper, spec.signal)
    this.processes.add(managed)
    void managed.settled.then(() => this.processes.delete(managed))
    try {
      await managed.establish()
    } catch (error) {
      this.processes.delete(managed)
      try {
        await managed.close()
      } catch (cleanupError) {
        // 收尾失败必须与启动失败一起上报：绝不静默降级成「已经清理好」。
        throw new Error(`${describe(error)}; additionally the failed scope could not be proven converged: ${describe(cleanupError)}`)
      }
      throw error
    }
    return managed
  }

  /** Windows 上后代由 Job 在创建期直接归属，登记即把它显式指派进同一个 Job。 */
  async trackDescendant(rootPid: number, childPid: number): Promise<void> {
    const managed = [...this.processes].find((candidate) => candidate.pid === rootPid)
    if (managed === undefined) {
      throw new Error(`no managed process is owned by pid ${rootPid}`)
    }
    managed.assignDescendant(childPid)
  }

  async close(): Promise<void> {
    this.closed = true
    const pending = [...this.processes]
    this.processes.clear()
    const results = await Promise.allSettled(pending.map((managed) => managed.close()))
    const failure = results.find((result) => result.status === 'rejected')
    if (failure !== undefined && failure.status === 'rejected') {
      throw failure.reason
    }
  }
}

/** 单次 Windows 调用的父进程状态机。 */
export class WindowsManagedProcess implements ManagedProcess {
  readonly stateDir: string
  readonly exit: Promise<ProcessExit>
  readonly settled: Promise<void>

  private readonly helper: ChildProcessWithoutNullStreams
  private readonly grantedSignal: AbortSignal | undefined
  private resolveExit!: (value: ProcessExit) => void
  private resolveSettled!: () => void
  private scopeEstablished = false
  private job: WindowsJobScope | null = null
  private termination: Promise<void> | null = null
  private exitSettled = false
  private settledFlag = false
  private helperExited = false
  private helperCloseCode: number | null = null
  private pollTimer: NodeJS.Timeout | null = null

  constructor(stateDir: string, helper: ChildProcessWithoutNullStreams, signal: AbortSignal | undefined) {
    this.stateDir = stateDir
    this.helper = helper
    this.grantedSignal = signal
    this.exit = new Promise<ProcessExit>((resolve) => {
      this.resolveExit = resolve
    })
    this.settled = new Promise<void>((resolve) => {
      this.resolveSettled = resolve
    })
    helper.on('close', (code) => {
      this.helperExited = true
      this.helperCloseCode = code
      this.settleExit()
      this.settleNaturalCompletion()
    })
    helper.on('error', () => {
      this.helperExited = true
      this.settleExit()
    })
    this.pollTimer = setInterval(() => this.refreshExit(), POLL_INTERVAL_MILLIS)
    this.pollTimer.unref()
  }

  get pid(): number {
    return this.helper.pid ?? -1
  }

  get stdin(): Writable {
    return this.helper.stdin
  }

  get stdout(): Readable {
    return this.helper.stdout
  }

  get stderr(): Readable {
    return this.helper.stderr
  }

  async establish(): Promise<void> {
    await this.awaitScope()
    this.job = await openJob(jobNameFor(this.stateDir))
    if (this.grantedSignal?.aborted === true) {
      await this.terminate()
      throw new Error('the process scope was cancelled before the user command was started')
    }
    state.publish(this.stateDir, state.PERMIT_FILE, 'permit')
  }

  assignDescendant(childPid: number): void {
    if (this.job === null) {
      throw new Error('the scope job object is not open')
    }
    this.job.assignProcess(childPid)
  }

  /**
   * 终止并收敛本次调用。
   *
   * <p>`terminate`/`close` 必须真正幂等：一旦本次调用已经被证明收敛并释放（`settledFlag`），再次调用**直接返回**。失败保留在
   * `termination` 上（不 fake-clear），后续调用仍如实抛出。
   */
  async terminate(graceMillis = CONVERGENCE_BUDGET_MILLIS): Promise<void> {
    if (this.settledFlag) {
      return
    }
    if (this.termination === null) {
      this.termination = this.runTermination(graceMillis)
    }
    try {
      await this.termination
    } catch (error) {
      // 收敛失败时保留 0700 状态目录：scope/error/cleanup 是唯一可复核的收尾证据，绝不静默丢弃。
      this.clearPoll()
      throw error
    }
    this.complete()
  }

  async close(): Promise<void> {
    await this.terminate()
  }

  /**
   * 收敛已被证明：关闭父进程持有的 Job 句柄、释放状态目录与记录。
   *
   * <p>自然退出路径（命令退出且 helper 已证明 Job 收敛）也必须走这里：否则父进程打开的 Job 句柄会一直泄漏。整个流程幂等，
   * 重复调用不会再关一次句柄或删一次目录。
   */
  private complete(): void {
    if (this.settledFlag) {
      return
    }
    this.clearPoll()
    if (this.job !== null) {
      this.job.close()
      this.job = null
    }
    state.removeStateDir(this.stateDir)
    this.settle()
  }

  private async awaitScope(): Promise<void> {
    const deadline = Date.now() + PREPARE_BUDGET_MILLIS
    while (true) {
      const published = state.read(this.stateDir, state.SCOPE_FILE)
      if (published !== null) {
        const processId = Number(published)
        if (!Number.isSafeInteger(processId) || processId <= 0) {
          throw new Error(`the process scope helper published an invalid scope id: ${published}`)
        }
        this.scopeEstablished = true
        return
      }
      if (this.helperExited) {
        throw new Error(this.startupFailure())
      }
      if (Date.now() >= deadline) {
        throw new Error(`the process scope helper did not establish the OS scope within ${PREPARE_BUDGET_MILLIS} ms`)
      }
      await delay(POLL_INTERVAL_MILLIS)
    }
  }

  /**
   * 启动失败时的说明。
   *
   * <p>只回显 helper 自己发布的失败原因（helper 侧已保证它是单行、不含启动参数与环境），并且同样做单行化与长度截断。绝不把
   * helper 的原始诊断文件内容拼进来：那里面可能有命令或环境的片段。
   */
  private startupFailure(): string {
    const reported = state.read(this.stateDir, state.ERROR_FILE)
    if (reported !== null) {
      return reported.replace(/\s+/g, ' ').slice(0, MESSAGE_LIMIT)
    }
    return `the process scope helper exited with code ${this.helperCloseCode} before establishing the OS scope`
  }

  private async runTermination(graceMillis: number): Promise<void> {
    if (!this.scopeEstablished || this.job === null) {
      if (!(await this.forceKillHelper())) {
        throw new Error('the process scope helper could not be terminated')
      }
      return
    }
    const drained = await this.job.terminateAndDrain(graceMillis)
    this.job.close()
    this.job = null
    await this.awaitHelperExit(graceMillis)
    if (!this.helperExited) {
      this.helper.kill('SIGKILL')
      await this.awaitHelperExit(graceMillis)
    }
    if (!drained) {
      throw new Error('the process scope job could not be proven converged')
    }
  }

  private async forceKillHelper(): Promise<boolean> {
    if (this.helperExited) {
      return true
    }
    this.helper.kill('SIGKILL')
    await this.awaitHelperExit(FORCE_KILL_BUDGET_MILLIS)
    return this.helperExited
  }

  private async awaitHelperExit(budgetMillis: number): Promise<boolean> {
    if (this.helperExited) {
      return true
    }
    await new Promise<void>((resolve) => {
      const timer = setTimeout(() => {
        this.helper.off('close', onClose)
        resolve()
      }, budgetMillis)
      const onClose = (): void => {
        clearTimeout(timer)
        resolve()
      }
      this.helper.once('close', onClose)
    })
    return this.helperExited
  }

  private settleExit(): void {
    if (this.exitSettled) {
      return
    }
    this.exitSettled = true
    this.clearPoll()
    const report = this.readExitReport()
    this.resolveExit(report !== null ? { exitCode: report.exitCode, signal: report.signal } : { exitCode: this.helperCloseCode, signal: null })
  }

  private refreshExit(): void {
    if (this.exitSettled) {
      this.clearPoll()
      return
    }
    const report = this.readExitReport()
    if (report !== null) {
      this.exitSettled = true
      this.clearPoll()
      this.resolveExit({ exitCode: report.exitCode, signal: report.signal })
    }
  }

  private readExitReport(): state.ExitReport | null {
    try {
      return state.readExit(this.stateDir)
    } catch {
      return null
    }
  }

  /**
   * 命令自然退出并且 helper 已经证明 Job 收敛时，本次调用其实已经结束：释放状态目录并释放记录，避免 supervisor 的 Set 无限增长。
   *
   * <p>只有 helper 明确发布 `cleanup=true` 且没有正在进行的显式收尾时才这么做：崩溃残留必须留给 `terminate()` 走完整收敛。
   */
  private settleNaturalCompletion(): void {
    if (this.settledFlag || this.termination !== null) {
      return
    }
    let cleanup: boolean | null = null
    try {
      cleanup = state.readCleanup(this.stateDir)
    } catch {
      cleanup = null
    }
    if (cleanup !== true) {
      return
    }
    this.complete()
  }

  private settle(): void {
    if (this.settledFlag) {
      return
    }
    this.settledFlag = true
    this.resolveSettled()
  }

  private clearPoll(): void {
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer)
      this.pollTimer = null
    }
  }
}

function describe(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  return message.replace(/\s+/g, ' ').slice(0, MESSAGE_LIMIT)
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}
