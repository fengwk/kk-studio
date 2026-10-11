import { existsSync, readFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

/** 解析一个夹具的绝对路径。 */
export function fixturePath(name: string): string {
  return fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
}

/** 有界等待文件出现，返回其文本内容；超时显式失败。 */
export async function waitForFile(file: string, budgetMillis = 20_000): Promise<string> {
  const deadline = Date.now() + budgetMillis
  while (!existsSync(file)) {
    if (Date.now() >= deadline) {
      throw new Error(`timed out waiting for ${file}`)
    }
    await sleep(5)
  }
  return readFileSync(file, 'utf8').trim()
}

/** 读回夹具写出的 pid。 */
export async function waitForPid(file: string, budgetMillis = 20_000): Promise<number> {
  return Number(await waitForFile(file, budgetMillis))
}

/** 进程此刻是否真的活着（僵尸不算）。 */
export function isAlive(pid: number): boolean {
  if (!Number.isSafeInteger(pid) || pid <= 0) {
    return false
  }
  try {
    process.kill(pid, 0)
  } catch {
    return false
  }
  if (process.platform === 'linux') {
    try {
      const content = readFileSync(`/proc/${pid}/stat`, 'utf8')
      const closing = content.lastIndexOf(')')
      const state = closing >= 0 ? content.slice(closing + 2).split(' ')[0] : undefined
      return state !== 'Z' && state !== 'X' && state !== 'x'
    } catch {
      return false
    }
  }
  return true
}

/** 有界等待进程消失；返回它是否已经消失。 */
export async function awaitGone(pid: number, budgetMillis = 20_000): Promise<boolean> {
  const deadline = Date.now() + budgetMillis
  while (isAlive(pid)) {
    if (Date.now() >= deadline) {
      return false
    }
    await sleep(20)
  }
  return true
}

/** 夹具自己目录：不使用全局临时目录，避免测试之间互相干扰。 */
export function fixtureCommand(name: string, ...args: string[]): { executable: string; args: string[] } {
  return { executable: process.execPath, args: [fixturePath(name), ...args] }
}
