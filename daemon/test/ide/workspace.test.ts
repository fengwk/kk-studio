import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, mkdir, readFile, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, parse } from 'node:path'
import type { DaemonConfig } from '../../src/contracts.js'
import { IdeError, safeMessage } from '../../src/ide/errors.js'
import { verifyPrivateFile } from '../../src/native/index.js'
import {
  RecoveryQuotaError,
  canonicalDirectory,
  epochValue,
  hostHome,
  listDirectories,
  newViewId,
  readRecoveryBackup,
  recoveryDirectory,
  validateBindingEpoch,
  validateViewId,
  workspaceFileContent,
  writeRecoveryBackup,
  type RecoveryBackup,
} from '../../src/ide/workspace.js'

const cleanups: (() => Promise<void> | void)[] = []
afterEach(async () => {
  for (const cleanup of cleanups.splice(0).reverse()) {
    await cleanup()
  }
})

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-ide-unit-'))
  cleanups.push(() => rm(dir, { recursive: true, force: true }))
  return dir
}

function config(dataDir: string): DaemonConfig {
  return {
    configPath: join(dataDir, 'config.json'),
    dataDir,
    tokenPath: join(dataDir, 'token'),
    studioUrl: 'https://studio.example',
    gatewayUri: 'wss://studio.example/api/harness/environment-daemon',
    bashExecutable: '/bin/bash',
    note: null,
    lsp: {},
    distributionDir: join(dataDir, 'distribution'),
  }
}

function backup(viewId: string, text = 'unsaved'): RecoveryBackup {
  return {
    viewId,
    bindingEpoch: '3',
    directory: '/project',
    createdAt: new Date(0).toISOString(),
    complete: true,
    documents: [{ path: '/project/a.txt', text }],
  }
}

