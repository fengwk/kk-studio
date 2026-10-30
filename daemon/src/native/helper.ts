import { spawn, type ChildProcess } from 'node:child_process'
import { appendFileSync } from 'node:fs'
import { join } from 'node:path'
import { processScopePhase } from './host.js'
import * as posix from './posix-session.js'
import * as state from './state.js'
import * as win from './win32.js'
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
 * <p>本入口只以**编译产物** `helper.js` 运行（见 helper-path.ts）：同目录模块一律用静态 `.js` import，不再做源码态扩展名探测。
 * `posix-session`/`win32` 的原生库都是惰性加载，因此非目标平台 import 本文件也不会触碰 kernel32/libc。
 */

/** 等待父进程许可的预算；父进程的启动预算与它一致。 */
const PERMIT_BUDGET_MILLIS = 20_000

/** 会话收敛的宽限窗口。 */
const TERMINATION_GRACE_MILLIS = 600

/** Windows Job 收敛的等待预算。 */
const JOB_DRAIN_BUDGET_MILLIS = 2_000

/** 轮询步长：足够细，使正常收敛不必白等整个预算。 */
const POLL_INTERVAL_MILLIS = 5

async function main(): Promise<void> {
  const stateDir = process.argv[2]
  if (stateDir === undefined) {
    process.exit(2)
    return
  }
  installDiagnostics(stateDir)
  let phase = 'launch'
  try {
    const spec = state.readLaunch(stateDir)
    if (spec === null) {
      throw new Error('the process scope helper launch spec is missing')
    }
    phase = processScopePhase()
    const code = phase === 'windows' ? await runWindows(stateDir, spec) : await runPosix(stateDir, spec)
    process.exit(code)
  } catch (error) {
    // 对外只发布**固定阶段代码**：命令路径、参数与环境一律不进入父进程可见的失败说明。
    state.publishQuietly(stateDir, state.ERROR_FILE, `helper-${phase}-failure`)
    recordDiagnostics(stateDir, `${phase}: ${describe(error)}`)
    process.exit(1)
  }
}

/**
 * 父进程消失（IPC 通道 `disconnect`）时的收尾钩子。
 *
 * <p>专用 IPC 通道（与用户命令的 stdin/stdout/stderr 分离）由父进程持有；父进程被强杀时内核关闭该通道，helper 因此收到
 * `disconnect`——这是真实的内核 EOF，不是心跳或猜父 pid。helper 必须在**放行用户命令之前**注册它，这样即使父进程在许可之前
 * 就消失，也不会有任何用户命令产生副作用。
 */
function installParentGoneHook(onParentGone: () => void): void {
  process.on('disconnect', onParentGone)
}

