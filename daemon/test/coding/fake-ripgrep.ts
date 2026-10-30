import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { RipgrepCommand } from '../../src/coding/ripgrep.js'

/**
 * 可编程的 rg 替身：通过 Node 脚本生成确定性的 match/limit/exit 行为，用于锁住进程终止、partial 结果与退出码 2 语义。
 * 只替换命令入口，不绕过能力层的参数与输出处理。
 */
export interface FakeRipgrepOptions {
  target: string
  matches?: number
  hang?: boolean
  exitCode?: number
  stderr?: string
  rawStdout?: string
}

export interface FakeRipgrep {
  command: RipgrepCommand
  pidFile: string
}

export async function installFakeRipgrep(
  directory: string,
  options: FakeRipgrepOptions,
): Promise<FakeRipgrep> {
  const pidFile = join(directory, 'fake-rg.pid')
  const script = join(directory, 'fake-rg.mjs')
  const matches = options.matches ?? 1
  const source = `import { writeFileSync } from 'node:fs'

const target = ${JSON.stringify(options.target)}
const pidFile = ${JSON.stringify(pidFile)}
writeFileSync(pidFile, String(process.pid))
const args = process.argv.slice(2)
const hang = ${options.hang === true}
const globIndex = args.indexOf('--glob')
const hasUserGlob = globIndex >= 0 && args[globIndex + 1] !== '!.git'
if (args.includes('--files')) {
  process.stdout.write(target + '\\0')
  if (hasUserGlob && hang) {
    setInterval(() => {}, 1000)
  } else {
    process.exit(${options.exitCode ?? 0})
  }
}
if (${JSON.stringify(options.rawStdout ?? '')}.length > 0) {
  process.stdout.write(${JSON.stringify(options.rawStdout ?? '')})
}
if (${matches} > 0) {
  for (let index = 0; index < ${matches}; index++) {
    process.stdout.write(JSON.stringify({
      type: 'match',
      data: {
        path: { text: target },
        lines: { text: 'match line\\n' },
        line_number: index + 1,
        absolute_offset: index * 11,
        submatches: [{ start: 0, end: 5 }],
      },
    }) + '\\n')
  }
}
if (${JSON.stringify(options.stderr ?? '')}.length > 0) {
  process.stderr.write(${JSON.stringify(options.stderr ?? '')})
}
if (hang) {
  setInterval(() => {}, 1000)
} else {
  process.exit(${options.exitCode ?? 0})
}
`
  await writeFile(script, source)
  return { command: { binary: process.execPath, prefixArgs: [script] }, pidFile }
}

/** 等假 rg 真正启动并写出 pid；超时即测试失败，而不是把未启动当成已终止。 */
export async function waitForFakePid(pidFile: string, timeoutMillis = 5000): Promise<number> {
  const deadline = Date.now() + timeoutMillis
  for (;;) {
    try {
      const value = await readFile(pidFile, 'utf8')
      const pid = Number(value)
      if (Number.isInteger(pid) && pid > 0) {
        return pid
      }
    } catch {
      // 尚未启动。
    }
    if (Date.now() >= deadline) {
      throw new Error(`fake ripgrep did not start within ${timeoutMillis}ms`)
    }
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
}
