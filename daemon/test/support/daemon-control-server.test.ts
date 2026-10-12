import {
  existsSync,
  mkdtempSync,
  readFileSync,
  renameSync as realRenameSync,
  rmSync,
  writeFileSync as realWriteFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runInNewContext } from 'node:vm'
import { afterAll, describe, expect, it, vi } from 'vitest'

import { LONG_RUNNING_PID_SCRIPT, longRunningNodeCommand, unixPath } from './daemon-control-server.js'

/** 原子发布回归：在真实文件系统上执行夹具脚本，观察 writeFileSync/renameSync 的顺序与目标文件状态。 */
describe('长进程夹具 PID 发布', () => {
  const directory = mkdtempSync(join(tmpdir(), 'kk-pid-publication-'))
  afterAll(() => rmSync(directory, { recursive: true, force: true }))

  it('先写临时文件再 rename，消费者只可能读到完整 PID', () => {
    const pidFile = join(directory, '发布 pid .pid')
    const temporary = `${pidFile}.tmp`
    const write = vi.fn((file: string, data: string) => realWriteFileSync(file, data))
    const rename = vi.fn((from: string, to: string) => realRenameSync(from, to))
    const interval = vi.fn()

    runInNewContext(LONG_RUNNING_PID_SCRIPT, {
      process: { argv: ['node', pidFile], pid: process.pid },
      require(specifier: string) {
        expect(specifier).toBe('fs')
        return {
          writeFileSync(file: string, data: string) {
            expect(file).toBe(temporary)
            expect(existsSync(pidFile)).toBe(false)
            write(file, data)
          },
          renameSync(from: string, to: string) {
            expect(from).toBe(temporary)
            expect(readFileSync(from, 'utf8')).toBe(String(process.pid))
            expect(existsSync(to)).toBe(false)
            rename(from, to)
            expect(readFileSync(to, 'utf8')).toBe(String(process.pid))
          },
        }
      },
      setInterval: interval,
    })

    expect(write).toHaveBeenCalledTimes(1)
    expect(write).toHaveBeenCalledWith(temporary, String(process.pid))
    expect(rename).toHaveBeenCalledTimes(1)
    expect(rename).toHaveBeenCalledWith(temporary, pidFile)
    expect(interval).toHaveBeenCalledTimes(1)
    expect(readFileSync(pidFile, 'utf8')).toBe(String(process.pid))
    expect(existsSync(temporary)).toBe(false)

    // 调用方仍把同一脚本与含空格/中文的目标路径按 bash 引号保护后组合。
    const command = longRunningNodeCommand(pidFile)
    expect(command).toContain(LONG_RUNNING_PID_SCRIPT)
    expect(command).toContain(unixPath(pidFile))
  })
})
