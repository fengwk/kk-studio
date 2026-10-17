import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, onTestFailed, test } from 'vitest'
import { PosixProcessSupervisor } from '../../src/native/posix-supervisor.js'
import type { ManagedProcess, ProcessSupervisor } from '../../src/contracts.js'
import { awaitGone, fixtureCommand, fixturePath, isAlive, waitForFile, waitForPid } from './process-test-util.js'

const TEST_TIMEOUT = 30_000
const STREAM_TIMEOUT = 10_000

// 执行范围的跨平台真实进程验收：用只依赖 Node 的夹具造出真实父子/孙进程层级，并用原生 pid 断言收敛。
//
// 这里直接构造 POSIX 实现：Linux 与 macOS 共用它。工厂分派由 platform-capabilities 覆盖。
// macOS 没有子进程收养者，未登记且故意 setsid 的后代不在会话生命周期内，相关用例只在 Linux 上断言。
describe.skipIf(process.platform === 'win32')('native process supervisor (POSIX)', () => {
  let workdir: string
  let supervisor: ProcessSupervisor
  const pending: ManagedProcess[] = []

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-proc-'))
    supervisor = new PosixProcessSupervisor()
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
    // macOS 没有子进程收养者：未登记且故意脱离的后代不属于会话生命周期，仍须支持普通进程。
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

  test.each(process.platform === 'linux' ? ['escape-fork.mjs', 'zombie-leader.mjs'] : ['escape-fork.mjs'])(
    'the scope helper converges everything when its parent daemon is killed (%s)', async (fixture) => {
    // 父进程消失必须由 helper 自己触发收敛：专用 IPC 通道的内核 EOF，不依赖心跳或猜父 pid。
    if (process.platform === 'win32') {
      return
    }
    const { resolveHelperPath } = await import('../../src/native/helper-path.js')
    const stateDir = mkdtempSync(join(tmpdir(), 'kk-studio-native-owner-'))
    const helperPidFile = join(workdir, 'helper.pid')
    const rootPidFile = join(workdir, 'root.pid')
    const escapedPidFile = join(workdir, 'escaped.pid')
    const command = fixtureCommand(fixture, rootPidFile, escapedPidFile)
    const owner = spawn(
      process.execPath,
      [fixturePath('parent-owner.mjs'), stateDir, resolveHelperPath(), helperPidFile, command.executable, ...command.args],
      { stdio: 'ignore' },
    )
    // 在 spawn 时注册：晚挂监听可能错过已经发生的 close，而 exit 早于 stdio 拆除。
    const ownerClosed = new Promise<void>((resolve) => {
      owner.on('close', () => resolve())
    })
    // 诊断状态：只在本用例失败或超时时由 onTestFailed 读取，不参与等待与判定。
    let phase = 'spawn-owner'
    const observed: { helper: number | null; root: number | null; escaped: number | null } = {
      helper: null,
      root: null,
      escaped: null,
    }
    onTestFailed(() => {
      const present = (name: string): string =>
        existsSync(join(stateDir, name)) ? 'present' : 'absent'
      // 只接受字面 true/false（cleanup）或固定阶段代码（error），其余只记 unexpected。
      const readRestricted = (name: string, accept: (value: string) => string): string => {
        if (!existsSync(join(stateDir, name))) {
          return 'absent'
        }
        try {
          return accept(readFileSync(join(stateDir, name), 'utf8').trim())
        } catch {
          return 'unreadable'
        }
      }
      const processState = (pid: number | null): { pid: number; alive: boolean } | null =>
        pid === null ? null : { pid, alive: isAlive(pid) }
      console.error(
        '[ownerdeath-diagnostic] ' +
          JSON.stringify({
            phase,
            owner: { exitCode: owner.exitCode, signalCode: owner.signalCode },
            helper: processState(observed.helper),
            root: processState(observed.root),
            escaped: processState(observed.escaped),
            state: {
              scope: present('scope'),
              permit: present('permit'),
              exit: present('exit'),
              cleanup: readRestricted('cleanup', (value) =>
                value === 'true' || value === 'false' ? value : 'unexpected',
              ),
              error: readRestricted('error', (value) =>
                /^helper-[a-z]+-failure$/.test(value) ? value : 'unexpected',
              ),
            },
          }),
      )
    })
    try {
      phase = 'wait-helper-pid'
      const helperPid = await waitForPid(helperPidFile)
      observed.helper = helperPid
      phase = 'wait-root-pid'
      const root = await waitForPid(rootPidFile)
      observed.root = root
      phase = 'wait-escaped-pid'
      const escaped = await waitForPid(escapedPidFile)
      observed.escaped = escaped
      if (fixture === 'zombie-leader.mjs') {
        phase = 'wait-zombie-leader'
        await waitForFile(`${rootPidFile}.leader-dead`)
      }
      expect([helperPid, root, escaped].every(isAlive)).toBe(true)

      // 强杀所有者（daemon）：helper 与后代此刻都还在，没有任何登记窗口。
      phase = 'kill-owner'
      process.kill(owner.pid as number, 'SIGKILL')
      phase = 'await-owner-exit'
      await ownerClosed

      phase = 'await-gone-helper'
      expect(await awaitGone(helperPid, 30_000)).toBe(true)
      phase = 'await-gone-root'
      expect(await awaitGone(root, 30_000)).toBe(true)
      phase = 'await-gone-escaped'
      if (process.platform === 'linux') {
        expect(await awaitGone(escaped, 30_000)).toBe(true)
      } else {
        process.kill(escaped, 'SIGKILL')
        await awaitGone(escaped)
      }
    } finally {
      if (owner.exitCode === null && owner.signalCode === null) {
        owner.kill('SIGKILL')
      }
      // 等待 spawn 时注册的 close：句柄真正释放后才删除状态目录，不靠晚挂监听猜时机。
      await ownerClosed
      rmSync(stateDir, { recursive: true, force: true })
    }
  }, TEST_TIMEOUT)

  test('an unrelated child owned by the daemon is never converged', async () => {
    // 基线之外才是本次调用可能收养的孤儿：不属于任何 managed 的既有直接子进程绝不能被收敛。
    if (process.platform !== 'linux') {
      return
    }
    const unrelatedPidFile = join(workdir, 'unrelated.pid')
    const unrelated = spawn(process.execPath, [fixturePath('hold.mjs'), unrelatedPidFile], { stdio: 'ignore' })
    const unrelatedPid = await waitForPid(unrelatedPidFile)

    const rootPidFile = join(workdir, 'root.pid')
    const escapedPidFile = join(workdir, 'escaped.pid')
    const managed = await start('escape-fork.mjs', rootPidFile, escapedPidFile)
    const escaped = await waitForPid(escapedPidFile)

    process.kill(managed.pid, 'SIGKILL')
    await managed.terminate()
    expect(await awaitGone(escaped)).toBe(true)
    expect(isAlive(unrelatedPid)).toBe(true)

    process.kill(unrelatedPid, 'SIGKILL')
    await awaitGone(unrelatedPid)
    unrelated.unref()
  }, TEST_TIMEOUT)

  test('terminating one scope does not touch another live scope', async () => {
    // 多范围归属不可精确证明时，至少保证「其它活动范围 + 未登记进任何范围的直接子进程」不受影响。
    if (process.platform !== 'linux') {
      return
    }
    const bRootPidFile = join(workdir, 'b-root.pid')
    const bChildPidFile = join(workdir, 'b-child.pid')
    const scopeB = await start('fork-hold.mjs', bRootPidFile, bChildPidFile)
    const rootB = await waitForPid(bRootPidFile)
    const childB = await waitForPid(bChildPidFile)
    expect([rootB, childB].every(isAlive)).toBe(true)

    const aRootPidFile = join(workdir, 'a-root.pid')
    const aEscapedPidFile = join(workdir, 'a-escaped.pid')
    const scopeA = await start('escape-fork.mjs', aRootPidFile, aEscapedPidFile)
    const escapedA = await waitForPid(aEscapedPidFile)

    process.kill(scopeA.pid, 'SIGKILL')
    await scopeA.terminate()
    expect(await awaitGone(escapedA)).toBe(true)
    // 另一个活动范围完全不受影响。
    expect(isAlive(rootB)).toBe(true)
    expect(isAlive(childB)).toBe(true)

    await scopeB.terminate()
    expect(await awaitGone(rootB)).toBe(true)
    expect(await awaitGone(childB)).toBe(true)
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
    const local = new PosixProcessSupervisor()
    await local.close()
    const command = fixtureCommand('hold.mjs', join(workdir, 'x.pid'))
    await expect(local.start({ executable: command.executable, args: command.args, cwd: workdir })).rejects.toThrow(/closed/)
  })

  test('helper-path resolves only to the compiled helper build output', async () => {
    const { resolveHelperPath } = await import('../../src/native/helper-path.js')
    const path = resolveHelperPath()
    // helper 只运行编译产物：源码开发态也必须解析到 daemon/dist/native/helper.js，绝不回退 .ts。
    expect(path.endsWith('helper.js')).toBe(true)
    expect(path).toMatch(/dist[/\\]native[/\\]helper\.js$/)
    expect((await import('node:fs')).existsSync(path)).toBe(true)
  })

  test('helper-path fails closed when no compiled helper exists', async () => {
    const { resolveHelperPath } = await import('../../src/native/helper-path.js')
    // 既没有同目录 helper.js，也没有 dist 构建产物：显式失败并提示先构建，绝不退回源码态执行。
    expect(() => resolveHelperPath(new URL('./__missing_helper_dir__/', import.meta.url))).toThrow(/build output is missing/)
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
