import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import * as state from '../../src/native/state.js'

/**
 * 握手指令面必须失败关闭：读取启动规格时「不可读」与「载荷不是完整对象」是两类不同的拒绝，
 * 任何一类都不能被当成「没有发布」而放行用户命令。
 */
describe('native scope state protocol failure modes', () => {
  let dir: string

  beforeEach(async () => {
    dir = await state.createStateDir()
  })

  afterEach(() => {
    state.removeStateDir(dir)
  })

  test('reading a launch spec that cannot be read fails closed', () => {
    // 路径存在但不是普通文件（这里是目录）：存在性检查通过、读取失败，必须显式报不可读。
    mkdirSync(join(dir, state.LAUNCH_FILE))
    expect(() => state.readLaunch(dir)).toThrow(/unreadable/)
  })

  test('a non-object launch payload is rejected as incomplete', () => {
    state.publish(dir, state.LAUNCH_FILE, '42')
    expect(() => state.readLaunch(dir)).toThrow(/incomplete/)
  })

  test('a non-object exit payload is rejected as unreadable', () => {
    state.publish(dir, state.EXIT_FILE, '42')
    expect(() => state.readExit(dir)).toThrow(/unreadable/)
  })
})
