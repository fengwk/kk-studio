import { chmodSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import { ensurePrivateDirectory, verifyPrivateFile, writePrivateFile } from '../../src/native/index.js'

// 私有文件系统原语属于安全边界：断言 0700/0600、符号链接拒绝与创建后读回。
describe.skipIf(process.platform === 'win32')('native private filesystem', () => {
  let workdir: string

  beforeEach(() => {
    workdir = mkdtempSync(join(tmpdir(), 'kk-studio-native-fs-'))
  })

  afterEach(() => {
    rmSync(workdir, { recursive: true, force: true })
  })

  test('ensurePrivateDirectory creates an owner-only directory', async () => {
    const target = join(workdir, 'data')
    const created = await ensurePrivateDirectory(target)
    expect(created).toBe(target)
    const stats = lstatSync(target)
    expect(stats.isDirectory()).toBe(true)
    expect(stats.mode & 0o777).toBe(0o700)
  })

  test('ensurePrivateDirectory tolerates an existing directory but tightens it', async () => {
    const target = join(workdir, 'existing')
    mkdirSync(target, { mode: 0o777 })
    chmodSync(target, 0o777)
    await ensurePrivateDirectory(target)
    expect(lstatSync(target).mode & 0o777).toBe(0o700)
  })

  test('ensurePrivateDirectory rejects a symbolic link', async () => {
    const real = join(workdir, 'real')
    mkdirSync(real, { mode: 0o700 })
    const link = join(workdir, 'link')
    symlinkSync(real, link)
    await expect(ensurePrivateDirectory(link)).rejects.toThrow(/symbolic link/)
  })

  test('writePrivateFile writes 0600 content and verifies', async () => {
    const file = join(workdir, 'secret')
    await writePrivateFile(file, 'token-value')
    expect(readFileSync(file, 'utf8')).toBe('token-value')
    expect(lstatSync(file).mode & 0o777).toBe(0o600)
    await expect(verifyPrivateFile(file)).resolves.toBeUndefined()
  })

  test('verifyPrivateFile rejects a group or world readable file', async () => {
    const file = join(workdir, 'loose')
    await writePrivateFile(file, 'x')
    chmodSync(file, 0o644)
    await expect(verifyPrivateFile(file)).rejects.toThrow(/owner-only/)
  })

  test('writePrivateFile refuses to follow a symbolic link target', async () => {
    const real = join(workdir, 'real')
    await writePrivateFile(real, 'x')
    const link = join(workdir, 'link')
    symlinkSync(real, link)
    await expect(writePrivateFile(link, 'y')).rejects.toThrow()
  })

  test('verifyPrivateFile rejects a symbolic link', async () => {
    const real = join(workdir, 'real')
    await writePrivateFile(real, 'x')
    const link = join(workdir, 'link')
    symlinkSync(real, link)
    await expect(verifyPrivateFile(link)).rejects.toThrow(/real file|symbolic link/)
  })

  test('writePrivateFile accepts binary payloads', async () => {
    const file = join(workdir, 'binary')
    await writePrivateFile(file, new Uint8Array([1, 2, 3]))
    expect([...readFileSync(file)]).toEqual([1, 2, 3])
    expect(lstatSync(file).mode & 0o777).toBe(0o600)
  })

  test('verifyPrivateFile rejects a missing file', async () => {
    await expect(verifyPrivateFile(join(workdir, 'nope'))).rejects.toThrow()
  })

  test('ensurePrivateDirectory rejects a path that is a regular file', async () => {
    const file = join(workdir, 'afile')
    await writePrivateFile(file, 'x')
    await expect(ensurePrivateDirectory(file)).rejects.toThrow()
  })
})
