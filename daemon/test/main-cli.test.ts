import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { once } from 'node:events'
import { afterEach, describe, expect, it } from 'vitest'

import { DAEMON_VERSION } from '../src/config.js'
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
 * 编译产物 CLI 回归：以真实子进程运行 `dist/main.js`，不读取 TS 源码，也不做源码 fallback。
 *
 * <p>信息命令（`--help` / `--version` / `--check-config`）与平台无关，在所有平台都对真实编译产物断言。正常 READY
 * 启动在 Linux、macOS 与 Windows 上都走真实控制面：POSIX 用真实 OS `SIGTERM` 停机，Windows 用控制面
 * `ERROR/REGISTRATION_REJECTED` 触发正常停机（Node 的 `kill('SIGTERM')` 在 Windows 上是强制 `TerminateProcess`）。
 * macOS 缺少子进程收养者不阻止这次启动。
 */
const DIST_DIR = fileURLToPath(new URL('../dist', import.meta.url))
const MAIN_ENTRY = join(DIST_DIR, 'main.js')
const PACKAGE_VERSION = JSON.parse(readFileSync(join(DIST_DIR, '..', 'package.json'), 'utf8')).version

const cleans: Array<() => void | Promise<void>> = []
afterEach(async () => {
  for (const clean of cleans.splice(0).reverse()) {
    await clean()
  }
})

interface CliResult {
  code: number | null
  signal: NodeJS.Signals | null
  stdout: string
  stderr: string
}

/** 运行真实编译产物并完整收敛：成功退出或真实信号结束都必须被等待到。 */
async function runCli(args: string[], entry: string = MAIN_ENTRY): Promise<CliResult> {
  expect(existsSync(entry), `build the daemon first: ${entry}`).toBe(true)
  return runChild([entry, ...args])
}

/** 以真实子进程运行给定 argv（argv[0] 由调用方提供），完整收集输出并等待退出。 */
async function runChild(argv: string[]): Promise<CliResult> {
  const child = spawn(process.execPath, argv, { stdio: ['ignore', 'pipe', 'pipe'] })
  const stdout: string[] = []
  const stderr: string[] = []
  child.stdout?.on('data', (chunk: Buffer) => stdout.push(chunk.toString('utf8')))
  child.stderr?.on('data', (chunk: Buffer) => stderr.push(chunk.toString('utf8')))
  cleans.push(() => reap(child))
  const [code, signal] = await promiseFromExit(child)
  return { code, signal, stdout: stdout.join(''), stderr: stderr.join('') }
}

describe('daemon compiled CLI information commands', () => {
  it('prints usage and version from the compiled entry', async () => {
    const help = await runCli(['--help'])
    expect(help.code).toBe(0)
    expect(help.stdout).toContain('Usage: kk-studio-daemon --config')

    const version = await runCli(['--version'])
    expect(version.code).toBe(0)
    expect(version.stdout).toContain(`kk-studio-daemon ${DAEMON_VERSION}`)
  })

  it('reports the packaged daemon version from the compiled entry', async () => {
    // 真实 CLI 输出必须与 npm 包版本一致，避免测试只做常量自比较。
    const version = await runCli(['--version'])
    expect(version.code).toBe(0)
    expect(version.stdout).toBe(`kk-studio-daemon ${PACKAGE_VERSION}\n`)
  })

  it('validates --check-config from the compiled entry with safe diagnostics', async () => {
    const fixture = await createControlServer()
    cleans.push(() => fixture.dispose())
    await writeConfig(fixture)

    const ok = await runCli(['--check-config', fixture.configPath])
    expect(ok.code).toBe(0)
    expect(ok.stdout).toContain('Daemon configuration is valid')

    const bad = await runCli(['--check-config', join(fixture.root, 'missing.json')])
    expect(bad.code).toBe(1)
    expect(bad.stderr).toContain('Invalid daemon configuration:')
    expect(bad.stderr).not.toContain(TOKEN)
  })
})

