import { spawn, type ChildProcess } from 'node:child_process'
import type {
  ManagedProcess,
  ProcessExit,
  ProcessSpec,
  ProcessSupervisor,
} from '../../src/contracts.js'

export class TestProcessSupervisor implements ProcessSupervisor {
  private readonly children = new Set<ChildProcess>()

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    const child = spawn(spec.executable, spec.args, {
      cwd: spec.cwd,
      env: spec.env ? { ...process.env, ...spec.env } : process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })

    this.children.add(child)

    let resolvedExit: ProcessExit | null = null
    const exitPromise = new Promise<ProcessExit>((resolve) => {
      child.on('exit', (code, signal) => {
        resolvedExit = { exitCode: code, signal }
        this.children.delete(child)
        resolve(resolvedExit)
      })
      child.on('error', () => {
        resolvedExit = { exitCode: null, signal: null }
        this.children.delete(child)
        resolve(resolvedExit)
      })
    })

    return {
      pid: child.pid ?? -1,
      stdin: child.stdin!,
      stdout: child.stdout!,
      stderr: child.stderr!,
      exit: exitPromise,
      terminate: async (graceMillis = 1000) => {
        if (resolvedExit !== null) {
          return
        }
        child.kill('SIGTERM')
        const timer = new Promise<void>((resolve) => setTimeout(resolve, graceMillis))
        const race = await Promise.race([
          exitPromise.then(() => true),
          timer.then(() => false),
        ])
        if (!race) {
          child.kill('SIGKILL')
          await exitPromise
        }
      },
    }
  }

  async close(): Promise<void> {
    for (const child of this.children) {
      try {
        child.kill('SIGKILL')
      } catch {}
    }
    this.children.clear()
  }
}
