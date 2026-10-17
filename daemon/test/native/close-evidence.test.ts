import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, test, vi } from 'vitest'
import { PosixProcessSupervisor } from '../../src/native/posix-supervisor.js'
import { fixtureCommand, waitForPid } from './process-test-util.js'

// 会话成员枚举「不可判定」时收敛无法被证明。这里把该判定固定为不可判定，验证收敛失败不会顺带删掉状态目录：
// scope/error/cleanup 是唯一可复核的收尾证据，必须保留下来而不是静默丢弃。
vi.mock('../../src/native/posix-session.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/native/posix-session.js')>()
  return { ...actual, membersSnapshot: () => null }
})

// 仅 POSIX：Windows 走 Job 收敛，不经会话枚举。
describe.skipIf(process.platform === 'win32')('native close evidence', () => {
  test('an unprovable convergence keeps the state directory', async () => {
    const workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-evidence-'))
    const supervisor = new PosixProcessSupervisor()
    const command = fixtureCommand('hold.mjs', join(workdir, 'root.pid'))
    const managed = await supervisor.start({ executable: command.executable, args: command.args, cwd: workdir })
    await waitForPid(join(workdir, 'root.pid'))
    const processState = managed as unknown as { stateDir: string; settled: Promise<void> }
    const stateDir = processState.stateDir
    expect(existsSync(stateDir)).toBe(true)

    await expect(managed.terminate()).rejects.toThrow(/proven converged/)
    expect(managed.stdin.destroyed).toBe(true)
    expect(managed.stdout.destroyed).toBe(true)
    expect(managed.stderr.destroyed).toBe(true)
    await processState.settled
    await expect(managed.close()).rejects.toThrow(/proven converged/)
    // 收敛失败时状态目录必须还在：它是这次收尾的唯一证据。
    expect(existsSync(stateDir)).toBe(true)

    rmSync(stateDir, { recursive: true, force: true })
    rmSync(workdir, { recursive: true, force: true })
    await supervisor.close().catch(() => {
      // 该范围已经被断言为无法证明收敛：这里的兜底不掩盖上面的断言。
    })
  }, 30_000)
})
