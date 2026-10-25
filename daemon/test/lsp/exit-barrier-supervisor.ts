import type {
  ManagedProcess,
  ProcessExit,
  ProcessSpec,
  ProcessSupervisor,
} from '../../src/contracts.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

/**
 * Splits the two facts a real supervisor reports separately: the exit observation and the managed
 * scope actually reaching stdio close. `reportExit` settles only `ManagedProcess.exit`, while
 * `terminate` remains pinned behind `releaseCleanup` before it drives the real child (or fails).
 */
export class ExitBarrierSupervisor implements ProcessSupervisor {
  private readonly processes = new TestProcessSupervisor()
  private readonly exitPromise: Promise<ProcessExit>
  private resolveExit!: (exit: ProcessExit) => void
  private readonly cleanupGate: Promise<void>
  private releaseCleanupGate!: () => void
  private readonly terminateStartedPromise: Promise<void>
  private resolveTerminateStarted!: () => void
  private readonly terminateFailure: Error | null
  terminateCalls = 0

  constructor(terminateFailure: Error | null = null) {
    this.terminateFailure = terminateFailure
    this.exitPromise = new Promise((resolve) => {
      this.resolveExit = resolve
    })
    this.cleanupGate = new Promise((resolve) => {
      this.releaseCleanupGate = resolve
    })
    this.terminateStartedPromise = new Promise((resolve) => {
      this.resolveTerminateStarted = resolve
    })
  }

  get terminateStarted(): Promise<void> {
    return this.terminateStartedPromise
  }

  reportExit(exit: ProcessExit = { exitCode: 0, signal: null }): void {
    this.resolveExit(exit)
  }

  releaseCleanup(): void {
    this.releaseCleanupGate()
  }

  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    const managed = await this.processes.start(spec)
    return {
      pid: managed.pid,
      stdin: managed.stdin,
      stdout: managed.stdout,
      stderr: managed.stderr,
      exit: this.exitPromise,
      terminate: async (graceMillis?: number): Promise<void> => {
        this.terminateCalls += 1
        this.resolveTerminateStarted()
        await this.cleanupGate
        if (this.terminateFailure) {
          throw this.terminateFailure
        }
        await managed.terminate(graceMillis)
      },
    }
  }

  /** Releases the barrier first so a failed test cannot leave the real child behind. */
  async close(): Promise<void> {
    this.releaseCleanupGate()
    await this.processes.close()
  }
}
