import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, test } from 'vitest'
import { resolveHelperPath } from '../../src/native/helper-path.js'
import { ERROR_FILE } from '../../src/native/state.js'

// helper 是独立进程：直接拉起它验证「没有启动规格就失败关闭」这条契约，确保放行之前绝不产生副作用。
describe('native scope helper failures', () => {
  test('the helper fails closed when the launch spec is missing', async () => {
    const stateDir = mkdtempSync(join(tmpdir(), 'kk-studio-native-helper-'))
    try {
      const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), stateDir], { stdio: 'ignore' })
      const code = await new Promise<number | null>((resolve) => child.on('close', (value) => resolve(value)))
      expect(code).toBe(1)
      // 失败原因发布给自己私有的状态目录；内容只说明阶段，不携带启动参数。
      expect(readFileSync(join(stateDir, ERROR_FILE), 'utf8')).toMatch(/launch spec is missing/)
    } finally {
      rmSync(stateDir, { recursive: true, force: true })
    }
  }, 30_000)

  test('the helper exits with code 2 when no state directory is given', async () => {
    const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath()], { stdio: 'ignore' })
    const code = await new Promise<number | null>((resolve) => child.on('close', (value) => resolve(value)))
    expect(code).toBe(2)
  }, 30_000)
})
