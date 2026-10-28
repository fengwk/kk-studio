import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { join } from 'node:path'
import type { Readable, Writable } from 'node:stream'
import type { ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../contracts.js'
import { ProcessScopeNotConvergedError } from '../contracts.js'
import { resolveHelperPath } from './helper-path.js'
import { verifyPrivateFile } from './private-fs.js'
import * as posix from './posix-session.js'
import * as state from './state.js'

/** 等待 helper 发布范围并确认的预算：覆盖 Node 冷启动。 */
const PREPARE_BUDGET_MILLIS = 20_000

/** helper 退出后再确认会话收敛的预算。 */
const CONVERGENCE_BUDGET_MILLIS = 4_000

/** 温和信号之后的默认宽限窗口。 */
const DEFAULT_GRACE_MILLIS = 1_000

/** 强杀 helper 之后的等待预算。 */
const FORCE_KILL_BUDGET_MILLIS = 1_000

/** 轮询步长。 */
const POLL_INTERVAL_MILLIS = 5

/** 对外错误里附加的 helper 自述长度上限：单行短记录，绝不整段外泄。 */
const MESSAGE_LIMIT = 300

/**
 * POSIX 执行范围的父进程侧：先确认 helper 建立的范围并登记收敛手段，再放行用户命令。
 *
 * <p>顺序是唯一的安全性来源：范围确认 -> 登记父进程自己的收敛手段（会话 id 已经就是 helper 的 pid，因此直接可用）-> 放行命令。
 * 所以「许可之前失败」永远等价于「用户命令没有产生任何副作用」；许可之后则一律走完整收敛。
 *
 * <p>helper 与父进程之间另有一条**专用 IPC 通道**（不在用户命令的 stdin/stdout/stderr 里）：父进程被强杀时该通道由内核关闭，
 * helper 收到 `disconnect` 后尝试收敛会话再退出；这不是阻止命令主动逃逸的安全沙箱。
 *
 * <p>在 Linux 上父进程同时把自己变成子进程收养者（`PR_SET_CHILD_SUBREAPER`，失败即失败关闭）：helper 崩溃时，它名下已经
 * `setsid` 的后代会被内核重新挂到父进程。父进程只收敛**本次调用新增**的直接子进程（对照 `start()` 时的基线）并排除其它活动
 * helper，绝不因为「是父进程的孩子」就整片收敛陌生会话。macOS 没有收养机制，`becomeChildSubreaper()` 返回 `false`，收尾
 * 不做这层扫描；未登记且故意脱离的后代不在本次会话生命周期内，限制由 `processScopeCapabilities()` 如实暴露。
 */
export class PosixProcessSupervisor implements ProcessSupervisor {
  private readonly processes = new Set<PosixManagedProcess>()
  private readonly adoptsOrphans = posix.becomeChildSubreaper()
  private closed = false

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    if (this.closed) {
      throw new Error('the process supervisor is closed')
    }
    if (spec.signal?.aborted === true) {
      throw new Error('the process scope was cancelled before the user command was started')
    }
    // 基线必须在 spawn helper 之前采样：之后新增的直接子进程才可能是本次调用的收养孤儿。采样不可判定（null）时，
    // 父进程侧收养收尾直接放弃（见 runTermination），绝不把「读不到」当成「全是新孤儿」而误杀。
    const baselineScan = posix.directChildren(process.pid, [])
    const baseline = baselineScan === null ? null : baselineScan.map((child) => child.pid)
    const stateDir = await state.createStateDir()
    let helper: ChildProcessWithoutNullStreams
    try {
      // 启动规格（cwd 与 argv）只经 0700/0600 状态文件交接，绝不进入 helper 的命令行。
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
    const managed = new PosixManagedProcess(stateDir, helper, spec.signal, baseline, () => this.helperPids(), this.adoptsOrphans)
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
        throw new ProcessScopeNotConvergedError(
          `${describe(error)}; additionally the failed scope could not be proven converged: ${describe(cleanupError)}`,
        )
      }
      throw error
    }
    return managed
  }

  /**
   * 登记 IDE PTY 等在 helper 会话之外、故意建立自己 POSIX session 的后代。
   *
   * <p>登记前必须先核实它确实是本范围的后代，并冻结其启动身份：只有「真后代 + 身份未变」才允许把它的会话纳入收敛集合，因此
   * 复用了同一个 sid 的陌生进程不会被纳入本次收尾。Linux 的子进程收养可补充未登记窗口；macOS 没有这层能力。
   */
  async trackDescendant(rootPid: number, childPid: number): Promise<void> {
    const managed = [...this.processes].find((candidate) => candidate.pid === rootPid)
    if (managed === undefined) {
      throw new Error(`no managed process is owned by pid ${rootPid}`)
    }
    managed.trackSession(childPid)
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

  /** 当前仍由本 supervisor 管理的 helper pid：收养收尾时必须排除它们，绝不误收敛别的活动范围。 */
  private helperPids(): number[] {
    return [...this.processes].map((managed) => managed.pid)
  }
}

