import { spawn, type ChildProcess } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { afterEach, describe, expect, it } from 'vitest'

import { awaitGone, isAlive } from '../native/process-test-util.js'
import { LONG_RUNNING_PID_SCRIPT, longRunningNodeCommand, unixPath } from './daemon-control-server.js'

/**
 * 长进程夹具 PID 文件的原子发布回归。
 *
 * <p>消费者在 `existsSync(pidFile)` 后立刻读取：如果夹具直接 writeFileSync 目标，就会在「文件已建、内容未写」的窗口
 * 读到空串，`Number('') === 0` 让存活断言假失败。夹具必须在临时文件写完后 rename 发布。
 *
 * <p>回归用真实 Node 子进程执行夹具脚本，并用 `--require` 预载一个只存在于测试内的 fs 探针记录真实 writeFileSync /
 * renameSync 序列与目标文件状态；旧实现会被同一组断言直接拒绝（负向对照）。
 */

/** 测试内私有 fs 探针源码，只写入临时目录，不进入生产或夹具代码。 */
const FS_SPY_SOURCE = `'use strict'
const fs = require('fs')
const logPath = process.env.KK_FS_SPY_LOG
const target = process.env.KK_FS_SPY_TARGET
const temporary = process.env.KK_FS_SPY_TEMPORARY
const originalWrite = fs.writeFileSync
const originalRename = fs.renameSync
const logFd = fs.openSync(logPath, 'a')
function record(event) { fs.writeSync(logFd, JSON.stringify(event) + '\\n') }
fs.writeFileSync = function (file, data, ...rest) {
  const path = String(file)
  record({ op: 'writeFileSync', path, directTarget: path === target, temporaryTarget: path === temporary, data: String(data) })
  return originalWrite.call(fs, file, data, ...rest)
}
fs.renameSync = function (from, to, ...rest) {
  const fromPath = String(from)
  const toPath = String(to)
  const targetExistedBefore = toPath === target ? fs.existsSync(target) : null
  const sourceExistedBefore = fs.existsSync(fromPath)
  const result = originalRename.call(fs, from, to, ...rest)
  record({ op: 'renameSync', from: fromPath, to: toPath, publishesTarget: toPath === target && fromPath === temporary, targetExistedBefore, sourceExistedBefore, targetExistsAfter: fs.existsSync(target) })
  return result
}
`

interface SpyEvent {
  op: 'writeFileSync' | 'renameSync'
  path?: string
  data?: string
  directTarget?: boolean
  temporaryTarget?: boolean
  from?: string
  to?: string
  publishesTarget?: boolean
  targetExistedBefore?: boolean | null
  sourceExistedBefore?: boolean
  targetExistsAfter?: boolean
}

const children: ChildProcess[] = []
const directories: string[] = []

afterEach(async () => {
  for (const child of children.splice(0)) {
    if (child.exitCode === null) {
      child.kill('SIGKILL')
    }
  }
  for (const directory of directories.splice(0)) {
    rmSync(directory, { recursive: true, force: true })
  }
})

function readEvents(logPath: string): SpyEvent[] {
  if (!existsSync(logPath)) {
    return []
  }
  return readFileSync(logPath, 'utf8')
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .map((line) => JSON.parse(line) as SpyEvent)
}

/**
 * 真实 spawn 一个 Node 子进程执行夹具脚本，等待 `waitForOp` 出现后返回真实写出的 PID 与探针记录。
 *
 * <p>等待是有界条件等待：观察到目标文件存在且对应 fs 调用已被探针记录即返回，不做固定时长 sleep。
 */
