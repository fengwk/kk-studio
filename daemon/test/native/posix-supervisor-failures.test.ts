import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { ProcessScopeNotConvergedError } from '../../src/contracts.js'
import { PosixManagedProcess, PosixProcessSupervisor } from '../../src/native/posix-supervisor.js'
import * as state from '../../src/native/state.js'

/**
 * POSIX 父进程侧状态机的失败关闭路径：范围 id 不可信、helper 未发布范围就退出、以及启动前
 * 写入启动规格失败，都必须显式失败并保留/清理正确的证据。
 */
const children: ChildProcessWithoutNullStreams[] = []
const stateDirs: string[] = []

function fakeHelper(lifetimeMillis = 400): ChildProcessWithoutNullStreams {
  const child = spawn(process.execPath, ['-e', `setTimeout(() => process.exit(0), ${lifetimeMillis})`], {
    stdio: ['pipe', 'pipe', 'pipe', 'ipc'],
  }) as ChildProcessWithoutNullStreams
  children.push(child)
  return child
}

async function dir(): Promise<string> {
  const value = await state.createStateDir()
  stateDirs.push(value)
  return value
}

function managed(stateDir: string, helper: ChildProcessWithoutNullStreams): PosixManagedProcess {
  // baseline=null 与 adoptsOrphans=false：本文件只驱动父进程侧状态机，不参与真实孤儿收养。
  return new PosixManagedProcess(stateDir, helper, undefined, null, () => [], false)
}

afterEach(async () => {
  for (const child of children.splice(0)) {
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGKILL')
    }
  }
  for (const value of stateDirs.splice(0)) {
    state.removeStateDir(value)
  }
})

describe.skipIf(process.platform === 'win32')('posix supervisor failure branches', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-posix-sup-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('an invalid published scope id fails closed', async () => {
    const stateDir = await dir()
    state.publish(stateDir, state.SCOPE_FILE, 'not-a-number')
    const scope = managed(stateDir, fakeHelper())
    await expect(scope.establish()).rejects.toThrow(/invalid scope id/)
    await scope.close()
  })

  test('a scope id that is not the helper session fails closed', async () => {
    const stateDir = await dir()
    const helper = fakeHelper()
    state.publish(stateDir, state.SCOPE_FILE, String((helper.pid ?? 0) + 1))
    const scope = managed(stateDir, helper)
    await expect(scope.establish()).rejects.toThrow(/not its own session/)
    await scope.close()
  })

  test('a helper that died before the scope reports its fixed failure phase', async () => {
    const stateDir = await dir()
    state.publish(stateDir, state.ERROR_FILE, 'helper-posix-failure')
    const helper = fakeHelper(50)
    const scope = managed(stateDir, helper)
    await new Promise((resolve) => helper.on('close', resolve))
    await expect(scope.establish()).rejects.toThrow(/failed during its posix phase/)
  })

  test('a helper spawn error releases IPC ownership without discarding buffered output', async () => {
    // 控制事件顺序，验证 error 终态释放父侧资源且不清空已经写入的输出。
    const helper = Object.assign(new EventEmitter(), {
      stdin: new PassThrough(),
      stdout: new PassThrough(),
      stderr: new PassThrough(),
      connected: true,
      disconnect: vi.fn(),
      unref: vi.fn(),
    })
    helper.stdout.end('buffered stdout')
    helper.stderr.end('buffered stderr')
    const scope = managed(await dir(), helper as unknown as ChildProcessWithoutNullStreams)
    helper.emit('error', new Error('opaque spawn detail'))
    await expect(scope.establish()).rejects.toThrow(/before establishing the OS scope/)
    await expect(scope.exit).resolves.toEqual({ exitCode: null, signal: null })
    await scope.close()
    await scope.settled
    expect(helper.stdin.destroyed).toBe(true)
    expect(helper.stdout.read().toString()).toBe('buffered stdout')
    expect(helper.stderr.read().toString()).toBe('buffered stderr')
    expect(helper.disconnect).toHaveBeenCalledOnce()
    expect(helper.unref).toHaveBeenCalledOnce()
    helper.stdout.destroy()
    helper.stderr.destroy()
  })

  test('a failed start whose scope converged rejects with the original error only', async () => {
    // start 契约：清理已证明收敛时只抛原始失败（不是 ProcessScopeNotConvergedError），调用方据此可以确认退出。
    const establish = vi
      .spyOn(PosixManagedProcess.prototype, 'establish')
      .mockRejectedValue(new Error('establish failed'))
    const supervisor = new PosixProcessSupervisor()
    try {
      const failure = await supervisor
        .start({ executable: process.execPath, args: ['-e', ''], cwd: tmpdir() })
        .then(
          () => null,
          (error: unknown) => error,
        )
      expect(failure).toBeInstanceOf(Error)
      expect(failure).not.toBeInstanceOf(ProcessScopeNotConvergedError)
      expect((failure as Error).message).toBe('establish failed')
    } finally {
      establish.mockRestore()
      await supervisor.close()
    }
  })

  test('a failed start whose cleanup cannot be proven converged rejects with the typed error', async () => {
    // 先真实收尾（杀掉 helper，不泄漏进程），再模拟收尾无法证明收敛：此时必须抛 ProcessScopeNotConvergedError。
    const establish = vi
      .spyOn(PosixManagedProcess.prototype, 'establish')
      .mockRejectedValue(new Error('establish failed'))
    const realClose = PosixManagedProcess.prototype.close
    const close = vi
      .spyOn(PosixManagedProcess.prototype, 'close')
      .mockImplementation(async function (this: PosixManagedProcess) {
        await realClose.call(this)
        throw new Error('cleanup could not be proven')
      })
    const supervisor = new PosixProcessSupervisor()
    try {
      await expect(
        supervisor.start({ executable: process.execPath, args: ['-e', ''], cwd: tmpdir() }),
      ).rejects.toThrow(ProcessScopeNotConvergedError)
    } finally {
      establish.mockRestore()
      close.mockRestore()
      await supervisor.close()
    }
  })

  test('a launch spec that cannot be written fails closed before spawning the helper', async () => {
    const supervisor = new PosixProcessSupervisor()
    await expect(
      supervisor.start({ executable: process.execPath, args: ['a\0b'], cwd: tmpdir() }),
    ).rejects.toThrow(/NUL/)
    await supervisor.close()
  })
})
