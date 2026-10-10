import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import * as state from '../../src/native/state.js'

// 父进程与 helper 的握手面：原子发布、读取即删、NUL 拒绝与退出/收敛事实的解析。
describe('native scope state protocol', () => {
  let dir: string

  beforeEach(() => {
    dir = state.createStateDir()
  })

  afterEach(() => {
    state.removeStateDir(dir)
  })

  test('createStateDir yields an existing owner-only directory', () => {
    expect(existsSync(dir)).toBe(true)
    if (process.platform !== 'win32') {
      expect(statSync(dir).mode & 0o777).toBe(0o700)
    }
  })

  test('publish then read round-trips and files are owner-only', () => {
    state.publish(dir, state.SCOPE_FILE, '123')
    expect(state.read(dir, state.SCOPE_FILE)).toBe('123')
    if (process.platform !== 'win32') {
      expect(statSync(join(dir, state.SCOPE_FILE)).mode & 0o777).toBe(0o600)
    }
  })

  test('reading a missing file returns null', () => {
    expect(state.read(dir, 'missing')).toBeNull()
  })

  test('publishQuietly never throws', () => {
    expect(() => state.publishQuietly(join(dir, 'no-such-dir'), 'x', 'y')).not.toThrow()
  })

  test('launch spec round-trips and is deleted after reading', () => {
    state.writeLaunch(dir, { cwd: '/tmp', args: ['/bin/echo', 'hi'] })
    const spec = state.readLaunch(dir)
    expect(spec).toEqual({ cwd: '/tmp', args: ['/bin/echo', 'hi'] })
    expect(existsSync(join(dir, state.LAUNCH_FILE))).toBe(false)
    expect(state.readLaunch(dir)).toBeNull()
  })

  test('launch spec rejects NUL characters', () => {
    expect(() => state.writeLaunch(dir, { cwd: '/tmp', args: ['a\0b'] })).toThrow(/NUL/)
    expect(() => state.writeLaunch(dir, { cwd: 'a\0b', args: ['x'] })).toThrow(/NUL/)
  })

  test('a corrupt launch spec fails closed', () => {
    state.publish(dir, state.LAUNCH_FILE, 'not json')
    expect(() => state.readLaunch(dir)).toThrow(/unreadable/)
  })

  test('an incomplete launch spec fails closed', () => {
    state.publish(dir, state.LAUNCH_FILE, JSON.stringify({ cwd: '/tmp' }))
    expect(() => state.readLaunch(dir)).toThrow(/incomplete/)
  })

  test('exit reports are parsed and corrupt payloads fail closed', () => {
    state.publish(dir, state.EXIT_FILE, JSON.stringify({ exitCode: 3, signal: null }))
    expect(state.readExit(dir)).toEqual({ exitCode: 3, signal: null })
    expect(state.readExit(dir)).toEqual({ exitCode: 3, signal: null })
    state.publish(dir, state.EXIT_FILE, '{not json')
    expect(() => state.readExit(dir)).toThrow(/unreadable/)
    state.publish(dir, state.EXIT_FILE, JSON.stringify({ exitCode: 'x', signal: null }))
    expect(() => state.readExit(dir)).toThrow(/unreadable/)
  })

  test('cleanup truth only accepts explicit true/false', () => {
    expect(state.readCleanup(dir)).toBeNull()
    state.publish(dir, state.CLEANUP_FILE, 'true')
    expect(state.readCleanup(dir)).toBe(true)
    state.publish(dir, state.CLEANUP_FILE, 'false')
    expect(state.readCleanup(dir)).toBe(false)
    state.publish(dir, state.CLEANUP_FILE, 'maybe')
    expect(state.readCleanup(dir)).toBeNull()
  })

  test('removeStateDir deletes the directory tree', () => {
    state.publish(dir, state.ERROR_FILE, 'boom')
    state.removeStateDir(dir)
    expect(existsSync(dir)).toBe(false)
    expect(() => state.removeStateDir(dir)).not.toThrow()
  })

  test('publish writes atomically through a temp file', () => {
    state.publish(dir, state.PERMIT_FILE, 'permit')
    expect(readFileSync(join(dir, state.PERMIT_FILE), 'utf8')).toBe('permit')
    expect(existsSync(join(dir, `${state.PERMIT_FILE}.tmp`))).toBe(false)
  })
})
