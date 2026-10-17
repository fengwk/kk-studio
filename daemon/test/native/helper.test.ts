import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, test } from 'vitest'
import { resolveHelperPath } from '../../src/native/helper-path.js'
import { ERROR_FILE, LAUNCH_FILE } from '../../src/native/state.js'

/** 直接拉起真实 helper 进程，断言它在放行用户命令之前就以固定阶段代码失败关闭。 */
async function runHelperAgainstSpec(stateDir: string): Promise<number | null> {
  const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), stateDir], { stdio: 'ignore' })
  return new Promise<number | null>((resolve) => child.on('close', (value) => resolve(value)))
}

// helper 是独立进程：直接拉起它验证「没有启动规格就失败关闭」这条契约，确保放行之前绝不产生副作用。
describe('native scope helper failures', () => {
  test('the helper fails closed when the launch spec is missing', async () => {
    const stateDir = mkdtempSync(join(tmpdir(), 'kk-studio-native-helper-'))
    try {
      const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath(), stateDir], { stdio: 'ignore' })
      const code = await new Promise<number | null>((resolve) => child.on('close', (value) => resolve(value)))
      expect(code).toBe(1)
      // 对外失败说明是固定阶段代码：不含命令路径、参数或环境片段。
      expect(readFileSync(join(stateDir, ERROR_FILE), 'utf8')).toBe('helper-launch-failure')
    } finally {
      rmSync(stateDir, { recursive: true, force: true })
    }
  }, 30_000)

  test('the helper exits with code 2 when no state directory is given', async () => {
    const child = spawn(process.execPath, ['--no-warnings', resolveHelperPath()], { stdio: 'ignore' })
    const code = await new Promise<number | null>((resolve) => child.on('close', (value) => resolve(value)))
    expect(code).toBe(2)
  }, 30_000)

  test('the helper fails closed when the launch spec is malformed', async () => {
    const stateDir = mkdtempSync(join(tmpdir(), 'kk-studio-native-helper-'))
    try {
      // 损坏的启动规格必须在读取阶段就失败：不得回显载荷，也不得猜出任何命令。
      writeFileSync(join(stateDir, LAUNCH_FILE), '{not json')
      expect(await runHelperAgainstSpec(stateDir)).toBe(1)
      expect(readFileSync(join(stateDir, ERROR_FILE), 'utf8')).toBe('helper-launch-failure')
    } finally {
      rmSync(stateDir, { recursive: true, force: true })
    }
  }, 30_000)

  test('the helper fails closed when the launch spec is incomplete', async () => {
    const stateDir = mkdtempSync(join(tmpdir(), 'kk-studio-native-helper-'))
    try {
      // 结构不完整的启动规格同样必须在放行之前失败关闭。
      writeFileSync(join(stateDir, LAUNCH_FILE), JSON.stringify({ cwd: 42 }))
      expect(await runHelperAgainstSpec(stateDir)).toBe(1)
      expect(readFileSync(join(stateDir, ERROR_FILE), 'utf8')).toBe('helper-launch-failure')
    } finally {
      rmSync(stateDir, { recursive: true, force: true })
    }
  }, 30_000)
})
