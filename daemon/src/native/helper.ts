import { spawn, type ChildProcess } from 'node:child_process'
import { appendFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { ExitReport, LaunchSpec } from './state.js'

/**
 * 进程 scope helper：以独立 Node 进程建立操作系统级执行范围，然后在范围内启动用户命令。
 *
 * <p>daemon 不是用户命令的 OS 所有者：它无法把 `spawn()` 直接放进一个新的 session/Job。helper 因此先取得所有权（POSIX
 * 用 `setsid` 建立新 session 与进程组；Windows 建立带 `KILL_ON_JOB_CLOSE` 的命名 Job 并把首个进程挂起后归属进去），
 * 再等父进程确认，最后才让用户命令运行。
 *
 * <p>helper 的命令行只携带固定入口与调用私有的状态目录；启动规格（cwd 与 argv）通过状态文件交接、读取后立即删除，因此命令行
 * 与诊断都不会带上启动参数。
 *
 * <p>「父进程确认之后才启动命令」是本文件的核心约定：许可之前失败（取消、超时、父进程退出）因此永远等价于「用户命令没有产生
 * 任何副作用」。命令自然退出时先把退出事实原子发布，再收敛整个范围并把收敛事实发布到 `cleanup`。
 *
 * <p>本入口以 `.ts` 源码交付（编译产物为同目录 `helper.js`），运行平台由父进程决定；Node 以类型剥离直接执行 `.ts` 时相对
 * import 的 `.js` 后缀无法解析，因此同目录模块一律按 `js`/`ts` 两种扩展名探测后动态载入。
 */

const HERE = new URL('./', import.meta.url)

/** 等待父进程许可的预算；父进程的启动预算与它一致。 */
const PERMIT_BUDGET_MILLIS = 20_000

/** 会话收敛的宽限窗口。 */
const TERMINATION_GRACE_MILLIS = 600

/** Windows Job 收敛的等待预算。 */
const JOB_DRAIN_BUDGET_MILLIS = 2_000

/** 轮询步长：足够细，使正常收敛不必白等整个预算。 */
const POLL_INTERVAL_MILLIS = 5

interface StateModule {
  SCOPE_FILE: string
  PERMIT_FILE: string
  EXIT_FILE: string
  ERROR_FILE: string
  CLEANUP_FILE: string
  DIAGNOSTICS_FILE: string
  publish(stateDir: string, name: string, content: string): void
  publishQuietly(stateDir: string, name: string, content: string): void
  read(stateDir: string, name: string): string | null
  readLaunch(stateDir: string): LaunchSpec | null
}

interface PosixModule {
  establishSession(): number
  becomeChildSubreaper(): boolean
  convergeSession(session: number, excludedPid: number, graceMillis: number): Promise<boolean>
  convergeAdoptedChildren(ownerPid: number, graceMillis: number, excludedPids: readonly number[]): Promise<boolean>
  startOrphanReaper(intervalMillis?: number): () => void
}

interface Win32JobScope {
  readonly processId: number
  resume(): void
  awaitExit(): Promise<ExitReport>
  terminateAndDrain(budgetMillis: number): Promise<boolean>
  close(): void
}

interface Win32Module {
  jobNameFor(stateDir: string): string
  createSuspendedJob(jobName: string, spec: LaunchSpec, env: NodeJS.ProcessEnv): Promise<Win32JobScope>
}

async function loadSibling(name: string): Promise<unknown> {
  for (const extension of ['js', 'ts']) {
    const url = new URL(`${name}.${extension}`, HERE)
    if (existsSync(fileURLToPath(url))) {
      return import(url.href)
    }
  }
  throw new Error(`the native process scope helper could not locate the ${name} module`)
}

async function main(): Promise<void> {
  const stateDir = process.argv[2]
  if (stateDir === undefined) {
    process.exit(2)
    return
  }
  const state = (await loadSibling('state')) as unknown as StateModule
  installDiagnostics(stateDir, state)
  try {
    const spec = state.readLaunch(stateDir)
    if (spec === null) {
      throw new Error('the process scope helper launch spec is missing')
    }
    const code = process.platform === 'win32' ? await runWindows(stateDir, spec, state) : await runPosix(stateDir, spec, state)
    process.exit(code)
  } catch (error) {
    state.publishQuietly(stateDir, state.ERROR_FILE, describe(error))
    process.exit(1)
  }
}

/** POSIX：先建立 session/进程组并公开范围，等父进程确认，然后启动用户命令。 */
async function runPosix(stateDir: string, spec: LaunchSpec, state: StateModule): Promise<number> {
  const posix = (await loadSibling('posix-session')) as unknown as PosixModule
  const sessionId = posix.establishSession()
  posix.becomeChildSubreaper()

  let convergence: Promise<boolean> | null = null
  let shutdownPromise: Promise<number> | null = null
  let stopping = false
  const converge = (): Promise<boolean> => {
    if (convergence === null) {
      convergence = (async () => {
        // 先收敛本会话，再收敛被内核收养到 helper 名下的直接子进程所属会话：故意 setsid 的后代只有在父进程退出后才可核实。
        const converged = await posix.convergeSession(sessionId, process.pid, TERMINATION_GRACE_MILLIS)
        const adopted = await posix.convergeAdoptedChildren(process.pid, TERMINATION_GRACE_MILLIS, [])
        return converged && adopted
      })()
    }
    return convergence
  }
  // SIGTERM 是父进程广播的温和信号：helper 捕获它（而不是忽略），一边屏蔽重复信号一边收敛整个会话。
  const shutdown = (): Promise<number> => {
    if (shutdownPromise === null) {
      shutdownPromise = (async () => {
        const drained = await converge()
        state.publishQuietly(stateDir, state.CLEANUP_FILE, String(drained))
        return drained ? 0 : 1
      })()
    }
    return shutdownPromise
  }
  process.on('SIGTERM', () => {
    stopping = true
    void shutdown().then((code) => process.exit(code))
  })

  // hook 必须在范围发布之前注册：发布之后父进程随时可能对会话广播温和信号。
  state.publish(stateDir, state.SCOPE_FILE, String(sessionId))
  await awaitPermit(stateDir, state)
  if (stopping) {
    return shutdown()
  }
  const command = spec.args[0]
  if (command === undefined) {
    throw new Error('the process scope command must not be empty')
  }
  const child = spawn(command, spec.args.slice(1), {
    cwd: spec.cwd,
    env: process.env,
    stdio: 'inherit',
  })
  const exit = await waitForExit(child)
  state.publish(stateDir, state.EXIT_FILE, JSON.stringify(exit))
  posix.startOrphanReaper()
  if (stopping) {
    return shutdown()
  }
  const drained = await converge()
  state.publish(stateDir, state.CLEANUP_FILE, String(drained))
  return drained ? 0 : 1
}

/** Windows：建立命名 Job 并让首个进程保持挂起，等父进程持有同一个 Job 的句柄之后再恢复执行。 */
async function runWindows(stateDir: string, spec: LaunchSpec, state: StateModule): Promise<number> {
  const win = (await loadSibling('win32')) as unknown as Win32Module
  const job = await win.createSuspendedJob(win.jobNameFor(stateDir), spec, process.env)
  try {
    state.publish(stateDir, state.SCOPE_FILE, String(job.processId))
    await awaitPermit(stateDir, state)
    job.resume()
    const exit = await job.awaitExit()
    state.publish(stateDir, state.EXIT_FILE, JSON.stringify(exit))
    const drained = await job.terminateAndDrain(JOB_DRAIN_BUDGET_MILLIS)
    state.publish(stateDir, state.CLEANUP_FILE, String(drained))
    return drained ? 0 : 1
  } finally {
    job.close()
  }
}

/**
 * 等到父进程确认范围。
 *
 * <p>许可之前不启动任何进程，因此取消或超时总能赶在用户命令产生副作用之前生效；父进程若放弃本次调用，会直接结束 helper。
 */
async function awaitPermit(stateDir: string, state: StateModule): Promise<void> {
  const deadline = Date.now() + PERMIT_BUDGET_MILLIS
  while (state.read(stateDir, state.PERMIT_FILE) === null) {
    if (Date.now() >= deadline) {
      throw new Error(`the parent did not confirm the process scope within ${PERMIT_BUDGET_MILLIS} ms; the command was not started`)
    }
    await delay(POLL_INTERVAL_MILLIS)
  }
}

function waitForExit(child: ChildProcess): Promise<ExitReport> {
  return new Promise((resolve) => {
    child.once('exit', (code, signal) => resolve({ exitCode: code, signal: signal ?? null }))
    child.once('error', () => resolve({ exitCode: null, signal: null }))
  })
}

/** helper 自身诊断的单行长度上限：只保留足以定位阶段的信息，绝不整段外泄。 */
const DIAGNOSTICS_LIMIT = 200

/**
 * helper 自己的报错写调用私有的诊断文件，绝不进入用户命令的输出流。
 *
 * <p>标准输出与标准错误属于用户命令（helper 以 `stdio: 'inherit'` 让命令继承它们）：helper 侧因此只注册兜底处理器，不做任何
 * 常规输出；父进程启动 helper 时关闭 Node 的警告输出。诊断只作为**单行**短记录落进 0700/0600 的状态目录，既不回显启动参数，
 * 也不被父进程拼进对外抛出的错误。
 */
function installDiagnostics(stateDir: string, state: StateModule): void {
  const record = (value: string): void => {
    try {
      appendFileSync(join(stateDir, state.DIAGNOSTICS_FILE), `${value}\n`)
    } catch {
      // 诊断文件不可写只影响 helper 报错的可见性；父进程仍会以退出码与状态文件收敛为失败。
    }
  }
  process.on('uncaughtException', (error) => {
    record(describe(error))
    process.exit(1)
  })
  process.on('unhandledRejection', (reason) => {
    record(describe(reason))
    process.exit(1)
  })
}

function describe(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  return message.replace(/\s+/g, ' ').slice(0, DIAGNOSTICS_LIMIT)
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

void main()
