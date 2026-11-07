import { spawn } from 'node:child_process'
import { rgPath } from '@vscode/ripgrep'

/**
 * `@vscode/ripgrep` 的子进程边界。
 *
 * <p>只负责启动、读取 stdout/stderr、在 abort 或调用方要求停止时终止进程，并等待 {@code close} 确认进程已被回收。搜索语义
 * （参数、忽略规则、结果上限与 partial）留在 {@code search.ts}。命令对象允许测试注入 Node 脚本作为假 rg；生产装配只使用
 * `@vscode/ripgrep` 导出的 rgPath。
 */

export interface RipgrepCommand {
  binary: string
  prefixArgs?: readonly string[]
  /**
   * 子进程环境覆盖：显式给出的键覆盖父进程同名变量，其余变量照常继承。
   *
   * <p>只服务于测试夹具。任何值都可以写成 `undefined`，从而把继承来的同名变量从子进程环境中移除；Node 无条件把父进程的
   * `NODE_V8_COVERAGE` 传播给子进程，夹具在退出过程中被强杀时会留下写了一半的覆盖率文件，Vitest 解析它就会让整个运行失败。
   */
  env?: Readonly<Record<string, string | undefined>>
}

export type RipgrepStop = 'abort' | 'limit' | null

export interface RipgrepResult {
  /** spawn 是否成功创建了子进程。 */
  launched: boolean
  code: number | null
  signal: NodeJS.Signals | null
  stopped: RipgrepStop
  stderr: string
  launchError: Error | null
  spawnError: Error | null
  consumerError: Error | null
}

const MAX_STDERR_CHARS = 64 * 1024

/** 生产使用的 rg 命令；rgPath 由按平台的 optional 预编译包提供。 */
export const defaultRipgrepCommand: RipgrepCommand = { binary: rgPath }

/**
 * 运行一次 rg。
 *
 * <p>`onChunk` 返回 false 表示调用方已拿到足够结果：终止进程并以 `stopped: 'limit'` 返回。abort 一律 SIGKILL；只有 `close`
 * 事件才算进程已退出，调用方据此确认执行范围结束。启动失败不抛异常，结果中用 `launchError` / `spawnError` 表达，便于
 * 能力层区分“未启动”与“已启动但失败”。
 */
export function runRipgrep(
  command: RipgrepCommand,
  args: readonly string[],
  cwd: string,
  signal: AbortSignal,
  onChunk: (chunk: Buffer) => boolean | void,
): Promise<RipgrepResult> {
  const prefix = command.prefixArgs ?? []
  // 未显式覆盖时不传 env：交给 spawn 的 undefined 与默认继承等价。
  const env = command.env === undefined ? undefined : { ...process.env, ...command.env }
  let child
  try {
    child = spawn(command.binary, [...prefix, ...args], {
      cwd,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    })
  } catch (error) {
    return Promise.resolve({
      launched: false,
      code: null,
      signal: null,
      stopped: null,
      stderr: '',
      launchError: asError(error),
      spawnError: null,
      consumerError: null,
    })
  }

  return new Promise<RipgrepResult>((resolve) => {
    let stopped: RipgrepStop = null
    let launched = false
    let exited = false
    let spawnError: Error | null = null
    let consumerError: Error | null = null
    let stderr = ''

    const kill = (): void => {
      if (child.exitCode !== null || child.signalCode !== null) {
        return
      }
      child.kill('SIGKILL')
    }

    const stop = (reason: 'abort' | 'limit'): void => {
      if (stopped !== null || exited) {
        return
      }
      stopped = reason
      kill()
    }

    const onAbort = (): void => stop('abort')
    if (signal.aborted) {
      onAbort()
    } else {
      signal.addEventListener('abort', onAbort, { once: true })
    }

    child.once('spawn', () => {
      launched = true
      if (stopped !== null) {
        kill()
      }
    })
    child.once('exit', () => {
      exited = true
    })
    child.stdout?.on('data', (chunk: Buffer) => {
      if (stopped === 'abort') {
        return
      }
      try {
        if (onChunk(chunk) === false) {
          stop('limit')
        }
      } catch (error) {
        consumerError ??= asError(error)
        stop('limit')
      }
    })
    child.stderr?.on('data', (chunk: Buffer) => {
      if (stderr.length >= MAX_STDERR_CHARS) {
        return
      }
      stderr += chunk.toString('utf8')
      if (stderr.length > MAX_STDERR_CHARS) {
        stderr = stderr.slice(0, MAX_STDERR_CHARS)
      }
    })
    child.once('error', (error) => {
      spawnError = error
    })
    child.once('close', (code, childSignal) => {
      signal.removeEventListener('abort', onAbort)
      resolve({
        launched,
        code,
        signal: childSignal,
        stopped,
        stderr,
        launchError: null,
        spawnError,
        consumerError,
      })
    })
  })
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}
