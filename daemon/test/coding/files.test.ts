import { afterEach, describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { syncBuiltinESMExports } from 'node:module'
import { promises as fsPromises } from 'node:fs'
import { fileCapabilities } from '../../src/coding/files.js'
import type { CapabilityContext } from '../../src/contracts.js'
import { callCapability, makeContext, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-files-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

const read = fileCapabilities['fs.read']!
const write = fileCapabilities['fs.write']!
const edit = fileCapabilities['fs.edit']!

// 到期与用户取消是不同事实；停止后的写入/编辑也不能绕过提交前检查。
describe('file capability stop reason', () => {
  it.each(['fs.read', 'fs.write', 'fs.edit', 'fs.edit.preview'])(
    '%s keeps TIMED_OUT distinct from caller cancellation',
    async (capabilityId) => {
      const dir = await tempDir()
      const file = join(dir, 'stopped.txt')
      await writeFile(file, 'before\n')
      const controller = new AbortController()
      controller.abort('TIMED_OUT')
      const args = capabilityId === 'fs.read'
        ? { path: file }
        : capabilityId === 'fs.write'
          ? { path: file, content: 'after\n' }
          : { path: file, old_string: 'before', new_string: 'after' }
      const result = await callCapability(
        fileCapabilities[capabilityId]!, capabilityId, args,
        makeContext(dir, { signal: controller.signal }),
      )
      expect(result.error).toBe(true)
      expect(result.details.code).toBe('TIMEOUT')
      expect(textOf(result)).toContain('Operation timed out')
      expect(textOf(result)).not.toContain('Operation cancelled')
      expect(await readFile(file, 'utf8')).toBe('before\n')
    },
  )
})

describe('file capability exit confirmation', () => {
  it('confirms exactly once on success and preflight rejection', async () => {
    // 文件能力没有遗留 worker：成功与返回前的失败都已在调用返回时结束本次范围。
    const dir = await tempDir()
    const file = join(dir, 'confirm.txt')
    let count = 0
    const context = (): CapabilityContext => ({
      ...makeContext(dir),
      confirmExecutionExit: () => {
        count += 1
      },
    })

    const readResult = await callCapability(read, 'fs.read', { path: 'relative.txt' }, context())
    expect(readResult.error).toBe(true)
    expect(count).toBe(1)

    const written = await callCapability(write, 'fs.write', { path: file, content: 'after\n' }, context())
    expect(written.error).toBe(false)
    expect(count).toBe(2)

    const edited = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'missing', new_string: 'x' },
      context(),
    )
    expect(edited.error).toBe(true)
    expect(count).toBe(3)
  })
})

