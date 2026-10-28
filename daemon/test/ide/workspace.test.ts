import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { DaemonConfig } from '../../src/contracts.js'
import { IdeError, safeMessage } from '../../src/ide/errors.js'
import { verifyPrivateFile } from '../../src/native/index.js'
import {
  RecoveryQuotaError,
  canonicalDirectory,
  hostHome,
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
    gatewayUri: 'wss://studio.example/api/harness/environment-daemon/v1',
    bashExecutable: '/bin/bash',
    note: null,
    lsp: {},
    distributionDir: join(dataDir, 'distribution'),
  }
}

function backup(viewId: string, text = 'unsaved'): RecoveryBackup {
  return {
    version: 1,
    viewId,
    bindingEpoch: '3',
    directory: '/project',
    createdAt: new Date(0).toISOString(),
    complete: true,
    documents: [{ path: '/project/a.txt', text }],
  }
}

describe('view identity and workspace files', () => {
  it('accepts only canonical lowercase UUIDs and positive long epochs', () => {
    expect(validateViewId('11111111-1111-4111-8111-111111111111')).toBe('11111111-1111-4111-8111-111111111111')
    expect(() => validateViewId('AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA')).toThrow(IdeError)
    expect(() => validateViewId('pane-1')).toThrow(IdeError)
    expect(validateBindingEpoch('1')).toBe('1')
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
})

describe('private recovery backups', () => {
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
