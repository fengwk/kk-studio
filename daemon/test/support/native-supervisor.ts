import { spawn, type ChildProcess } from 'node:child_process'
import type { ManagedProcess, ProcessExit, ProcessSpec, ProcessSupervisor } from '../../src/contracts.js'

/**
 * 测试用原生 supervisor：用真实子进程实现 `ProcessSupervisor` 边界。
 *
 * <p>生产 supervisor 由 `src/native` 提供；这里只保留测试需要的确定语义——spawn 失败即拒绝、
 * terminate 幂等并等待真正退出、abort 时终止子进程。它让受管进程的 drain / 超时 / 取消行为在真实
 * 进程上被验证，而不是被 fake 掩盖。
 */
export function createNativeSupervisorFixture(): ProcessSupervisor {
  const children = new Set<ChildProcess>()
  return {
    async start(spec: ProcessSpec): Promise<ManagedProcess> {
      const child = spawn(spec.executable, spec.args, {
        cwd: spec.cwd,
        env: spec.env,
        stdio: ['pipe', 'pipe', 'pipe'],
      })
      children.add(child)
      await new Promise<void>((resolve, reject) => {
        child.once('spawn', () => resolve())
        child.once('error', reject)
      })
      let settled = false
      const exit = new Promise<ProcessExit>(resolve => {
        child.once('error', () => {
          if (!settled) {
            settled = true
            resolve({ exitCode: null, signal: null })
          }
        })
        child.once('close', (code, signal) => {
          if (!settled) {
            settled = true
            resolve({ exitCode: code, signal })
          }
        })
      })
      const terminate = async (): Promise<void> => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
        await exit
      }
      const onAbort = (): void => {
        void terminate()
      }
      if (spec.signal !== undefined) {
        if (spec.signal.aborted) {
          onAbort()
        } else {
          spec.signal.addEventListener('abort', onAbort, { once: true })
        }
      }
      if (child.stdin === null || child.stdout === null || child.stderr === null) {
        throw new Error('native supervisor fixture could not open the child stdio pipes')
      }
      return {
        pid: child.pid ?? 0,
        stdin: child.stdin,
        stdout: child.stdout,
        stderr: child.stderr,
        exit,
        terminate,
      }
    },
    async close(): Promise<void> {
      for (const child of children) {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill('SIGKILL')
        }
      }
      children.clear()
    },
  }
}