describe('view identity and workspace files', () => {
  it('lists sorted real child directories and resolves the input symlink', async () => {
    const dir = await tempDir()
    const target = join(dir, 'target')
    await mkdir(target)
    await mkdir(join(target, 'z'))
    await mkdir(join(target, 'a'))
    await writeFile(join(target, 'file'), '')
    const linkType = process.platform === 'win32' ? 'junction' : 'dir'
    await symlink(join(target, 'a'), join(target, 'link'), linkType)
    await symlink(target, join(dir, 'alias'), linkType)
    const canonicalTarget = await realpath(target)
    // Child links and files are not traversed, but the input is canonicalized.
    expect(await listDirectories(join(dir, 'alias'))).toEqual({
      path: canonicalTarget, parentPath: await realpath(dir),
      children: [{ name: 'a', path: join(canonicalTarget, 'a') }, { name: 'z', path: join(canonicalTarget, 'z') }],
      truncated: false,
    })
    expect((await listDirectories(parse(canonicalTarget).root)).parentPath).toBeUndefined()
    await expect(listDirectories('relative')).rejects.toThrow()
    await expect(listDirectories(join(dir, 'missing'))).rejects.toThrow()
    await expect(listDirectories(join(target, 'file'))).rejects.toThrow()
  })

  it('bounds listings by both entry count and serialized bytes', async () => {
    // TMPDIR may be long enough for the byte limit to precede the entry limit.
    const dir = await mkdtemp(join(tmpdir(), 'x'))
    cleanups.push(() => rm(dir, { recursive: true, force: true }))
    const many = dir
    const long = join(dir, 'long')
    await mkdir(long)
    await Promise.all(Array.from({ length: 1001 }, (_, i) => mkdir(join(many, String(i).padStart(4, '0')))))
    const count = await listDirectories(many)
    expect(count.truncated).toBe(true)
    // Independently calculate how many sorted entries fit under both limits.
    const base = { ...count, parentPath: count.parentPath ?? null, children: [], truncated: true }
    let used = Buffer.byteLength(JSON.stringify(base))
    let expectedCount = 0
    for (let i = 0; i < 1000; i++) {
      const name = String(i).padStart(4, '0')
      used += Buffer.byteLength(JSON.stringify({ name, path: join(count.path, name) })) + 1
      if (used > 48 * 1024) break
      expectedCount++
    }
    expect(count.children).toHaveLength(expectedCount)
    expect(count.children.map(child => child.name)).toEqual(
      Array.from({ length: count.children.length }, (_, i) => String(i).padStart(4, '0')),
    )
    expect(Buffer.byteLength(JSON.stringify(count))).toBeLessThanOrEqual(48 * 1024)
    await Promise.all(Array.from({ length: 300 }, (_, i) => mkdir(join(long, `${String(i).padStart(4, '0')}${'界'.repeat(60)}`))))
    const bytes = await listDirectories(long)
    // Multibyte names hit the byte bound well before the count bound.
    expect(bytes.truncated).toBe(true)
    expect(bytes.children.length).toBeLessThan(300)
    expect(Buffer.byteLength(JSON.stringify(bytes))).toBeLessThanOrEqual(48 * 1024)
  })

  it('accepts only canonical lowercase UUIDs and positive long epochs', () => {
    expect(validateViewId('11111111-1111-4111-8111-111111111111')).toBe('11111111-1111-4111-8111-111111111111')
    expect(() => validateViewId('AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA')).toThrow(IdeError)
    expect(() => validateViewId('pane-1')).toThrow(IdeError)
    expect(validateBindingEpoch('1')).toBe('1')
    expect(epochValue('3')).toBe(3n)
    expect(validateBindingEpoch('9223372036854775807')).toBe('9223372036854775807')
    expect(() => validateBindingEpoch('0')).toThrow(IdeError)
    expect(() => validateBindingEpoch('9223372036854775808')).toThrow(IdeError)
    expect(newViewId()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('resolves the host HOME or fails closed', () => {
    expect(hostHome({ HOME: '/home/user' })).toBe('/home/user')
    expect(hostHome({ USERPROFILE: 'C:\\Users\\user' })).toBe('C:\\Users\\user')
    expect(() => hostHome({})).toThrow(/HOME is not set/)
  })

  it('canonicalises directories through symlinks and rejects non directories', async () => {
    const dir = await tempDir()
    const project = join(dir, 'project')
    await mkdir(project)
    const resolved = await canonicalDirectory(project)
    expect(resolved).toBe(await (await import('node:fs/promises')).realpath(project))
    await expect(canonicalDirectory('relative')).rejects.toThrow(/absolute path/)
    await expect(canonicalDirectory(join(dir, 'missing'))).rejects.toThrow(/does not exist/)
    const file = join(dir, 'file.txt')
    await writeFile(file, 'x')
    await expect(canonicalDirectory(file)).rejects.toThrow(/not a directory/)
  })

  it('serialises the managed single folder workspace', () => {
    expect(JSON.parse(workspaceFileContent('/home/user'))).toEqual({ folders: [{ path: '/home/user' }] })
  })

  it('rejects unsafe directory names without exposing them in listings', async () => {
    if (process.platform === 'win32') return
    const dir = await tempDir()
    await mkdir(join(dir, 'valid'))
    await mkdir(join(dir, '  '))
    await mkdir(join(dir, 'bad\nname'))
    const listing = await listDirectories(dir)
    expect(listing.truncated).toBe(true)
    expect(listing.children.map(child => child.name)).toEqual(['valid'])
    await expect(listDirectories(join(dir, 'bad\nname'))).rejects.toThrow('directory path is invalid')
  })
})

describe('private recovery backups', () => {
  it('enforces aggregate quota and leaves existing recovery data intact', async () => {
    const cfg = config(await tempDir())
    const first = backup(newViewId(), 'first')
    const second = backup(newViewId(), 'second')
    const firstBytes = Buffer.byteLength(`${JSON.stringify(first)}\n`)
    const secondBytes = Buffer.byteLength(`${JSON.stringify(second)}\n`)
    await writeRecoveryBackup(cfg, first, firstBytes + secondBytes)
    // Each backup fits alone, but the combined directory budget does not.
    await expect(writeRecoveryBackup(cfg, second, firstBytes + secondBytes - 1))
      .rejects.toThrow('recovery data quota is exhausted')
    expect(await readRecoveryBackup(cfg, first.viewId, firstBytes)).toEqual(first)
    expect(await readRecoveryBackup(cfg, second.viewId, secondBytes)).toBeNull()
    await expect(writeRecoveryBackup(cfg, first, 0)).rejects.toThrow('recovery quota is invalid')
    await expect(readRecoveryBackup(cfg, first.viewId, firstBytes - 1))
      .rejects.toThrow('recovery backup exceeds the configured quota')
  })

  it('reports malformed JSON and filesystem errors without leaking paths', async () => {
    const cfg = config(await tempDir())
    const viewId = newViewId()
    const directory = recoveryDirectory(cfg)
    await mkdir(directory, { recursive: true })
    const file = join(directory, `${viewId}.json`)
    await writeFile(file, '{')
    await expect(readRecoveryBackup(cfg, viewId, 1024)).rejects.toThrow('recovery backup is corrupt')
    await rm(file)
    await mkdir(file)
    await expect(readRecoveryBackup(cfg, viewId, 1024)).rejects.toThrow('recovery backup is unreadable:')
  })

  it('reports quota measurement failures for dangling recovery entries', async () => {
    if (process.platform === 'win32') return
    const cfg = config(await tempDir())
    const directory = recoveryDirectory(cfg)
    await mkdir(directory, { recursive: true })
    await symlink(join(directory, 'missing'), join(directory, 'dangling.json'))
    // A dangling entry must not be silently excluded from the quota.
    await expect(writeRecoveryBackup(cfg, backup(newViewId()), 4096))
      .rejects.toThrow('recovery quota could not be measured: ENOENT')
  })

  it('round-trips a complete backup and replaces only the same view', async () => {
    const dir = await tempDir()
    const cfg = config(dir)
    const viewId = newViewId()
    await writeRecoveryBackup(cfg, backup(viewId), 1024 * 1024)
    expect(await readRecoveryBackup(cfg, viewId, 1024 * 1024)).toEqual(backup(viewId))
    const file = join(recoveryDirectory(cfg), `${viewId}.json`)
    // POSIX 验证属主和 0600；Windows 验证实际 owner-only DACL，而不是无此语义的 mode 位。
    await expect(verifyPrivateFile(file)).resolves.toBeUndefined()
    await writeRecoveryBackup(cfg, backup(viewId, 'replacement'), 1024 * 1024)
    expect((await readRecoveryBackup(cfg, viewId, 1024 * 1024))?.documents[0]?.text).toBe('replacement')
    expect(await readRecoveryBackup(cfg, newViewId(), 1024 * 1024)).toBeNull()
  })

  it('rejects over-quota writes without deleting other views and reports corrupt backups', async () => {
    const dir = await tempDir()
    const cfg = config(dir)
    const first = newViewId()
    const second = newViewId()
    await writeRecoveryBackup(cfg, backup(first, 'first'), 2048)
    await expect(writeRecoveryBackup(cfg, backup(second, 'x'.repeat(4096)), 4096)).rejects.toBeInstanceOf(
      RecoveryQuotaError,
    )
    expect((await readRecoveryBackup(cfg, first, 2048))?.documents[0]?.text).toBe('first')
    await expect(readRecoveryBackup(cfg, first, 0)).rejects.toBeInstanceOf(RecoveryQuotaError)
    const corrupt = join(recoveryDirectory(cfg), `${second}.json`)
    await mkdir(recoveryDirectory(cfg), { recursive: true })
    await writeFile(corrupt, '{"version":1,"viewId":"wrong"}')
    await expect(readRecoveryBackup(cfg, second, 1024)).rejects.toBeInstanceOf(RecoveryQuotaError)
  })

  it('validates corrupt backup payload variants', async () => {
    const dir = await tempDir()
    const cfg = config(dir)
    const viewId = newViewId()
    await mkdir(recoveryDirectory(cfg), { recursive: true })
    const file = join(recoveryDirectory(cfg), `${viewId}.json`)

    // 非对象 JSON
    await writeFile(file, '"a string"')
    await expect(readRecoveryBackup(cfg, viewId, 1024)).rejects.toThrow('recovery backup is corrupt')

    // 非法 bindingEpoch
    await writeFile(
      file,
      JSON.stringify({
        ...backup(viewId),
        bindingEpoch: 'not-a-number',
      }),
    )
    await expect(readRecoveryBackup(cfg, viewId, 1024)).rejects.toThrow('recovery backup is corrupt')

    // document 路径包含非法控制字符
    await writeFile(
      file,
      JSON.stringify({
        ...backup(viewId),
        documents: [{ path: '/project/\u0000.txt', text: 'hi' }],
      }),
    )
    await expect(readRecoveryBackup(cfg, viewId, 1024)).rejects.toThrow('recovery backup is corrupt')

    // document 不是对象
    await writeFile(
      file,
      JSON.stringify({
        ...backup(viewId),
        documents: ['not-an-object'],
      }),
    )
    await expect(readRecoveryBackup(cfg, viewId, 1024)).rejects.toThrow('recovery backup is corrupt')
  })

  it('writes files privately and does not overflow the fixed directory budget', async () => {
    const dir = await tempDir()
    const cfg = config(dir)
    const viewId = newViewId()
    const serialized = JSON.stringify(backup(viewId))
    await writeRecoveryBackup(cfg, backup(viewId), 4096)
    const file = join(recoveryDirectory(cfg), `${viewId}.json`)
    expect(await readFile(file, 'utf8')).toContain('unsaved')
    expect(serialized.length).toBeGreaterThan(0)
    await expect(writeRecoveryBackup(cfg, backup(viewId, 'x'.repeat(4096)), 4096)).rejects.toBeInstanceOf(
      RecoveryQuotaError,
    )
  })
})

describe('safe error messages', () => {
  it('maps known errors without leaking internals', () => {
    expect(safeMessage(new IdeError('directory does not exist'))).toBe('directory does not exist')
    const aborted = new Error('boom')
    aborted.name = 'AbortError'
    expect(safeMessage(aborted)).toBe('ide request was cancelled')
    expect(safeMessage(new Error('secret token=abc'))).toBe('ide request failed')
    expect(safeMessage('nonsense')).toBe('ide request failed')
  })
})
