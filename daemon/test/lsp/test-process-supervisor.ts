import { spawn, type ChildProcess } from 'node:child_process'
import type {
  ManagedProcess,
  ProcessExit,
  ProcessSpec,
  ProcessSupervisor,
} from '../../src/contracts.js'

interface TrackedChild {
  child: ChildProcess
  closed: Promise<void>
}

export class TestProcessSupervisor implements ProcessSupervisor {
  private readonly tracked = new Set<TrackedChild>()
  /** Observation only: every spawned child in launch order, like the IDE fixture. */
  readonly children: ChildProcess[] = []

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    const child = spawn(spec.executable, spec.args, {
      cwd: spec.cwd,
      env: spec.env ? { ...process.env, ...spec.env } : process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.children.push(child)

    // Registered at spawn time: 'exit' precedes stdio teardown, so only 'close' proves the
    // pipes are released. A listener attached later could miss an event that already fired.
    const tracked: TrackedChild = {
      child,
      closed: new Promise<void>((resolve) => {
        child.on('close', () => {
          this.tracked.delete(tracked)
          resolve()
        })
      }),
    }
    this.tracked.add(tracked)

    let resolvedExit: ProcessExit | null = null
    const exitPromise = new Promise<ProcessExit>((resolve) => {
      const settle = (exit: ProcessExit): void => {
        if (resolvedExit === null) {
          resolvedExit = exit
          resolve(exit)
        }
      }
      child.on('exit', (code, signal) => settle({ exitCode: code, signal }))
      // A failed spawn emits 'error' and then 'close'; both settle so close() never hangs.
      child.on('error', () => settle({ exitCode: null, signal: null }))
    })

    // Bounded grace: resolve the race's timer immediately so no stray timeout keeps the loop up.
    const exitedWithin = async (graceMillis: number): Promise<boolean> => {
      let timer: ReturnType<typeof setTimeout> | undefined
      const timeout = new Promise<boolean>((resolve) => {
        timer = setTimeout(() => resolve(true), graceMillis)
      })
      try {
        return await Promise.race([exitPromise.then(() => false), timeout])
      } finally {
        if (timer !== undefined) {
          clearTimeout(timer)
        }
      }
    }

    return {
      pid: child.pid ?? -1,
      stdin: child.stdin!,
      stdout: child.stdout!,
      stderr: child.stderr!,
      exit: exitPromise,
      terminate: async (graceMillis = 1000) => {
        if (resolvedExit === null) {
          child.kill('SIGTERM')
          if (await exitedWithin(graceMillis)) {
            child.kill('SIGKILL')
            await exitPromise
          }
        }
        // Terminate only resolves once stdio teardown is real, never right after 'exit'.
        await tracked.closed
      },
    }
  }

  async close(): Promise<void> {
    const pending = [...this.tracked]
    for (const { child } of pending) {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill('SIGKILL')
      }
    }
    // Await every registered close, including children that exited but still hold their pipes.
    await Promise.all(pending.map(({ closed }) => closed))
  }
}
