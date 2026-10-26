import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { IdeError, safeMessage } from '../../src/ide/errors.js'
import { WireError } from '../../src/ide/wire.js'
import { canonicalDirectory, hostHome, newViewId, validateViewId, workspaceFileContent } from '../../src/ide/workspace.js'

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

describe('view identity and workspace files', () => {
  it('validates UUID view ids and generates new ones', () => {
    expect(validateViewId('11111111-1111-4111-8111-111111111111')).toBe('11111111-1111-4111-8111-111111111111')
    expect(validateViewId('AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA')).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    expect(() => validateViewId('pane-1')).toThrow(IdeError)
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

describe('safe error messages', () => {
  it('maps known errors without leaking internals', () => {
    expect(safeMessage(new IdeError('directory does not exist'))).toBe('directory does not exist')
    const aborted = new Error('boom')
    aborted.name = 'AbortError'
    expect(safeMessage(aborted)).toBe('ide request was cancelled')
    expect(safeMessage(new Error('secret token=abc'))).toBe('ide request failed')
    // Transport and peer protocol errors are not trusted operator-safe messages.
    expect(safeMessage(new WireError('synthetic-sensitive-stream-error'))).toBe('ide stream protocol or transport failed')
    expect(safeMessage('nonsense')).toBe('ide request failed')
  })
})
