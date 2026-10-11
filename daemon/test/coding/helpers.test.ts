import { afterEach, describe, expect, it } from 'vitest'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  OutputSpool,
} from '../../src/coding/output.js'
import {
  buildDiff,
  detectLineEnding,
  normalizeToLf,
  parseLineEndingDocument,
  serializeLineEndingDocument,
  truncateInline,
} from '../../src/coding/files.js'
import {
  createUtf16Decoder,
  createUtf8Decoder,
  probe,
  readAllBytes,
  forEachLine,
} from '../../src/coding/text.js'
import { searchCapabilities } from '../../src/coding/search.js'
import { callCapability, makeContext, openTestStore, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-helpers-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

describe('UTF-8 严格解码器', () => {
  it('解码多字节序列并跨块保留不完整后缀', () => {
    const decoder = createUtf8Decoder()
    expect(decoder.push(utf8('aé'))).toBe('aé')
    const split = createUtf8Decoder()
    expect(split.push(Uint8Array.from([0xe4, 0xb8]))).toBe('')
    expect(split.push(Uint8Array.from([0xad]))).toBe('中')
    const emoji = createUtf8Decoder()
    expect(emoji.push(Uint8Array.from([0xf0, 0x9f]))).toBe('')
    expect(emoji.push(Uint8Array.from([0x98, 0x80]))).toBe('😀')
  })

  it('非法前导字节、缺失续字节与过短尾部都失败', () => {
    expect(() => createUtf8Decoder().push(Uint8Array.from([0x80]))).toThrowError(/invalid text encoding/)
    expect(() => createUtf8Decoder().push(Uint8Array.from([0xe4, 0x41]))).toThrowError(/malformed/)
    const pending = createUtf8Decoder()
    pending.push(Uint8Array.from([0xe4]))
    expect(() => pending.finish()).toThrowError(/invalid text encoding/)
  })

  it('代理项编码与超出范围的码点失败', () => {
    expect(() => createUtf8Decoder().push(Uint8Array.from([0xed, 0xa0, 0x80]))).toThrowError(/malformed/)
    expect(() =>
      createUtf8Decoder().push(Uint8Array.from([0xf4, 0x90, 0x80, 0x80])),
    ).toThrowError(/malformed/)
  })
})

describe('UTF-16 严格解码器', () => {
  it('合并跨块字节与代理对', () => {
    const decoder = createUtf16Decoder(true)
    expect(decoder.push(Uint8Array.from([0x61]))).toBe('')
    expect(decoder.push(Uint8Array.from([0x00]))).toBe('a')
    const pair = createUtf16Decoder(true)
    expect(pair.push(Uint8Array.from([0x3d, 0xd8, 0x00, 0xde]))).toBe('😀')
  })

  it('孤立低代理项失败', () => {
    expect(() => createUtf16Decoder(true).push(Uint8Array.from([0x00, 0xdc]))).toThrowError(/malformed/)
  })
})

describe('probe / readAllBytes / forEachLine', () => {
  it('probe 读取前缀，readAllBytes 读取全文', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.bin')
    await writeFile(file, Buffer.alloc(9000, 0x41))
    expect((await probe(file)).length).toBe(8192)
    expect((await readAllBytes(file)).length).toBe(9000)
  })

  it('超长行以 truncated 交付', async () => {
    const dir = await tempDir()
    const file = join(dir, 'long.txt')
    await writeFile(file, `${'a'.repeat(1048580)}\n`)
    let truncatedSeen = false
    await forEachLine(file, { charset: 'UTF-8', bomLength: 0, utf16: false }, (_n, _l, truncated) => {
      truncatedSeen = truncated
      return true
    })
    expect(truncatedSeen).toBe(true)
  })
})