/** 已经登记、身份已冻结的「故意脱会话」后代。 */
interface TrackedSession {
  childPid: number
  session: number
  identity: string
}

/** 单次 POSIX 调用的父进程状态机；不含任何静态可变状态。 */
export class PosixManagedProcess implements ManagedProcess {
  /** 调用私有的状态目录；成功收尾之后必须不存在，收敛失败时保留为收尾证据。 */
  readonly stateDir: string

  /** 命令自然退出或 helper 结束的事实。 */
  readonly exit: Promise<ProcessExit>

  /** 本次调用的本地资源所有权已结束；失败由 terminate/close 报告，supervisor 据此释放记录。 */
  readonly settled: Promise<void>

  private readonly helper: ChildProcessWithoutNullStreams
  private readonly grantedSignal: AbortSignal | undefined
  private readonly baselinePids: number[] | null
  private readonly excludedHelpers: () => number[]
  private readonly adoptsOrphans: boolean
  private readonly extraSessions = new Map<number, TrackedSession>()
  private resolveExit!: (value: ProcessExit) => void
  private resolveSettled!: () => void
  private sessionId = -1
  private scopeEstablished = false
  private termination: Promise<void> | null = null
  private exitSettled = false
  private settledFlag = false
  private helperExited = false
  private helperCloseCode: number | null = null
  private helperCloseSignal: string | null = null
  private pollTimer: NodeJS.Timeout | null = null

