import { afterEach, describe, expect, it } from 'vitest'
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  OutputSpool,
} from '../../src/coding/output.js'
import {
  fileCapabilities,
} from '../../src/coding/files.js'
import {
  decodeText,
  probe,
  readAllBytes,
  readTextWindow,
} from '../../src/coding/text.js'
import { searchCapabilities } from '../../src/coding/search.js'
import { toPosix } from '../../src/coding/support.js'
import { callCapability, makeContext, openTestStore, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-helpers-')))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

async function previewDiff(
  content: string,
  oldString: string,
  newString: string,
): Promise<string> {
  const dir = await tempDir()
  const file = join(dir, 'diff-target.txt')
  await writeFile(file, content, 'utf8')
  const editPreview = fileCapabilities['fs.edit.preview']!
  const result = await callCapability(
    editPreview,
    'fs.edit.preview',
    { path: file, old_string: oldString, new_string: newString },
    makeContext(dir),
  )
  expect(result.error).toBe(false)
  const review = (result.contents[0] as { type: 'json'; json: Record<string, unknown> }).json
  return review.diff as string
}

describe('UTF-8 解码器', () => {
  it('解码多字节序列并跨块保留不完整后缀', async () => {
    expect(decodeText(utf8('aé')).text).toBe('aé')
    expect(decodeText(Uint8Array.from([0xe4, 0xb8, 0xad])).text).toBe('中')
    expect(decodeText(Uint8Array.from([0xf0, 0x9f, 0x98, 0x80])).text).toBe('😀')

    const dir = await tempDir()
    const split3Bytes = Buffer.concat([
      Buffer.alloc(8190, 0x61),
      Buffer.from([0xe4, 0xb8, 0xad]),
    ])
    const file1 = join(dir, 'split3.txt')
    await writeFile(file1, split3Bytes)
    const text1 = await readTextWindow(
      file1,
      { charset: 'UTF-8', bomLength: 0, utf16: false },
      1,
      2000,
      null,
      '/split3.txt',
      null,
      () => {},
    )
    expect(text1).toContain('中')

    const split4Bytes = Buffer.concat([
      Buffer.alloc(8190, 0x61),
      Buffer.from([0xf0, 0x9f, 0x98, 0x80]),
    ])
    const file2 = join(dir, 'split4.txt')
    await writeFile(file2, split4Bytes)
    const text2 = await readTextWindow(
      file2,
      { charset: 'UTF-8', bomLength: 0, utf16: false },
      1,
      2000,
      null,
      '/split4.txt',
      null,
      () => {},
    )
    expect(text2).toContain('😀')
  })

  it('非法前导字节、缺失续字节与过短尾部都失败', () => {
    expect(() => decodeText(Uint8Array.from([0x80]))).toThrowError(/invalid text encoding/)
    expect(() => decodeText(Uint8Array.from([0xe4, 0x41]))).toThrowError(/malformed/)
    expect(() => decodeText(Uint8Array.from([0xe4]))).toThrowError(/invalid text encoding/)
  })

  it('代理项编码与超出范围的码点失败', () => {
    expect(() => decodeText(Uint8Array.from([0xed, 0xa0, 0x80]))).toThrowError(/malformed/)
    expect(() =>
      decodeText(Uint8Array.from([0xf4, 0x90, 0x80, 0x80])),
    ).toThrowError(/malformed/)
  })
})

describe('UTF-16 严格解码器', () => {
  it('解码 UTF-16 字节与跨块代理对', async () => {
    expect(decodeText(Uint8Array.from([0xff, 0xfe, 0x61, 0x00])).text).toBe('a')
    expect(decodeText(Uint8Array.from([0xff, 0xfe, 0x3d, 0xd8, 0x00, 0xde])).text).toBe('😀')

    const dir = await tempDir()
    const bom = Buffer.from([0xff, 0xfe])
    const asciiUnits = Buffer.alloc(8190)
    for (let i = 0; i < 8190; i += 2) {
      asciiUnits[i] = 0x61
      asciiUnits[i + 1] = 0x00
    }
    const surrogate = Buffer.from([0x3d, 0xd8, 0x00, 0xde])
    const file = join(dir, 'utf16-split.txt')
    await writeFile(file, Buffer.concat([bom, asciiUnits, surrogate]))
    const text = await readTextWindow(
      file,
      { charset: 'UTF-16LE', bomLength: 2, utf16: true },
      1,
      2000,
      null,
      '/utf16-split.txt',
      null,
      () => {},
    )
    expect(text).toContain('😀')
  })

  it('孤立低代理项失败', () => {
    expect(() => decodeText(Uint8Array.from([0xff, 0xfe, 0x00, 0xdc]))).toThrowError(/malformed/)
  })
})

describe('probe / readAllBytes', () => {
  it('probe 读取前缀，readAllBytes 读取全文', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.bin')
    await writeFile(file, Buffer.alloc(9000, 0x41))
    expect((await probe(file)).length).toBe(8192)
    expect((await readAllBytes(file)).length).toBe(9000)
  })

})