describe('fs.read', () => {
  it('渲染有界窗口 header、offset 行号与 ends_with_newline', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'alpha\nbeta\ngamma\n')
    const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe(
      [
        `path: ${file.replace(/\\/g, '/')}`,
        'ends_with_newline: yes',
        'range: 1:1-3:5',
        '',
        '1|alpha',
        '2|beta',
        '3|gamma',
      ].join('\n'),
    )
  })

  it('截断时报告 truncation_reason/next 与尾部警告', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'a\nb\nc\n')
    const result = await callCapability(read, 'fs.read', { path: file, limit: 2 }, makeContext(dir))
    const text = textOf(result)
    expect(text).toContain('truncated: yes')
    expect(text).toContain('truncation_reason: line_limit')
    expect(text).toContain('next: 3:1')
    expect(text).toContain('[TRUNCATED: More file content remains. Next position: line 3, column 1.]')
  })

  it('起点超过 EOF 输出 range: empty', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'one\n')
    const result = await callCapability(read, 'fs.read', { path: file, offset: 5 }, makeContext(dir))
    expect(textOf(result)).toContain('range: empty')
  })

  it('按 BOM 判定 UTF-16LE 并折叠 CRLF', async () => {
    const dir = await tempDir()
    const file = join(dir, 'u16.txt')
    const body = '中\r\n文'
    const bytes = Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(body, 'utf16le')])
    await writeFile(file, bytes)
    const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
    const text = textOf(result)
    expect(text).toContain('ends_with_newline: no')
    expect(text).toContain('1|中')
    expect(text).toContain('2|文')
  })

  it("目录清单按分页与 '/' 标记目录", async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'b.txt'), 'x')
    await mkdir(join(dir, 'sub'))
    const result = await callCapability(read, 'fs.read', { path: dir }, makeContext(dir))
    const text = textOf(result)
    expect(text).toContain('kind: directory')
    expect(text).toContain('b.txt')
    expect(text).toContain('sub/')
  })

  it('图片以二进制内容返回并带媒体类型', async () => {
    const dir = await tempDir()
    const file = join(dir, 'p.png')
    await writeFile(file, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00]))
    const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
    expect(result.contents[0]).toMatchObject({ type: 'binary', mediaType: 'image/png' })
  })

  it('rejects oversized media before reading content and accepts an exact-limit image', async () => {
    const dir = await tempDir()
    const oversized = join(dir, 'oversized.png')
    const limit = 16 * 1_048_576
    const bytes = Buffer.alloc(limit + 1)
    bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    await writeFile(oversized, bytes)

    const originalReadFile = fsPromises.readFile
    let wholeFileReads = 0
    Object.defineProperty(fsPromises, 'readFile', {
      configurable: true,
      value: (...args: Parameters<typeof fsPromises.readFile>) => {
        wholeFileReads += 1
        return Reflect.apply(originalReadFile, fsPromises, args)
      },
    })
    syncBuiltinESMExports()
    try {
      const rejected = await callCapability(
        read,
        'fs.read',
        { path: oversized },
        { ...makeContext(dir), maxOutputBytes: limit },
      )
      expect(rejected.error).toBe(true)
      expect(textOf(rejected)).toBe(
        'File is 16.000001 MiB, which exceeds the tool output size limit of 16 MiB. The file was not read. Use a smaller file, for example by converting or splitting it with bash, or ask the user to raise the limit in system settings.',
      )
      expect(wholeFileReads).toBe(0)
    } finally {
      Object.defineProperty(fsPromises, 'readFile', { configurable: true, value: originalReadFile })
      syncBuiltinESMExports()
    }

    const atLimit = join(dir, 'at-limit.png')
    bytes.fill(0, 8)
    await writeFile(atLimit, bytes.subarray(0, limit))
    const accepted = await callCapability(
      read,
      'fs.read',
      { path: atLimit },
      { ...makeContext(dir), maxOutputBytes: limit },
    )
    expect(accepted.error).toBe(false)
    expect(accepted.contents[0]).toMatchObject({ type: 'binary', mediaType: 'image/png' })
  })

  it('音视频与 PDF 按原字节返回并带嗅探出的媒体类型与文件名', async () => {
    // 与 Java MediaTypeSniffer 共用的 platform 测试资源样例。
    const samples = fileURLToPath(
      new URL(
        '../../../platform/runtime/src/test/resources/fun/fengwk/kkstudio/platform/storage/media/',
        import.meta.url,
      ),
    )
    const dir = await tempDir()
    for (const [sample, mediaType] of [
      ['video/sample.mp4', 'video/mp4'],
      ['video/sample.mov', 'video/quicktime'],
      ['audio/sample.wav', 'audio/wav'],
      ['audio/sample-id3.mp3', 'audio/mpeg'],
      ['audio/sample-frame.mp3', 'audio/mpeg'],
      ['doc/sample.pdf', 'application/pdf'],
    ] as const) {
      const bytes = new Uint8Array(readFileSync(join(samples, sample)))
      // 伪装反例沿用真实扩展名落地：判型只看字节，扩展名不参与。
      const file = join(dir, sample.replace('/', '-'))
      await writeFile(file, bytes)
      const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
      expect(result.error, file).toBe(false)
      const content = result.contents[0]
      expect(content, file).toMatchObject({ type: 'binary', mediaType, name: file.replace(/\\/g, '/').split('/').pop() })
      if (content.type === 'binary') {
        expect(Buffer.from(content.bytes).equals(Buffer.from(bytes)), file).toBe(true)
      }
    }
  })

  it('伪装成媒体扩展名的普通文本按文本读取，扩展名不参与判定', async () => {
    const dir = await tempDir()
    const file = join(dir, 'movie.mp4')
    await writeFile(file, 'plain text, not a movie\n')
    const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
    expect(result.error).toBe(false)
    expect(textOf(result)).toContain('plain text, not a movie')
  })

  it('相对路径先于文件系统访问被拒绝', async () => {
    const dir = await tempDir()
    const result = await callCapability(read, 'fs.read', { path: 'rel.txt' }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('absolute path')
  })

  it('二进制文本以受控错误拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'bin.dat')
    await writeFile(file, Buffer.from([0x00, 0x01, 0x02]))
    const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('binary')
  })
})