describe('edit 行尾/ diff 辅助', () => {
  it('detectLineEnding 与 normalizeToLf', () => {
    expect(detectLineEnding('a\nb')).toBe('\n')
    expect(detectLineEnding('a\r\nb')).toBe('\r\n')
    expect(detectLineEnding('a\rb')).toBe('\r')
    expect(detectLineEnding('a\r\nb\nc')).toBe('mixed')
    expect(normalizeToLf('a\r\nb\rc')).toBe('a\nb\nc')
  })

  it('parse/serialize 往返保留混合行尾', () => {
    const document = parseLineEndingDocument('a\r\nb\nc')
    expect(serializeLineEndingDocument(document)).toBe('a\r\nb\nc')
  })

  it('buildDiff 输出上下文、删除与新增块', () => {
    const before = parseLineEndingDocument('a\nb\nc\n')
    const after = parseLineEndingDocument('a\nB\nc\n')
    const diff = buildDiff(before, after)
    expect(diff).toContain(' 1|a')
    expect(diff).toContain('- 2|b')
    expect(diff).toContain('+ 2|B')
    expect(diff).toContain(' 3|c')
  })

  it('buildDiff 折叠远端上下文并标注省略', () => {
    const lines = Array.from({ length: 40 }, (_value, index) => `line${index}`)
    const changed = [...lines]
    changed[0] = 'first'
    changed[39] = 'last'
    const diff = buildDiff(
      parseLineEndingDocument(`${lines.join('\n')}\n`),
      parseLineEndingDocument(`${changed.join('\n')}\n`),
    )
    expect(diff).toContain('...')
    expect(diff).toContain('- 1|line0')
    expect(diff).toContain('+ 1|first')
  })

  it('alignMiddle 超大区域退化为整段删除加新增', () => {
    const original = Array.from({ length: 1500 }, (_value, index) => `o${index}`)
    const next = Array.from({ length: 1500 }, (_value, index) => `n${index}`)
    const diff = buildDiff(
      parseLineEndingDocument(`${original.join('\n')}\n`),
      parseLineEndingDocument(`${next.join('\n')}\n`),
    )
    expect(diff).toContain('|o0')
    expect(diff).toContain('|n0')
  })

  it('truncateInline 在代理对边界截断', () => {
    const long = `${'a'.repeat(30 * 1024 - 1)}\ud83d\ude00`
    const truncated = truncateInline(long)
    expect(truncated.endsWith('... (diff truncated to fit inline limit)')).toBe(true)
    expect(truncated).not.toContain('\ud83d\ude00')
  })
})

describe('output 预览', () => {
  it('超大输出省略中段并保留尾部快照', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c', 5, 2000, store.captureBudgetBytes())
    await spool.write(utf8('a'.repeat(20000)))
    const result = await spool.finish(false)
    expect(textOf(result)).toContain('bytes omitted here')
    const truncated = new OutputSpool(store, 'c', 5, 2000, 10)
    await truncated.write(utf8('中'.repeat(50)))
    expect(truncated.captureTruncatedTailPreview().length).toBeGreaterThan(0)
    await truncated.close()
  })

  it('createStagingFile 对非法 callId 仍生成安全文件名', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const staging = await store.createStagingFile('../../evil/name')
    expect(staging.endsWith('.part')).toBe(true)
    expect(staging).toContain('evil_name')
    await store.deleteStagingQuietly(staging)
  })
})

describe('search 忽略与多行', () => {
  it('仓库 .git/info/exclude 生效', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git', 'info'), { recursive: true })
    await writeFile(join(dir, '.git', 'info', 'exclude'), '*.tmp\n')
    await writeFile(join(dir, 'a.tmp'), '')
    const find = searchCapabilities['fs.find']!
    const result = await callCapability(find, 'fs.find', { pattern: '*.tmp', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe('No files found matching pattern')
  })

  it('linked worktree 的 commondir exclude 生效', async () => {
    const dir = await tempDir()
    const common = join(dir, 'common-git')
    await mkdir(join(common, 'info'), { recursive: true })
    await writeFile(join(common, 'info', 'exclude'), '*.skip\n')
    const worktree = join(dir, 'wt')
    await mkdir(worktree)
    const gitDir = join(dir, 'wt-git')
    await mkdir(gitDir)
    await writeFile(join(gitDir, 'commondir'), '../common-git\n')
    await writeFile(join(worktree, '.git'), `gitdir: ${gitDir}\n`)
    await writeFile(join(worktree, 'a.skip'), '')
    const find = searchCapabilities['fs.find']!
    const result = await callCapability(
      find,
      'fs.find',
      { pattern: '*.skip', path: worktree },
      makeContext(dir),
    )
    expect(textOf(result)).toBe('No files found matching pattern')
  })

  it('multiline 跨多行匹配整段输出', async () => {
    const dir = await tempDir()
    const file = join(dir, 'm.txt')
    await writeFile(file, 'x\ny\nz\n')
    const grep = searchCapabilities['fs.grep']!
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x\\ny\\nz', path: file, multiline: true },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${file}:1:x\n${file}:2:y\n${file}:3:z`)
  })
})