  constructor(
    stateDir: string,
    helper: ChildProcessWithoutNullStreams,
    signal: AbortSignal | undefined,
    baselinePids: number[] | null,
    excludedHelpers: () => number[],
    adoptsOrphans: boolean,
  ) {
    this.stateDir = stateDir
    this.helper = helper
    this.grantedSignal = signal
    this.baselinePids = baselinePids
    this.excludedHelpers = excludedHelpers
    this.adoptsOrphans = adoptsOrphans
    this.exit = new Promise<ProcessExit>((resolve) => {
      this.resolveExit = resolve
    })
    this.settled = new Promise<void>((resolve) => {
      this.resolveSettled = resolve
    })
    helper.on('close', (code, signalName) => {
      this.helperExited = true
      this.helperCloseCode = code
      this.helperCloseSignal = signalName
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

  /** 确认范围、登记父进程收敛手段，然后放行用户命令。 */
  async establish(): Promise<void> {
    await this.awaitScope()
    if (this.grantedSignal?.aborted === true) {
      await this.terminate()
      throw new Error('the process scope was cancelled before the user command was started')
    }
    state.publish(this.stateDir, state.PERMIT_FILE, String(this.sessionId))
  }

  /** 登记一个故意建立自己 session 的后代；核实血缘并冻结启动身份之后才纳入收敛集合。 */
  trackSession(childPid: number): void {
    const ancestorPid = this.pid
    if (posix.isDescendantOf(childPid, ancestorPid) !== true) {
      throw new Error(`pid ${childPid} is not a descendant of the managed scope ${ancestorPid}`)
    }
    const session = posix.sessionOf(childPid)
    if (session <= 0) {
      throw new Error(`cannot resolve the POSIX session of descendant ${childPid}`)
    }
    if (this.scopeEstablished && session === this.sessionId) {
      return
    }
    const identity = posix.processIdentity(childPid)
    if (identity === null) {
      throw new Error(`cannot freeze the launch identity of descendant ${childPid}`)
    }
    this.extraSessions.set(session, { childPid, session, identity })
  }

  /**
   * 终止并收敛本次调用。
   *
   * <p>`terminate`/`close` 必须真正幂等：一旦本次调用已经被证明收敛并释放（`settledFlag`），再次调用**直接返回**，绝不对已经
   * 消失的会话/复用同一 sid 的陌生进程再发信号。失败保留在 `termination` 上（不 fake-clear），后续调用仍如实抛出。
   */
  async terminate(graceMillis = DEFAULT_GRACE_MILLIS): Promise<void> {
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
      this.releaseHelperResources(true)
      this.resolveSettled()
      throw error
    }
    this.complete()
  }

  async close(): Promise<void> {
    await this.terminate()
  }

  /** 收敛已被证明：释放状态目录并释放记录，避免 supervisor 的 Set 与临时目录无限增长。 */
  private complete(): void {
    if (this.settledFlag) {
      return
    }
    this.clearPoll()
    this.releaseHelperResources()
    state.removeStateDir(this.stateDir)
    this.settle()
  }

  /** 有界收尾结束后释放本进程的管道与引用，不把残留后代持有的输出管道变成无限等待。 */
  private releaseHelperResources(force = false): void {
    this.helper.stdin.destroy()
    if (force) {
      this.helper.stdout.destroy()
      this.helper.stderr.destroy()
    }
    if (this.helper.connected) {
      this.helper.disconnect()
    }
    this.helper.unref()
  }

  private async awaitScope(): Promise<void> {
    const deadline = Date.now() + PREPARE_BUDGET_MILLIS
    while (true) {
      const published = state.read(this.stateDir, state.SCOPE_FILE)
      if (published !== null) {
        const sessionId = Number(published)
        if (!Number.isSafeInteger(sessionId) || sessionId <= 0) {
          throw new Error(`the process scope helper published an invalid scope id: ${published}`)
        }
        // helper 是会话 leader，因此 scope id 必须等于 helper 自己的 pid：否则宁可失败也不向陌生会话发信号。
        if (sessionId !== this.helper.pid) {
          throw new Error('the process scope helper published a scope id that is not its own session')
        }
        this.sessionId = sessionId
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
   * <p>只接受 helper 发布的**固定阶段代码**（`helper-<phase>-failure`），并据此给出通用说明；其它任何内容（例如被篡改的文件、
   * 或 helper 内部拼出的通用 spawn 路径）都不回显，避免把命令路径或环境片段带进对外错误。
   */
  private startupFailure(): string {
    const reported = state.read(this.stateDir, state.ERROR_FILE)
    if (reported !== null && /^helper-[a-z]+-failure$/.test(reported)) {
      return `the process scope helper failed during its ${reported.slice('helper-'.length, -'-failure'.length)} phase`
    }
    return `the process scope helper exited with code ${this.helperCloseCode} before establishing the OS scope`
  }

  private async runTermination(graceMillis: number): Promise<void> {
    if (!this.scopeEstablished) {
      if (!(await this.forceKillHelper())) {
        throw new Error('the process scope helper could not be terminated')
      }
      return
    }
    // helper 存活时父进程广播会话信号：helper 自己会活到收敛完成；helper 已退出则走只读确认之后的强杀。
    posix.signalMembers(this.sessionId, posix.SIGTERM, posix.NO_PROCESS)
    await this.awaitHelperExit(graceMillis)
    if (!this.helperExited) {
      posix.signalMembers(this.sessionId, posix.SIGKILL, posix.NO_PROCESS)
      this.helper.kill('SIGKILL')
      await this.awaitHelperExit(graceMillis)
    }
    let proven = await this.drainSession(this.sessionId)
    // helper 崩溃时，本次调用新增的、被内核收养到父进程名下的直接子进程才需要父进程收尾；基线子进程与其它活动 helper 一律排除。
    // 基线采样不可判定时不做这一步：宁可留下一个未收敛的孤儿（如实计入 remaining），也绝不误杀陌生进程。
    if (this.adoptsOrphans && this.baselinePids !== null) {
      const excluded = [...this.excludedHelpers(), ...this.baselinePids]
      proven = (await posix.convergeAdoptedChildren(process.pid, graceMillis, excluded)) && proven
    }
    // 收养收尾可能清空身份已失效的登记会话；最终只读核验必须在它之后进行。
    proven = (await this.drainTrackedSessions()) && proven
    if (!proven) {
      throw new Error('the process scope could not be proven converged')
    }
  }

  /**
   * 收敛显式登记的后代会话：发信号之前重新核验「仍是本后代」且「启动身份未变」。
   *
   * <p>核验不通过时跳过：宁可报告未收敛，也绝不向可能复用了同一个 sid 的陌生进程发信号。
   */
  private async drainTrackedSessions(): Promise<boolean> {
    let proven = true
    for (const tracked of [...this.extraSessions.values()]) {
      const identity = posix.processIdentity(tracked.childPid)
      if (identity === null || identity !== tracked.identity || posix.sessionOf(tracked.childPid) !== tracked.session) {
        // 身份失效不授权发信号，但只有旧会话的空快照才能证明它已经消失。
        const members = posix.membersSnapshot(tracked.session, posix.NO_PROCESS)
        if (members === null || members.length > 0) {
          proven = false
        }
        continue
      }
      proven = (await this.drainSession(tracked.session)) && proven
    }
    return proven
  }

  /**
   * helper 退出之后的收敛：绝不盲广播，而是「重新核验身份之后逐个强杀」，直到没有活着的成员。
   *
   * <p>只做只读确认会把「helper 先死、后代还在」当成失败并留下资源；照着快照直接发信号又可能杀到复用了同一个 pid 的无关进程。
   * 发信号前重新确认「仍属于本次会话且启动时刻未变」，核验不通过就不发信号。
   */
  private async drainSession(session: number): Promise<boolean> {
    const deadline = Date.now() + CONVERGENCE_BUDGET_MILLIS
    while (true) {
      const members = posix.membersSnapshot(session, posix.NO_PROCESS)
      if (members === null) {
        return false
      }
      if (members.length === 0) {
        return true
      }
      posix.signalMembers(session, posix.SIGKILL, posix.NO_PROCESS)
      if (Date.now() >= deadline) {
        return false
      }
      await delay(POLL_INTERVAL_MILLIS)
    }
  }

  /** 强杀 helper 并等它消失：放行之前失败时范围里没有任何用户命令，直接强杀是安全的。 */
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
    if (report !== null) {
      this.resolveExit({ exitCode: report.exitCode, signal: report.signal })
    } else {
      this.resolveExit({ exitCode: this.helperCloseCode, signal: this.helperCloseSignal })
    }
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

  /**
   * 命令自然退出并且 helper 已经证明收敛时，本次调用其实已经结束：释放状态目录并释放记录，避免 supervisor 的 Set 无限增长。
   *
   * <p>只有 helper 明确发布 `cleanup=true` 且没有正在进行的显式收尾时才这么做：崩溃残留必须留给 `terminate()` 走完整收敛。
   */
  private settleNaturalCompletion(): void {
    if (this.settledFlag || this.termination !== null) {
      return
    }
    if (this.readCleanup() !== true) {
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

  private readExitReport(): state.ExitReport | null {
    try {
      return state.readExit(this.stateDir)
    } catch {
      return null
    }
  }

  private readCleanup(): boolean | null {
    try {
      return state.readCleanup(this.stateDir)
    } catch {
      return null
    }
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
