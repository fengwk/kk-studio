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
 * 编译产物 CLI 回归：以真实子进程运行 `dist/main.js`，用真实 OS 信号 SIGTERM 停机。
 *
 * 这是对「同一份编译源码」的端到端证据：控制面走真实 loopback WSS，进程约束走真实 native
 * supervisor，停机由 `child.kill('SIGTERM')` 投递真实操作系统信号。运行前必须先构建
 * （`npm run build`）；本用例不读取 TS 源码，也不做源码 fallback。
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
    'stops on a real OS SIGTERM, reaps the in-flight process and releases the data lock',
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

      // 真实操作系统信号。
      child.kill('SIGTERM')
      const [code, signal] = await promiseFromExit(child)
      expect(signal).toBeNull()
      expect(code).toBe(0)

      // 在途进程随 native 范围收敛，数据目录锁也已释放。
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
  if (child.exitCode === null && child.signalCode === null) {
    child.kill('SIGKILL')
    await once(child, 'exit')
  }
}