/** POSIX：先建立 session/进程组并公开范围，等父进程确认，然后启动用户命令。 */
async function runPosix(stateDir: string, spec: LaunchSpec): Promise<number> {
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
    void shutdown().then((code) => process.exit(code), () => process.exit(1))
  })
  // 父进程消失也是收尾信号：必须在范围发布之前注册，保证「许可之前没有副作用」的前提不被父进程崩溃打破。
  installParentGoneHook(() => {
    stopping = true
    void shutdown().then((code) => process.exit(code), () => process.exit(1))
  })

  // hook 必须在范围发布之前注册：发布之后父进程随时可能对会话广播温和信号。
  state.publish(stateDir, state.SCOPE_FILE, String(sessionId))
  await awaitPermit(stateDir)
  if (stopping) {
    return shutdown()
  }
  const command = spec.args[0]
  if (command === undefined) {
    throw new Error('the process scope command must not be empty')
  }
  const child = spawn(command, spec.args.slice(1), {
    cwd: spec.cwd,
    env: commandEnvironment(),
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
async function runWindows(stateDir: string, spec: LaunchSpec): Promise<number> {
  const job = await win.createSuspendedJob(win.jobNameFor(stateDir), spec, commandEnvironment())
  let stopping = false
  let shutdownPromise: Promise<number> | null = null
  // 父进程消失：Job 由 helper 持有，KILL_ON_JOB_CLOSE 只在 helper 自己的句柄关闭时生效，因此这里必须显式清空整个 Job。
  // 收敛失败（`terminateAndDrain` 抛出）如实发布 cleanup=false 并返回非 0，绝不假装已经清空。
  const shutdown = (): Promise<number> => {
    if (shutdownPromise === null) {
      shutdownPromise = (async () => {
        try {
          await job.terminateAndDrain(JOB_DRAIN_BUDGET_MILLIS)
          state.publishQuietly(stateDir, state.CLEANUP_FILE, 'true')
          return 0
        } catch {
          state.publishQuietly(stateDir, state.CLEANUP_FILE, 'false')
          return 1
        }
      })()
    }
    return shutdownPromise
  }
  installParentGoneHook(() => {
    stopping = true
    void shutdown().then((code) => process.exit(code), () => process.exit(1))
  })
  try {
    state.publish(stateDir, state.SCOPE_FILE, String(job.processId))
    await awaitPermit(stateDir)
    if (stopping) {
      return shutdown()
    }
    job.resume()
    const exit = await job.awaitExit()
    state.publish(stateDir, state.EXIT_FILE, JSON.stringify(exit))
    if (stopping) {
      return shutdown()
    }
    try {
      await job.terminateAndDrain(JOB_DRAIN_BUDGET_MILLIS)
      state.publish(stateDir, state.CLEANUP_FILE, 'true')
      return 0
    } catch {
      state.publishQuietly(stateDir, state.CLEANUP_FILE, 'false')
      return 1
    }
  } finally {
    job.close()
  }
}

/**
 * 用户命令的环境：复制 helper 自身环境，但剔除 Node 专用 IPC 变量。
 *
 * <p>helper 与父进程之间用一条专用 IPC 通道（`disconnect` 收尾的依据），通道会向 helper 注入 `NODE_CHANNEL_FD`。若原样传给
 * 用户命令，命令里的 Node 进程会误用 helper 的通道。因此命令环境里必须移除它。
 */
function commandEnvironment(): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env }
  delete env.NODE_CHANNEL_FD
  return env
}

/**
 * 等到父进程确认范围。
 *
 * <p>许可之前不启动任何进程，因此取消或超时总能赶在用户命令产生副作用之前生效；父进程若放弃本次调用，会直接结束 helper。
 */
async function awaitPermit(stateDir: string): Promise<void> {
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

/**
 * helper 自己的报错写调用私有的诊断文件，绝不进入用户命令的输出流。
 *
 * <p>标准输出与标准错误属于用户命令（helper 以 `stdio: 'inherit'` 让命令继承它们）：helper 侧因此只注册兜底处理器，不做任何
 * 常规输出；父进程启动 helper 时关闭 Node 的警告输出。
 *
 * <p>诊断文件以 **0600** 创建（`appendFileSync` 的默认 0666 会把内容暴露给同机其它用户）。记录内容只保留阶段与错误**类型名**，
 * 不写原始消息：截断无法去掉消息里的令牌或路径，因此这里从源头就不落敏感原文。
 */
function installDiagnostics(stateDir: string): void {
  process.on('uncaughtException', (error) => {
    recordDiagnostics(stateDir, `uncaught: ${describe(error)}`)
    process.exit(1)
  })
  process.on('unhandledRejection', (reason) => {
    recordDiagnostics(stateDir, `rejection: ${describe(reason)}`)
    process.exit(1)
  })
}

/** 追加一行严格私有诊断；失败只影响可见性，不改变退出码与状态文件结论。 */
function recordDiagnostics(stateDir: string, value: string): void {
  try {
    appendFileSync(join(stateDir, state.DIAGNOSTICS_FILE), `${value}\n`, { mode: 0o600 })
  } catch {
    // 诊断文件不可写只影响 helper 报错的可见性；父进程仍会以退出码与状态文件收敛为失败。
  }
}

/** 只保留错误**类型名**（或非 Error 的类型），绝不带原始消息。 */
function describe(error: unknown): string {
  if (error instanceof Error) {
    return error.name === '' ? 'Error' : error.name
  }
  return typeof error
}

function delay(millis: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, millis))
}

void main()
