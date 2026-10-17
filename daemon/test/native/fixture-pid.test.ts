import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

const probe = fileURLToPath(new URL('../resources/native/fixture-pid-probe.mjs', import.meta.url))

describe('native fixture PID publication', () => {
  let directory: string

  beforeEach(() => {
    directory = mkdtempSync(join(tmpdir(), 'kk-fixture-pid-'))
  })

  afterEach(() => {
    rmSync(directory, { recursive: true, force: true })
  })

  // 默认进程和 helper 的显式 pid 都必须先写临时文件，再一次性发布最终路径。
  it.each(['default', 'explicit'] as const)('atomically publishes the %s PID', async (mode) => {
    const file = join(directory, 'helper.pid')
    const args = [probe, file]
    if (mode === 'explicit') {
      args.push(String(process.pid))
    }
    const child = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk: Buffer) => {
      stdout += chunk.toString()
    })
    child.stderr.on('data', (chunk: Buffer) => {
      stderr += chunk.toString()
    })
    const exitCode = await new Promise<number | null>((resolve, reject) => {
      child.once('error', reject)
      child.once('close', resolve)
    })

    expect(exitCode, stderr).toBe(0)
    const expectedPid = mode === 'explicit' ? process.pid : child.pid
    expect(stdout).toBe(String(expectedPid))
    expect(readFileSync(file, 'utf8')).toBe(String(expectedPid))
  })
})
