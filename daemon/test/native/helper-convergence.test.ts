import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, test } from 'vitest'
import { resolveHelperPath } from '../../src/native/helper-path.js'
import * as state from '../../src/native/state.js'

/**
 * 直接拉起编译后的 helper，验证「许可之前失败等价于用户命令没有副作用」这条核心约定，以及
 * 父进程消失（IPC `disconnect`）与温和信号都会触发真实的会话收敛。
 */
const TEST_TIMEOUT = 30_000

function waitForFile(dir: string, name: string, budget = 20_000): Promise<void> {
  const deadline = Date.now() + budget
  return new Promise((resolve, reject) => {
    const poll = (): void => {
      if (state.read(dir, name) !== null) {
        resolve()
      } else if (Date.now() >= deadline) {
        reject(new Error(`timed out waiting for ${name}`))
      } else {
        setTimeout(poll, 5)
      }
    }
    poll()
  })
}

function exitCode(child: ChildProcess): Promise<number | null> {
  // 用 'exit' 而非 'close'：父进程与 helper 的 IPC 通道断开后，子进程句柄不再阻止事件循环，
  // 'close'（等待 stdio 全部关闭）可能不再派发，但退出码已经确定。
  return new Promise((resolve) => child.on('exit', (code) => resolve(code)))
}

describe.skipIf(process.platform === 'win32')('native scope helper convergence', () => {
  const stateDirs: string[] = []
  const children: ChildProcess[] = []

  afterEach(async () => {
    for (const child of children.splice(0)) {
      if (child.exitCode === null && child.signalCode === null) {
        child.kill('SIGKILL')
        await new Promise((resolve) => child.on('close', resolve))
      }
    }
    for (const dir of stateDirs.splice(0)) {
      state.removeStateDir(dir)
    }
  })

  async function spawnHelper(useIpc: boolean): Promise<{ child: ChildProcess; dir: string }> {
    const dir = await state.createStateDir()
    stateDirs.push(dir)
    state.writeLaunch(dir, { cwd: tmpdir(), args: [process.execPath, '-e', 'setInterval(() => {}, 1000)'] })
    const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), dir], {
      stdio: useIpc ? ['pipe', 'pipe', 'pipe', 'ipc'] : 'ignore',
    })
    children.push(child)
    await waitForFile(dir, state.SCOPE_FILE)
    return { child, dir }
  }

  test('a SIGTERM before the permit converges the scope and never runs the command', async () => {
    const { child, dir } = await spawnHelper(false)
    child.kill('SIGTERM')
    expect(await exitCode(child)).toBe(0)
    expect(state.readCleanup(dir)).toBe(true)
    // 许可之前没有副作用：命令从未运行，也就没有退出事实。
    expect(state.read(dir, state.EXIT_FILE)).toBeNull()
  }, TEST_TIMEOUT)

  test('parent death (IPC disconnect) converges the scope before the command runs', async () => {
    const { child, dir } = await spawnHelper(true)
    child.disconnect()
    expect(await exitCode(child)).toBe(0)
    expect(state.readCleanup(dir)).toBe(true)
    expect(state.read(dir, state.EXIT_FILE)).toBeNull()
  }, TEST_TIMEOUT)

  test('an empty command fails closed after the scope is permitted', async () => {
    const dir = await state.createStateDir()
    stateDirs.push(dir)
    state.writeLaunch(dir, { cwd: tmpdir(), args: [] })
    const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), dir], { stdio: 'ignore' })
    children.push(child)
    await waitForFile(dir, state.SCOPE_FILE)
    state.publish(dir, state.PERMIT_FILE, 'permit')
    expect(await exitCode(child)).toBe(1)
    // 对外只发布固定阶段代码，绝不回显命令路径或参数。
    expect(state.read(dir, state.ERROR_FILE)).toBe('helper-posix-failure')
  }, TEST_TIMEOUT)

  test('a command that exits is published as an exit report before convergence', async () => {
    const dir = await state.createStateDir()
    stateDirs.push(dir)
    state.writeLaunch(dir, { cwd: tmpdir(), args: [process.execPath, '-e', 'process.exit(21)'] })
    const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), dir], { stdio: 'ignore' })
    children.push(child)
    await waitForFile(dir, state.SCOPE_FILE)
    state.publish(dir, state.PERMIT_FILE, 'permit')
    expect(await exitCode(child)).toBe(0)
    expect(state.readExit(dir)).toEqual({ exitCode: 21, signal: null })
    expect(existsSync(join(dir, state.CLEANUP_FILE))).toBe(true)
  }, TEST_TIMEOUT)
})
