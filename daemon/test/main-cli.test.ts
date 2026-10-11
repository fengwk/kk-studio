import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'
import { afterEach, describe, expect, it } from 'vitest'

import * as native from '../src/native/index.js'
import { waitFor } from './helpers.js'
import { awaitGone, isAlive } from './native/process-test-util.js'
import {
  TOKEN,
  createControlServer,
  invoke,
  longRunningNodeCommand,
  writeConfig,
} from './support/daemon-control-server.js'

/**
 * 编译产物 CLI 回归：以真实子进程运行 `dist/main.js`，用真实停机路径结束。
 *
 * 这是对「同一份编译源码」的端到端证据：控制面走真实 loopback WSS，进程约束走真实 native supervisor。
 * 停机的事实边界：POSIX 上 `child.kill('SIGTERM')` 派发真实 OS 信号、main 正常退出 0；Windows 上 Node 的
 * `kill('SIGTERM')` 是强制 `TerminateProcess`，不会派发 JS 信号监听，因此 Windows 用真实控制面
 * `ERROR/REGISTRATION_REJECTED` 触发正常停机（退出 1）。两条路径都必须收敛在途进程并释放目录锁。
 * 运行前必须先构建（`npm run build`）；本用例不读取 TS 源码，也不做源码 fallback。
 */

const MAIN_ENTRY = fileURLToPath(new URL('../dist/main.js', import.meta.url))

const cleans: Array<() => void | Promise<void>> = []
afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
})

describe('daemon compiled CLI', () => {
  it(
    'stops and releases the data lock: POSIX via a real OS SIGTERM, Windows via a graceful control abort',
    async () => {
      expect(existsSync(MAIN_ENTRY), `build the daemon first: ${MAIN_ENTRY}`).toBe(true)
      const fixture = await createControlServer()
      cleans.push(() => fixture.dispose())
      await writeConfig(fixture)

      const child = spawn(process.execPath, [MAIN_ENTRY, '--config', fixture.configPath], {
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      const stdout: string[] = []
      const stderr: string[] = []
      child.stdout?.on('data', (chunk: Buffer) => stdout.push(chunk.toString('utf8')))
      child.stderr?.on('data', (chunk: Buffer) => stderr.push(chunk.toString('utf8')))
      cleans.push(() => reap(child))

      await waitFor(() => fixture.received.some((envelope) => envelope.messageType === 'READY'), 30_000)

      // 真实长进程写回自己的真实 OS PID。
      const pidFile = join(fixture.root, 'cli.pid')
      fixture.send('INVOKE', {
        invocationId: 'cli-long',
        payload: invoke('process.exec', { command: longRunningNodeCommand(pidFile), workdir: fixture.root }, 120_000),
      })
      await waitFor(() => existsSync(pidFile), 30_000)
      const payloadPid = Number(readFileSync(pidFile, 'utf8').trim())
      expect(isAlive(payloadPid)).toBe(true)

      // 先建立退出监听再触发停机：极快退出也绝不丢事件。
      const exited = promiseFromExit(child)
      if (process.platform === 'win32') {
        // Windows 的 SIGTERM 是强制终止，不派发 JS 监听；用真实控制面触发正常停机。
        fixture.send('ERROR', { payload: { code: 'REGISTRATION_REJECTED', message: 'no lease' } })
        const [code, signal] = await exited
        expect(signal).toBeNull()
        expect(code).toBe(1)
      } else {
        child.kill('SIGTERM')
        const [code, signal] = await exited
        expect(signal).toBeNull()
        expect(code).toBe(0)
      }

      // 在途进程随 native 范围收敛，数据目录锁也已释放：两条停机路径都必须成立。
      expect(await awaitGone(payloadPid, 30_000)).toBe(true)
      const lock = await native.acquireDirectoryLock(fixture.dataDir)
      await lock.close()

      // 凭据绝不进入 CLI 输出。
      expect(stdout.join('')).not.toContain(TOKEN)
      expect(stderr.join('')).not.toContain(TOKEN)
    },
    120_000,
  )
})

function promiseFromExit(child: ChildProcess): Promise<[number | null, NodeJS.Signals | null]> {
  return new Promise((resolve) => {
    child.once('exit', (code, signal) => resolve([code, signal]))
  })
}

async function reap(child: ChildProcess): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) {
    return
  }
  // 失败路径上必须让子进程真正收敛：强杀后等它退出，仍不退出就显式失败。
  child.kill('SIGKILL')
  await once(child, 'exit')
  if (child.exitCode === null && child.signalCode === null) {
    throw new Error('the daemon CLI child did not exit after SIGKILL')
  }
}
