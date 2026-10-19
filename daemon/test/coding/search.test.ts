import { afterEach, describe, expect, it } from 'vitest'
import { mkdir, mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { resolveSearchWorkerEntry, searchCapabilities } from '../../src/coding/search.js'
import { toPosix } from '../../src/coding/support.js'
import { callCapability, makeContext, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-search-')))
  tempDirs.push(dir)
  return dir
}

/** find/grep 文本始终用 POSIX 分隔符展示已解析路径。 */
function displayed(file: string): string {
  return toPosix(file)
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

const find = searchCapabilities['fs.find']!
const grep = searchCapabilities['fs.grep']!

describe('fs.find', () => {
  it('glob 匹配并遵守分层 .gitignore', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await mkdir(join(dir, 'sub'))
    await writeFile(join(dir, '.gitignore'), '*.log\n')
    await writeFile(join(dir, 'a.ts'), '')
    await writeFile(join(dir, 'b.log'), '')
    await writeFile(join(dir, 'sub', 'c.ts'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.ts', path: dir }, makeContext(dir))
    const lines = textOf(result).split('\n').sort()
    expect(lines).toEqual([displayed(join(dir, 'a.ts')), displayed(join(dir, 'sub', 'c.ts'))].sort())
  })

  it('内层 .gitignore 的否定规则可重新包含', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await mkdir(join(dir, 'sub'))
    await writeFile(join(dir, '.gitignore'), '*.log\n')
    await writeFile(join(dir, 'sub', '.gitignore'), '!keep.log\n')
    await writeFile(join(dir, 'drop.log'), '')
    await writeFile(join(dir, 'sub', 'keep.log'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.log', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(displayed(join(dir, 'sub', 'keep.log')))
  })

  it('无匹配时返回明确文案', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.zzz', path: dir }, makeContext(dir))
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('No files found matching pattern')
  })

  it('相对路径被拒绝', async () => {
    const dir = await tempDir()
    const result = await callCapability(find, 'fs.find', { pattern: '*', path: 'rel' }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('absolute path')
  })
})

describe('fs.grep', () => {
  it('literal 与默认正则匹配', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'Hello\nworld\n')
    const literal = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'world', path: file, literal: true },
      makeContext(dir),
    )
    expect(textOf(literal)).toBe(`${displayed(file)}:2:world`)
    const regex = await callCapability(grep, 'fs.grep', { pattern: 'w.rld', path: file }, makeContext(dir))
    expect(textOf(regex)).toBe(`${displayed(file)}:2:world`)
  })

  it('ignore_case 生效', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'Hello\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'hello', path: file, ignore_case: true },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${displayed(file)}:1:Hello`)
  })

  it('include 过滤目录扫描', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.ts'), 'x\n')
    await writeFile(join(dir, 'b.js'), 'x\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x', path: dir, include: '*.ts' },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${displayed(join(dir, 'a.ts'))}:1:x`)
  })

  it('multiline 跨行匹配并整段输出', async () => {
    const dir = await tempDir()
    const file = join(dir, 'm.txt')
    await writeFile(file, 'a\nb\nc\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'a\\nb', path: file, multiline: true },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${displayed(file)}:1:a\n${displayed(file)}:2:b`)
  })

  it('limit 达到时明确标注结果不完整', async () => {
    const dir = await tempDir()
    const file = join(dir, 'l.txt')
    await writeFile(file, 'x\nx\nx\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x', path: file, literal: true, limit: 1 },
      makeContext(dir),
    )
    expect(textOf(result)).toContain('1 results limit reached')
  })

  it('目录扫描中二进制文件被静默跳过', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'bin.dat'), Buffer.from([0x00, 0x01]))
    const result = await callCapability(grep, 'fs.grep', { pattern: 'x', path: dir }, makeContext(dir))
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('No matches found')
  })

  it('取消信号映射为 CANCELLED 且空匹配时给出未完整提示', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const controller = new AbortController()
    controller.abort()
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'z', path: file },
      makeContext(dir, { signal: controller.signal }),
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'CANCELLED',
      search: { outcome: 'CANCELLED', incomplete: true },
    })
    expect(textOf(result)).toBe('[Search cancelled; results are incomplete.]')
  })

  it('find 在 empty timeout 时返回超时提示而非 No files found', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), '')
    const controller = new AbortController()
    controller.abort('TIMED_OUT')
    const result = await callCapability(
      find,
      'fs.find',
      { pattern: '*.zzz', path: dir },
      makeContext(dir, { signal: controller.signal }),
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'TIMEOUT',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
    expect(textOf(result)).toBe('[Search timed out; results are incomplete.]')
  })

  it('grep 在 empty timeout 时返回超时提示而非 No matches found', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'hello\n')
    const controller = new AbortController()
    controller.abort('TIMED_OUT')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'zzz', path: dir },
      makeContext(dir, { signal: controller.signal }),
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'TIMEOUT',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
    expect(textOf(result)).toBe('[Search timed out; results are incomplete.]')
  })

  it('真实 worker 收到超时信号后停止退出并返回明确未完整结果', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'match line\n')
    await writeFile(join(dir, 'b.txt'), 'match line\n')
    const controller = new AbortController()
    controller.abort('TIMED_OUT')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'match line', path: dir },
      makeContext(dir, { signal: controller.signal }),
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'TIMEOUT',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
    expect(textOf(result)).toContain('[Search timed out; results are incomplete.]')
  })

  it('超长无换行文件 (longline) 搜索中收到停止信号正常退出并返回未完整结果', async () => {
    const dir = await tempDir()
    const longFile = join(dir, 'long.txt')
    await writeFile(longFile, 'a'.repeat(64 * 1024))
    const controller = new AbortController()
    controller.abort('TIMED_OUT')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'needle', path: longFile },
      makeContext(dir, { signal: controller.signal }),
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({
      code: 'TIMEOUT',
      search: { outcome: 'TIMED_OUT', incomplete: true },
    })
    expect(textOf(result)).toContain('[Search timed out; results are incomplete.]')
  })

  it('非法正则被拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: '(', path: file }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('Invalid regex')
  })

  it('resolveSearchWorkerEntry 在无效 base 下回落并返回 null', () => {
    expect(resolveSearchWorkerEntry(new URL('file:///tmp/not-exist/entry.js'))).toBeNull()
  })
})
