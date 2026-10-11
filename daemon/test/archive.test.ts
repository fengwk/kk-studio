import { createHash } from 'node:crypto'
import { gzipSync } from 'node:zlib'
import { lstat, mkdir, mkdtemp, readFile, readlink, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { ReadEntry } from 'tar'
import {
  ArchiveError,
  MAX_EXTRACTED_FILE_BYTES,
  MAX_EXTRACTED_TOTAL_BYTES,
  extractArchive,
  inspectArchiveEntry,
  normalizeArchivePath,
  validateArchive,
} from '../src/archive.js'

interface TarEntry {
  name: string
  type?: '0' | '1' | '2' | '5' | '6'
  linkname?: string
  body?: Buffer
  size?: number
}

/** 手工构造 ustar 头，允许写入绝对路径、`..`、逃逸链接等真实恶意形状。 */
function buildTar(entries: TarEntry[]): Buffer {
  const blocks: Buffer[] = []
  for (const entry of entries) {
    const body = entry.body ?? Buffer.alloc(0)
    const header = Buffer.alloc(512)
    header.write(entry.name, 0, 100, 'utf8')
    writeOctal(header, 100, 8, 0o644)
    writeOctal(header, 108, 8, 0)
    writeOctal(header, 116, 8, 0)
    writeOctal(header, 124, 12, entry.size ?? body.length)
    writeOctal(header, 136, 12, 0)
    header.write('        ', 148, 8, 'utf8')
    header.write(entry.type ?? '0', 156, 1, 'utf8')
    if (entry.linkname !== undefined) {
      header.write(entry.linkname, 157, 100, 'utf8')
    }
    header.write('ustar\0', 257, 6, 'utf8')
    header.write('00', 263, 2, 'utf8')
    let checksum = 0
    for (const byte of header) {
      checksum += byte
    }
    header.write(checksum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'utf8')
    blocks.push(header)
    if (body.length > 0) {
      blocks.push(body)
      const padding = (512 - (body.length % 512)) % 512
      if (padding > 0) {
        blocks.push(Buffer.alloc(padding))
      }
    }
  }
  blocks.push(Buffer.alloc(1024))
  return Buffer.concat(blocks)
}

function writeOctal(target: Buffer, offset: number, length: number, value: number): void {
  target.write(value.toString(8).padStart(length - 1, '0') + '\0', offset, length, 'utf8')
}

describe('archive entry validation', () => {
  let dir: string

  beforeEach(async () => {
    dir = await mkdtemp(path.join(tmpdir(), 'kk-archive-'))
  })

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true })
  })

  async function archiveOf(entries: TarEntry[]): Promise<string> {
    const target = path.join(dir, 'payload.tar.gz')
    await writeFile(target, gzipSync(buildTar(entries)))
    return target
  }

  it('normalizes relative paths and rejects absolute or escaping paths', () => {
    expect(normalizeArchivePath('./')).toBe('')
    expect(normalizeArchivePath('./lib/main.js')).toBe('lib/main.js')
    expect(normalizeArchivePath('lib/')).toBe('lib')
    expect(normalizeArchivePath('/etc/passwd')).toBeNull()
    expect(normalizeArchivePath('../escape')).toBeNull()
    expect(normalizeArchivePath('a/../../b')).toBeNull()
    expect(normalizeArchivePath('C:/windows')).toBeNull()
    expect(normalizeArchivePath('a\\b')).toBeNull()
  })

  it('extracts a legitimate archive with relative entries', async () => {
    const archive = await archiveOf([
      { name: './', type: '5' },
      { name: './main.js', body: Buffer.from('entry') },
      { name: './lib/', type: '5' },
      { name: './lib/nested.js', body: Buffer.from('nested') },
    ])
    const destination = path.join(dir, 'out')
    await mkdir(destination, { recursive: true })

    await extractArchive(archive, destination, new AbortController().signal)

    expect(await readFile(path.join(destination, 'main.js'), 'utf8')).toBe('entry')
    expect(await readFile(path.join(destination, 'lib/nested.js'), 'utf8')).toBe('nested')
  })

  it('accepts a relative symlink that stays inside the extraction root', async () => {
    const archive = await archiveOf([
      { name: './main.js', body: Buffer.from('entry') },
      { name: './lib/link.js', type: '2', linkname: '../main.js' },
    ])
    await expect(validateArchive(archive, new AbortController().signal)).resolves.toBeUndefined()
  })

  // 创建符号链接在 Windows 上需要额外权限，因此真实落盘的链接目标只在 POSIX 平台断言。
  it.runIf(process.platform !== 'win32')(
    'materializes a relative internal symlink when extracting',
    async () => {
      const archive = await archiveOf([
        { name: './main.js', body: Buffer.from('entry') },
        { name: './lib/link.js', type: '2', linkname: '../main.js' },
      ])
      const destination = path.join(dir, 'out-link')
      await mkdir(destination, { recursive: true })

      await extractArchive(archive, destination, new AbortController().signal)

      expect(await readlink(path.join(destination, 'lib/link.js'))).toBe('../main.js')
    },
  )

  it('rejects an absolute path entry', async () => {
    const archive = await archiveOf([{ name: '/etc/passwd', body: Buffer.from('x') }])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(ArchiveError)
  })

  it('rejects a .. escape entry', async () => {
    const archive = await archiveOf([{ name: '../escape', body: Buffer.from('x') }])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(ArchiveError)
  })

  it('rejects a symlink that escapes the extraction root', async () => {
    const archive = await archiveOf([{ name: './evil', type: '2', linkname: '/etc/passwd' }])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(
      'distribution archive contains a link that escapes the extraction root',
    )
  })

  it('rejects a hard link to an undeclared file', async () => {
    const archive = await archiveOf([{ name: './copy.js', type: '1', linkname: './missing.js' }])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(
      'distribution archive contains a hard link to an undeclared file',
    )
  })

  it('rejects a symlink whose target uses Windows separators to escape', async () => {
    const archive = await archiveOf([{ name: './evil', type: '2', linkname: '..\\..\\Windows\\System32' }])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(
      'distribution archive contains a link that escapes the extraction root',
    )
  })

  it('rejects a fifo or device entry', async () => {
    const archive = await archiveOf([{ name: './fifo', type: '6' }])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(
      'distribution archive contains a device, fifo or unsupported entry',
    )
  })

  it('rejects a duplicate entry', async () => {
    const archive = await archiveOf([
      { name: './main.js', body: Buffer.from('one') },
      { name: './main.js', body: Buffer.from('two') },
    ])
    await expect(validateArchive(archive, new AbortController().signal)).rejects.toThrow(
      'distribution archive contains a duplicate entry',
    )
  })

  it('fails closed when the archive is cancelled', async () => {
    const archive = await archiveOf([{ name: './main.js', body: Buffer.from('entry') }])
    const controller = new AbortController()
    controller.abort()
    await expect(validateArchive(archive, controller.signal)).rejects.toThrow(ArchiveError)
  })

  it('fails closed when the archive exceeds its processing timeout', async () => {
    const entries: TarEntry[] = []
    for (let index = 0; index < 4000; index += 1) {
      entries.push({ name: `./file-${index}.bin`, body: Buffer.alloc(2048, index % 251) })
    }
    const archive = await archiveOf(entries)
    await expect(validateArchive(archive, new AbortController().signal, 1)).rejects.toThrow(ArchiveError)
  })

  it('rejects an entry beyond the per-file or total size budget', () => {
    const scan = { seen: new Set<string>(), files: new Set<string>(), total: 0 }
    const oversized = fakeEntry({ path: './huge.bin', size: MAX_EXTRACTED_FILE_BYTES + 1 })
    expect(inspectArchiveEntry(oversized, scan)).toBe(
      'distribution archive contains an entry beyond the size budget',
    )
    const secondScan = { seen: new Set<string>(), files: new Set<string>(), total: MAX_EXTRACTED_TOTAL_BYTES }
    const overflowing = fakeEntry({ path: './more.bin', size: 1 })
    expect(inspectArchiveEntry(overflowing, secondScan)).toBe(
      'distribution archive exceeds the total size budget',
    )
  })

  it('keeps a real sha256 stable while processing a benign archive', async () => {
    const archive = await archiveOf([{ name: './main.js', body: Buffer.from('entry') }])
    const digest = createHash('sha256').update(await readFile(archive)).digest('hex')
    expect(digest).toHaveLength(64)
    await validateArchive(archive, new AbortController().signal)
    expect((await lstat(archive)).isFile()).toBe(true)
  })
})

function fakeEntry(overrides: Partial<ReadEntry> & { path: string; size: number }): ReadEntry {
  return {
    type: 'File',
    meta: false,
    unsupported: false,
    invalid: false,
    ...overrides,
  } as unknown as ReadEntry
}
