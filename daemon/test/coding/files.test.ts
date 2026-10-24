import { afterEach, describe, expect, it } from 'vitest'
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
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

  it('目录清单按分页与 '/' 标记目录', async () => {
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
})