/** 在带空格名字的临时目录里放一个指向编译产物目录的 symlink，返回其 lexical 入口路径。 */
function linkedEntryUnderSpaceDir(): { entry: string; cleanup: () => void } {
  const root = mkdtempSync(join(tmpdir(), 'daemon symlink entry '))
  const link = join(root, 'linked dist')
  // junction 在 Windows 上创建目录链接无需特权；POSIX 用目录 symlink。
  symlinkSync(DIST_DIR, link, process.platform === 'win32' ? 'junction' : 'dir')
  return {
    entry: join(link, 'main.js'),
    cleanup: () => rmSync(root, { recursive: true, force: true }),
  }
}

/**
 * 经符号链接目录执行编译产物的回归。
 *
 * `process.argv[1]` 保留调用方给出的 lexical 路径，而 `import.meta.url` 是 Node 解析 symlink 后的 canonical 路径；
 * 入口判定必须把两侧归一化到同一 realpath，否则会静默 `exit 0` 且 stdout 为空。这里让 lexical 与 canonical 必然不同
 * （带空格的链接目录名），用真实子进程覆盖 `--version` / `--help` 两个信息入口。
 */
describe('daemon compiled CLI through a symlinked entry directory', () => {
  it('runs information commands when invoked via a symlinked directory path', async () => {
    expect(existsSync(MAIN_ENTRY), `build the daemon first: ${MAIN_ENTRY}`).toBe(true)
    const linked = linkedEntryUnderSpaceDir()
    cleans.push(linked.cleanup)

    // 前提：传入的 lexical 入口与 Node 解析出的真实路径确实不同，否则本用例无法证明入口判定已归一化。
    expect(linked.entry).not.toBe(realpathSync(linked.entry))
    expect(realpathSync(linked.entry)).toBe(realpathSync(MAIN_ENTRY))

    const version = await runCli(['--version'], linked.entry)
    expect(version.code).toBe(0)
    expect(version.stdout).toContain(`kk-studio-daemon ${DAEMON_VERSION}`)

    const help = await runCli(['--help'], linked.entry)
    expect(help.code).toBe(0)
    expect(help.stdout).toContain('Usage: kk-studio-daemon --config')
  })
})

/**
 * import 编译入口不产生副作用：以真实子进程加载 dist/main.js，而 `process.argv[1]` 是另一个存在的启动脚本，
 * 入口判定必须为假、不得执行 main。
 */
describe('daemon compiled entry import side effects', () => {
  it('does not run main when the entry module is imported by another script', async () => {
    expect(existsSync(MAIN_ENTRY), `build the daemon first: ${MAIN_ENTRY}`).toBe(true)
    const root = mkdtempSync(join(tmpdir(), 'daemon entry import '))
    cleans.push(() => rmSync(root, { recursive: true, force: true }))
    const launcher = join(root, 'launcher.mjs')
    writeFileSync(
      launcher,
      `await import(${JSON.stringify(pathToFileURL(MAIN_ENTRY).href)})\nprocess.stdout.write('imported\\n')\n`,
      'utf8',
    )

    const result = await runChild([launcher])
    expect(result.code).toBe(0)
    expect(result.stdout).toBe('imported\n')
    expect(result.stderr).toBe('')
  })
})

describe('daemon compiled CLI runtime', () => {
  it(
    'stops and releases the data lock: POSIX via a real OS SIGTERM, Windows via a graceful control abort',
    async () => {
      expect(existsSync(MAIN_ENTRY), `build the daemon first: ${MAIN_ENTRY}`).toBe(true)
      const fixture = await createControlServer()
      cleans.push(() => fixture.dispose())
      await writeConfig(fixture)

      const child = spawn(process.execPath, [MAIN_ENTRY, '--config', fixture.configPath], {
        stdio: ['ignore', 'pipe', 'pipe'],
        env: { ...process.env, http_proxy: '', https_proxy: '', no_proxy: '' },
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