describe('edit 行尾/ diff 辅助', () => {
  it('detectLineEnding 与 normalizeToLf 通过 fs.edit 检验', async () => {
    const dir = await tempDir()
    const edit = fileCapabilities['fs.edit']!

    const fileLf = join(dir, 'file-lf.txt')
    await writeFile(fileLf, 'a\nb\nc\n', 'utf8')
    const rLf = await callCapability(
      edit,
      'fs.edit',
      { path: fileLf, old_string: 'a\r\nb\rc', new_string: 'a\nB\nc' },
      makeContext(dir),
    )
    expect(rLf.error).toBe(false)
    expect(await readFile(fileLf, 'utf8')).toBe('a\nB\nc\n')

    const rNoop = await callCapability(
      edit,
      'fs.edit',
      { path: fileLf, old_string: 'a\r\nB', new_string: 'a\nB' },
      makeContext(dir),
    )
    expect(rNoop.error).toBe(true)
    expect(textOf(rNoop)).toContain('old_string and new_string must differ after line-ending normalization')

    const fileCrlf = join(dir, 'file-crlf.txt')
    await writeFile(fileCrlf, 'a\r\nb\r\n', 'utf8')
    const rCrlf = await callCapability(
      edit,
      'fs.edit',
      { path: fileCrlf, old_string: 'b', new_string: 'b\ninserted' },
      makeContext(dir),
    )
    expect(rCrlf.error).toBe(false)
    expect(await readFile(fileCrlf, 'utf8')).toBe('a\r\nb\r\ninserted\r\n')

    const fileCr = join(dir, 'file-cr.txt')
    await writeFile(fileCr, 'a\rb\r', 'utf8')
    const rCr = await callCapability(
      edit,
      'fs.edit',
      { path: fileCr, old_string: 'b', new_string: 'b\ninserted' },
      makeContext(dir),
    )
    expect(rCr.error).toBe(false)
    expect(await readFile(fileCr, 'utf8')).toBe('a\rb\rinserted\r')
  })

  it('parse/serialize 往返保留混合行尾', async () => {
    const dir = await tempDir()
    const fileMixed = join(dir, 'file-mixed.txt')
    await writeFile(fileMixed, 'a\r\nb\nc\r', 'utf8')
    const edit = fileCapabilities['fs.edit']!
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: fileMixed, old_string: 'b', new_string: 'B' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(await readFile(fileMixed, 'utf8')).toBe('a\r\nB\nc\r')
  })

  it('buildDiff 输出上下文、删除与新增块', async () => {
    const diff = await previewDiff('a\nb\nc\n', 'b', 'B')
    expect(diff).toContain(' 1|a')
    expect(diff).toContain('- 2|b')
    expect(diff).toContain('+ 2|B')
    expect(diff).toContain(' 3|c')
  })

  it('buildDiff 折叠远端上下文并标注省略', async () => {
    const lines = Array.from({ length: 40 }, (_value, index) => `line${index}`)
    const changed = [...lines]
    changed[0] = 'first'
    changed[39] = 'last'
    const origText = `${lines.join('\n')}\n`
    const nextText = `${changed.join('\n')}\n`
    const diff = await previewDiff(origText, origText, nextText)
    expect(diff).toContain('...')
    expect(diff).toContain('- 1|line0')
    expect(diff).toContain('+ 1|first')
  })

  it('alignMiddle 超大区域退化为整段删除加新增', async () => {
    const original = Array.from({ length: 1500 }, (_value, index) => `o${index}`)
    const next = Array.from({ length: 1500 }, (_value, index) => `n${index}`)
    const origText = `${original.join('\n')}\n`
    const nextText = `${next.join('\n')}\n`
    const diff = await previewDiff(origText, origText, nextText)
    expect(diff).toContain('|o0')
    expect(diff).toContain('|n0')
  })

  it('审查 diff 完整保留大文本变更和代理对', async () => {
    const long = `${'a'.repeat(30 * 1024 - 1)}😀`
    const diff = await previewDiff('old\n', 'old\n', `${long}\n`)
    expect(diff).toContain(`+ 1|${long}`)
    expect(diff).not.toContain('diff truncated')
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
    const shown = toPosix(file)
    expect(textOf(result)).toBe(`${shown}:1:x\n${shown}:2:y\n${shown}:3:z`)
  })
})