async function runFixtureScript(
  script: string,
  waitForOp: 'writeFileSync' | 'renameSync',
  timeoutMillis = 10_000,
): Promise<{ pid: number; events: SpyEvent[]; child: ChildProcess }> {
  const directory = mkdtempSync(join(tmpdir(), 'kk-pid-publication-'))
  directories.push(directory)
  const pidFile = join(directory, 'long.pid')
  const spyPath = join(directory, 'fs-spy.cjs')
  const logPath = join(directory, 'fs-spy.log')
  writeFileSync(spyPath, FS_SPY_SOURCE, 'utf8')
  const env: NodeJS.ProcessEnv = {
    ...process.env,
    KK_FS_SPY_LOG: logPath,
    KK_FS_SPY_TARGET: unixPath(pidFile),
    KK_FS_SPY_TEMPORARY: `${unixPath(pidFile)}.tmp`,
  }
  // 被观测的子进程不属于覆盖率范围，避免把父进程的 V8 覆盖率注入它。
  delete env.NODE_V8_COVERAGE
  const child = spawn(process.execPath, ['--require', spyPath, '-e', script, unixPath(pidFile)], {
    env,
    stdio: ['ignore', 'ignore', 'pipe'],
    windowsHide: true,
  })
  children.push(child)
  const stderr: string[] = []
  child.stderr?.on('data', (chunk: Buffer) => stderr.push(chunk.toString('utf8')))
  const deadline = Date.now() + timeoutMillis
  while (!existsSync(pidFile) || !readEvents(logPath).some((event) => event.op === waitForOp)) {
    if (child.exitCode !== null) {
      throw new Error(`夹具子进程在发布前退出：code=${child.exitCode} stderr=${stderr.join('')}`)
    }
    if (Date.now() > deadline) {
      throw new Error(`等待夹具 PID 发布超时：stderr=${stderr.join('')}`)
    }
    await sleep(5)
  }
  return { pid: Number(readFileSync(pidFile, 'utf8').trim()), events: readEvents(logPath), child }
}

describe('长进程夹具 PID 发布', () => {
  it(
    '原子发布真实 PID：先写临时文件再 rename，消费者不会读到空 PID',
    async () => {
      // 回归观察的脚本必须就是 longRunningNodeCommand 实际内嵌的那一段。
      const sample = longRunningNodeCommand(join(tmpdir(), 'example.pid'))
      expect(sample).toContain(LONG_RUNNING_PID_SCRIPT)
      expect(sample).toContain(unixPath(join(tmpdir(), 'example.pid')))

      const { pid, events, child } = await runFixtureScript(LONG_RUNNING_PID_SCRIPT, 'renameSync')
      // 真实子进程写回自己的真实 OS PID，且此刻存活。
      expect(pid).toBe(child.pid)
      expect(isAlive(pid)).toBe(true)

      const directTargetWrites = events.filter((event) => event.op === 'writeFileSync' && event.directTarget === true)
      const temporaryWrites = events.filter((event) => event.op === 'writeFileSync' && event.temporaryTarget === true)
      const publications = events.filter((event) => event.op === 'renameSync' && event.publishesTarget === true)
      expect(directTargetWrites).toHaveLength(0)
      expect(temporaryWrites).toHaveLength(1)
      expect(temporaryWrites[0]?.data).toBe(String(pid))
      expect(publications).toHaveLength(1)
      expect(publications[0]).toMatchObject({
        targetExistedBefore: false,
        sourceExistedBefore: true,
        targetExistsAfter: true,
      })

      // 负向对照：旧的直接写目标实现只会产生一次指向目标的 writeFileSync，且没有 rename——会被上面的断言拒绝。
      const legacy = await runFixtureScript(
        'require("fs").writeFileSync(process.argv[1],String(process.pid));setInterval(function(){},1000)',
        'writeFileSync',
      )
      expect(legacy.events.filter((event) => event.op === 'writeFileSync' && event.directTarget === true)).toHaveLength(1)
      expect(legacy.events.filter((event) => event.op === 'renameSync')).toHaveLength(0)

      // 长进程随 kill 真实收敛。
      child.kill('SIGKILL')
      expect(await awaitGone(pid, 10_000)).toBe(true)
      legacy.child.kill('SIGKILL')
      expect(await awaitGone(legacy.pid, 10_000)).toBe(true)
    },
    30_000,
  )
})
