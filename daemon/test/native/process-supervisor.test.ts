import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { createProcessSupervisor } from '../../src/native/index.js'
import type { ManagedProcess, ProcessSupervisor } from '../../src/contracts.js'
import { awaitGone, fixtureCommand, isAlive, waitForFile, waitForPid } from './process-test-util.js'

const TEST_TIMEOUT = 30_000
const STREAM_TIMEOUT = 10_000

// 执行范围的跨平台真实进程验收：用只依赖 Node 的夹具造出真实父子/孙进程层级，并用原生 pid 断言收敛。
describe.skipIf(process.platform === 'win32')('native process supervisor (POSIX)', () => {
  let workdir: string
  let supervisor: ProcessSupervisor
  const pending: ManagedProcess[] = []

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-proc-'))
    supervisor = createProcessSupervisor()
  })

  afterEach(async () => {
    for (const managed of pending.splice(0)) {
      try {
        await managed.close()
      } catch {
        // 用例已经断言过收敛；这里的兜底不掩盖原始失败。
      }
    }
    await supervisor.close()
    rmSync(workdir, { recursive: true, force: true })
  })

  async function start(name: string, ...args: string[]): Promise<ManagedProcess> {
    const command = fixtureCommand(name, ...args)
    const managed = await supervisor.start({ executable: command.executable, args: command.args, cwd: workdir })
    pending.push(managed)
    return managed
  }

  test('natural exit still converges live descendants', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const permit = join(workdir, 'permit')
    const managed = await start('fork-exit.mjs', rootPidFile, childPidFile, permit)

    const child = await waitForPid(childPidFile)
    expect(isAlive(child)).toBe(true)

    // 根进程出口时间点由用例许可：先确认子进程活着，再放根进程走。
    writeFileSync(permit, 'go')

    const exit = await managed.exit
    expect(exit.exitCode).toBe(0)
    await managed.terminate()
    expect(await awaitGone(child)).toBe(true)
  }, TEST_TIMEOUT)

  test('terminate converges nested descendants', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const grandPidFile = join(workdir, 'grand.pid')
    const managed = await start('nest-hold.mjs', rootPidFile, childPidFile, grandPidFile)

    const root = await waitForPid(rootPidFile)
    const child = await waitForPid(childPidFile)
    const grand = await waitForPid(grandPidFile)
    expect([root, child, grand].every(isAlive)).toBe(true)

    await managed.terminate()
    for (const pid of [root, child, grand]) {
      expect(await awaitGone(pid), `pid ${pid} must be gone`).toBe(true)
    }
  }, TEST_TIMEOUT)

  test('terminate converges descendants in another process group of the same session', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const groupPidFile = join(workdir, 'group.pid')
    const managed = await start('group-fork.mjs', rootPidFile, groupPidFile)

    const root = await waitForPid(rootPidFile)
    const grouped = await waitForPid(groupPidFile)
    expect(isAlive(root)).toBe(true)
    expect(isAlive(grouped)).toBe(true)

    await managed.terminate()
    expect(await awaitGone(root)).toBe(true)
    expect(await awaitGone(grouped)).toBe(true)
  }, TEST_TIMEOUT)

  test('stdin EOF lets the fixture exit with its own code', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const managed = await start('eof.mjs', rootPidFile, '42')
    await waitForPid(rootPidFile)
    managed.stdin.end()
    const exit = await managed.exit
    expect(exit.exitCode).toBe(42)
    await managed.terminate()
  }, TEST_TIMEOUT)

  test('duplex stdio carries input and keeps stderr separate', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const managed = await start('duplex-echo.mjs', rootPidFile, '42')
    await waitForPid(rootPidFile)

    managed.stdout.setEncoding('utf8')
    managed.stderr.setEncoding('utf8')
    const stdout = collect(managed.stdout)
    const stderr = collect(managed.stderr)

    managed.stdin.write('ping-42\n')
    const line = await readLine(managed.stdout)
    expect(line).toBe('ping-42')

    managed.stdin.end()
    const exit = await managed.exit
    expect(exit.exitCode).toBe(42)
    await managed.terminate()

    expect(stdout()).toContain('ping-42')
    expect(stderr()).toContain('FIXTURE-STDERR-MARKER')
    expect(stdout()).not.toContain('FIXTURE-STDERR-MARKER')
  }, TEST_TIMEOUT)

  test('an aborted signal prevents the command from ever running', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const controller = new AbortController()
    controller.abort()
    const command = fixtureCommand('hold.mjs', rootPidFile)
    await expect(
      supervisor.start({ executable: command.executable, args: command.args, cwd: workdir, signal: controller.signal }),
    ).rejects.toThrow(/cancelled/)
    // 许可之前失败等价于「命令从未运行」：夹具不会写下自己的 pid。
    await expect(waitForFile(rootPidFile, 200)).rejects.toThrow()
  }, TEST_TIMEOUT)

  test('trackDescendant converges a deliberately escaped session', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const escapedPidFile = join(workdir, 'escaped.pid')
    const managed = await start('escape-fork.mjs', rootPidFile, escapedPidFile)

    const root = await waitForPid(rootPidFile)
    const escaped = await waitForPid(escapedPidFile)
    expect(isAlive(escaped)).toBe(true)
    await supervisor.trackDescendant(managed.pid, escaped)

    await managed.terminate()
    expect(await awaitGone(root)).toBe(true)
    expect(await awaitGone(escaped)).toBe(true)
  }, TEST_TIMEOUT)

  test('an unregistered setsid descendant is converged after its parent exits naturally', async () => {
    // 根进程自然退出：脱离会话的后代被内核收养到 helper 名下，收尾仍然必须覆盖它（Linux）。
    if (process.platform !== 'linux') {
      return
    }
    const rootPidFile = join(workdir, 'root.pid')
    const escapedPidFile = join(workdir, 'escaped.pid')
    const managed = await start('escape-exit.mjs', rootPidFile, escapedPidFile)

    const escaped = await waitForPid(escapedPidFile)
    expect(isAlive(escaped)).toBe(true)

    const exit = await managed.exit
    expect(exit.exitCode).toBe(0)
    await managed.terminate()
    expect(await awaitGone(escaped)).toBe(true)
  }, TEST_TIMEOUT)

  test('an unregistered setsid descendant does not survive a scope helper crash', async () => {
    // helper 崩溃：收养者换成父 daemon，父进程仍必须核实并收敛这个脱离会话的后代（Linux）。
    if (process.platform !== 'linux') {
      return
    }
    const rootPidFile = join(workdir, 'root.pid')
    const escapedPidFile = join(workdir, 'escaped.pid')
    const managed = await start('escape-fork.mjs', rootPidFile, escapedPidFile)

    const root = await waitForPid(rootPidFile)
    const escaped = await waitForPid(escapedPidFile)
    expect(isAlive(escaped)).toBe(true)

    process.kill(managed.pid, 'SIGKILL')
    await managed.terminate()
    expect(await awaitGone(root)).toBe(true)
    expect(await awaitGone(escaped)).toBe(true)
  }, TEST_TIMEOUT)

  test('an unregistered setsid descendant is an unverified boundary on macOS', async () => {
    // macOS 没有子进程收养者：脱离会话的后代被重新挂到 launchd，native 无法核实其归属——这是明确暴露的限制，不是静默泄漏。
    if (process.platform !== 'darwin') {
      return
    }
    const rootPidFile = join(workdir, 'root.pid')
    const escapedPidFile = join(workdir, 'escaped.pid')
    const managed = await start('escape-fork.mjs', rootPidFile, escapedPidFile)

    const escaped = await waitForPid(escapedPidFile)
    await managed.terminate()
    expect(isAlive(escaped)).toBe(true)
    process.kill(escaped, 'SIGKILL')
    expect(await awaitGone(escaped)).toBe(true)
  }, TEST_TIMEOUT)

  test('descendants are still converged after the scope helper crashes', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const managed = await start('fork-hold.mjs', rootPidFile, childPidFile)

    const root = await waitForPid(rootPidFile)
    const child = await waitForPid(childPidFile)
    expect(isAlive(root)).toBe(true)
    expect(isAlive(child)).toBe(true)

    // 直接强杀 helper（会话 leader）：命令与后代仍在运行，收敛必须由父进程重新枚举并核验后完成。
    process.kill(managed.pid, 'SIGKILL')
    await managed.terminate()
    expect(await awaitGone(root)).toBe(true)
    expect(await awaitGone(child)).toBe(true)
  }, TEST_TIMEOUT)

  test('an unknown root pid cannot be tracked', async () => {
    await expect(supervisor.trackDescendant(2_000_000_000, process.pid)).rejects.toThrow(/no managed process/)
  })

  test('a non-descendant pid cannot be tracked', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const managed = await start('hold.mjs', rootPidFile)
    await waitForPid(rootPidFile)
    // 测试进程不是 helper 的后代：登记必须因血缘核验失败而拒绝，绝不把陌生会话纳入收敛。
    await expect(supervisor.trackDescendant(managed.pid, process.pid)).rejects.toThrow(/not a descendant/)
    await managed.terminate()
  }, TEST_TIMEOUT)

  test('a naturally finished scope is released from the supervisor', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const managed = await start('eof.mjs', rootPidFile, '7')
    await waitForPid(rootPidFile)
    managed.stdin.end()
    const exit = await managed.exit
    expect(exit.exitCode).toBe(7)
    await managed.terminate()
    // 自然退出且 helper 已证明收敛的记录会被释放：再次登记同一个 root 必须变成「没有这个 managed 进程」。
    const deadline = Date.now() + 5_000
    let released = false
    while (Date.now() < deadline && !released) {
      released = await supervisor.trackDescendant(managed.pid, process.pid).then(
        () => false,
        (error: Error) => /no managed process/.test(error.message),
      )
    }
    expect(released).toBe(true)
  }, TEST_TIMEOUT)

  test('a command that cannot be started resolves exit without a code', async () => {
    const managed = await supervisor.start({ executable: '/no/such/kk-studio-binary', args: [], cwd: workdir })
    pending.push(managed)
    const exit = await managed.exit
    expect(exit.exitCode).toBeNull()
    await managed.terminate()
  }, TEST_TIMEOUT)

  test('start after close fails closed', async () => {
    const local = createProcessSupervisor()
    await local.close()
    const command = fixtureCommand('hold.mjs', join(workdir, 'x.pid'))
    await expect(local.start({ executable: command.executable, args: command.args, cwd: workdir })).rejects.toThrow(/closed/)
  })

  test('helper-path resolves to an existing helper module', async () => {
    const { resolveHelperPath } = await import('../../src/native/helper-path.js')
    const path = resolveHelperPath()
    expect(path.endsWith('helper.js') || path.endsWith('helper.ts')).toBe(true)
    expect((await import('node:fs')).existsSync(path)).toBe(true)
  })

  test('a scope helper that dies before publishing the scope fails closed', async () => {
    const command = fixtureCommand('hold.mjs', join(workdir, 'dying.pid'))
    const managed = await supervisor
      .start({ executable: command.executable, args: command.args, cwd: workdir, env: { NODE_OPTIONS: '--kk-studio-not-a-real-flag' } })
      .catch((error: Error) => error)
    expect(managed).toBeInstanceOf(Error)
  }, TEST_TIMEOUT)

  test('a signal aborted after the scope is published cancels before the command runs', async () => {
    const rootPidFile = join(workdir, 'aborted.pid')
    const controller = new AbortController()
    const command = fixtureCommand('hold.mjs', rootPidFile)
    const promise = supervisor.start({ executable: command.executable, args: command.args, cwd: workdir, signal: controller.signal })
    controller.abort()
    await expect(promise).rejects.toThrow(/cancelled/)
    await expect(waitForFile(rootPidFile, 200)).rejects.toThrow()
  }, TEST_TIMEOUT)

  test('trackDescendant is a no-op for a child already in the managed session', async () => {
    const rootPidFile = join(workdir, 'root.pid')
    const childPidFile = join(workdir, 'child.pid')
    const managed = await start('fork-hold.mjs', rootPidFile, childPidFile)
    const root = await waitForPid(rootPidFile)
    await supervisor.trackDescendant(managed.pid, root)
    await managed.terminate()
    expect(await awaitGone(root)).toBe(true)
  }, TEST_TIMEOUT)

  function collect(stream: NodeJS.ReadableStream): () => string {
    let buffer = ''
    stream.on('data', (chunk: Buffer | string) => {
      buffer += chunk
    })
    return () => buffer
  }

  function readLine(stream: NodeJS.ReadableStream): Promise<string> {
    return new Promise((resolve, reject) => {
      let buffer = ''
      const timer = setTimeout(() => {
        stream.off('data', onData)
        reject(new Error('timed out reading a line'))
      }, STREAM_TIMEOUT)
      const onData = (chunk: Buffer | string): void => {
        buffer += chunk
        const newline = buffer.indexOf('\n')
        if (newline >= 0) {
          clearTimeout(timer)
          stream.off('data', onData)
          resolve(buffer.slice(0, newline))
        }
      }
      stream.on('data', onData)
    })
  }
})