describe('fs.write', () => {
  it('创建文件并写入原样内容', async () => {
    const dir = await tempDir()
    const file = join(dir, 'new.txt')
    const result = await callCapability(
      write,
      'fs.write',
      { path: file, content: 'hello\n' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toContain('Created')
    expect(await readFile(file, 'utf8')).toBe('hello\n')
  })

  it('覆盖既有文件时保留 BOM 与编码', async () => {
    const dir = await tempDir()
    const file = join(dir, 'bom.txt')
    await writeFile(file, Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('old')]))
    const result = await callCapability(
      write,
      'fs.write',
      { path: file, content: 'new' },
      makeContext(dir),
    )
    expect(textOf(result)).toContain('Overwrote')
    expect(Array.from(await readFile(file))).toEqual([0xef, 0xbb, 0xbf, 0x6e, 0x65, 0x77])
  })

  it('目标是目录时拒绝', async () => {
    const dir = await tempDir()
    const target = join(dir, 'adir')
    await mkdir(target)
    const result = await callCapability(
      write,
      'fs.write',
      { path: target, content: 'x' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('directory')
  })

  it('悬空符号链接拒绝创建', async () => {
    const dir = await tempDir()
    const link = join(dir, 'dangling')
    await symlink(join(dir, 'missing'), link)
    const result = await callCapability(
      write,
      'fs.write',
      { path: link, content: 'x' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('symbolic link')
  })
})

describe('fs.edit', () => {
  it('唯一匹配替换并保留 CRLF 行尾', async () => {
    const dir = await tempDir()
    const file = join(dir, 'crlf.txt')
    await writeFile(file, 'one\r\ntwo\r\nthree\r\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'two', new_string: 'TWO' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toContain('Replacements: 1')
    expect(await readFile(file, 'utf8')).toBe('one\r\nTWO\r\nthree\r\n')
  })

  it('多处匹配且未 replace_all 时拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'dup.txt')
    await writeFile(file, 'x\nx\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'x', new_string: 'y' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('use replace_all')
  })

  it('replace_all 替换全部匹配', async () => {
    const dir = await tempDir()
    const file = join(dir, 'all.txt')
    await writeFile(file, 'x\nx\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'x', new_string: 'y', replace_all: true },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toContain('Replacements: 2')
    expect(await readFile(file, 'utf8')).toBe('y\ny\n')
  })

  it('找不到匹配时拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'none.txt')
    await writeFile(file, 'abc\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'zzz', new_string: 'q' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('Could not find old_string')
  })

  it('old_string 为空时拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'e.txt')
    await writeFile(file, 'abc\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: '', new_string: 'q' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('old_string must not be empty')
  })

  it('rejects writing to a directory', async () => {
    const dir = await tempDir()
    const subDir = join(dir, 'sub')
    await mkdir(subDir)
    const result = await callCapability(
      write,
      'fs.write',
      { path: subDir, content: 'hello' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('path is a directory')
  })

  it('handles mixed line endings with multi-line replacements and non-newline endings', async () => {
    const dir = await tempDir()
    const mixedFile = join(dir, 'mixed.txt')
    await writeFile(mixedFile, 'line1\r\nline2\nline3\r\n')
    const mixedResult = await callCapability(
      edit,
      'fs.edit',
      { path: mixedFile, old_string: 'line2\n', new_string: 'sub1\nsub2\nsub3\n' },
      makeContext(dir),
    )
    expect(mixedResult.error).toBe(false)

    const noEndingFile = join(dir, 'no-ending.txt')
    await writeFile(noEndingFile, 'hello')
    const noEndingResult = await callCapability(
      edit,
      'fs.edit',
      { path: noEndingFile, old_string: 'o', new_string: 'p' },
      makeContext(dir),
    )
    expect(noEndingResult.error).toBe(false)
    expect(await readFile(noEndingFile, 'utf8')).toBe('hellp')
  })
})
