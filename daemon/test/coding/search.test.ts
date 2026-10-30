import { afterEach, describe, expect, it, vi } from 'vitest'
import { mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createSearchCapabilities, matchCenteredExcerpt, searchCapabilities } from '../../src/coding/search.js'
import { runRipgrep } from '../../src/coding/ripgrep.js'
import { toPosix } from '../../src/coding/support.js'
import { callCapability, makeContext, textOf, type ContextOverrides } from './helpers.js'
import { installFakeRipgrep, waitForFakePid } from './fake-ripgrep.js'

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

function expectProcessGone(pid: number): void {
  expect(() => process.kill(pid, 0)).toThrow()
}

function confirmingContext(
  dir: string,
  overrides: ContextOverrides = {},
): { context: ReturnType<typeof makeContext>; confirmations: () => number } {
  let count = 0
  const context = {
    ...makeContext(dir, overrides),
    confirmExecutionExit: () => {
      count += 1
    },
  }
  return { context, confirmations: () => count }
}

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

  it('包含隐藏文件与目录，但排除 .git', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await mkdir(join(dir, '.hidden'))
    await writeFile(join(dir, '.hidden.ts'), '')
    await writeFile(join(dir, '.hidden', 'a.ts'), '')
    await writeFile(join(dir, '.git', 'config'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.ts', path: dir }, makeContext(dir))
    const lines = textOf(result).split('\n').sort()
    expect(lines).toEqual(
      [displayed(join(dir, '.hidden.ts')), displayed(join(dir, '.hidden', 'a.ts'))].sort(),
    )
  })

  it('没有 .git 目录时仍应用 .gitignore', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, '.gitignore'), '*.log\n')
    await writeFile(join(dir, 'a.log'), '')
    await writeFile(join(dir, 'a.ts'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.log', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe('No files found matching pattern')
  })

  it('.git/info/exclude 生效', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git', 'info'), { recursive: true })
    await writeFile(join(dir, '.git', 'info', 'exclude'), 'a.txt\n')
    await writeFile(join(dir, 'a.txt'), '')
    await writeFile(join(dir, 'b.txt'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.txt', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(displayed(join(dir, 'b.txt')))
  })

  it('linked worktree 使用 commondir 的共享 exclude', async () => {
    const dir = await tempDir()
    const repository = join(dir, 'checkout')
    const metadata = join(dir, 'metadata')
    const gitdir = join(metadata, 'worktrees', 'checkout')
    await mkdir(repository)
    await mkdir(gitdir, { recursive: true })
    await mkdir(join(metadata, 'info'))
    await writeFile(join(repository, '.git'), `gitdir: ${gitdir}\n`)
    await writeFile(join(gitdir, 'commondir'), '../..\n')
    await writeFile(join(metadata, 'info', 'exclude'), 'blocked.txt\n')
    await writeFile(join(repository, 'blocked.txt'), 'needle\n')
    await writeFile(join(repository, 'kept.txt'), 'needle\n')
    const found = await callCapability(
      find, 'fs.find', { pattern: '*.txt', path: repository }, makeContext(dir),
    )
    expect(textOf(found)).toBe(displayed(join(repository, 'kept.txt')))
    const grepped = await callCapability(
      grep, 'fs.grep', { pattern: 'needle', path: repository }, makeContext(dir),
    )
    expect(textOf(grepped)).toBe(`${displayed(join(repository, 'kept.txt'))}:1:needle`)
  })

  it('不读取 .ignore（保持现有 gitignore 契约）', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, '.ignore'), 'a.txt\n')
    await writeFile(join(dir, 'a.txt'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*.txt', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(displayed(join(dir, 'a.txt')))
  })

  it('不跟随符号链接', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, 'sub'))
    await writeFile(join(dir, 'sub', 'a.ts'), '')
    await symlink(join(dir, 'sub'), join(dir, 'link'))
    const result = await callCapability(find, 'fs.find', { pattern: '*.ts', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(displayed(join(dir, 'sub', 'a.ts')))
  })

  it('带斜杠的 pattern 相对检索根匹配', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, 'src'))
    await writeFile(join(dir, 'src', 'a.ts'), '')
    await writeFile(join(dir, 'a.ts'), '')
    const result = await callCapability(find, 'fs.find', { pattern: 'src/*.ts', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(displayed(join(dir, 'src', 'a.ts')))
  })

  it('limit 达到时明确标注结果不完整', async () => {
    const dir = await tempDir()
    for (let index = 0; index < 5; index++) {
      await writeFile(join(dir, `f${index}.txt`), '')
    }
    const result = await callCapability(
      find,
      'fs.find',
      { pattern: '*.txt', path: dir, limit: 2 },
      makeContext(dir),
    )
    const text = textOf(result)
    expect(text).toContain('[2 results limit reached. Refine the pattern or raise limit.]')
    expect(text.split('\n').filter((line) => line.startsWith(displayed(dir)))).toHaveLength(2)
  })

  it('显式指定被忽略的目录时仍检索该目录（ripgrep 语义）', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, '.gitignore'), 'ignored/\n')
    await mkdir(join(dir, 'ignored'))
    await writeFile(join(dir, 'ignored', 'a.txt'), '')
    const result = await callCapability(
      find,
      'fs.find',
      { pattern: '*.txt', path: join(dir, 'ignored') },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(displayed(join(dir, 'ignored', 'a.txt')))
  })

  it('.git 作为检索根不返回文件', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await writeFile(join(dir, '.git', 'config'), '')
    const result = await callCapability(find, 'fs.find', { pattern: '*', path: join(dir, '.git') }, makeContext(dir))
    expect(textOf(result)).toBe('No files found matching pattern')
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

  it('文件路径被拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, '')
    const result = await callCapability(find, 'fs.find', { pattern: '*', path: file }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('path must be a directory')
  })

  it('非法 glob 被拒绝而不是报告无匹配', async () => {
    const dir = await tempDir()
    const result = await callCapability(find, 'fs.find', { pattern: '[', path: dir }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('Invalid glob')
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

  it('ignore_case 生效且默认区分大小写', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'Hello\n')
    const insensitive = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'hello', path: file, ignore_case: true },
      makeContext(dir),
    )
    expect(textOf(insensitive)).toBe(`${displayed(file)}:1:Hello`)
    const sensitive = await callCapability(grep, 'fs.grep', { pattern: 'hello', path: file }, makeContext(dir))
    expect(textOf(sensitive)).toBe('No matches found')
  })

  it('include 过滤目录扫描且不会重新包含被忽略的文件', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await writeFile(join(dir, '.gitignore'), 'ignored.ts\n')
    await writeFile(join(dir, 'kept.ts'), 'x\n')
    await writeFile(join(dir, 'ignored.ts'), 'x\n')
    await writeFile(join(dir, 'b.js'), 'x\n')
    await writeFile(join(dir, '.hidden.ts'), 'x\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x', path: dir, include: '*.ts' },
      makeContext(dir),
    )
    expect(textOf(result).split('\n').sort()).toEqual(
      [displayed(join(dir, 'kept.ts')), displayed(join(dir, '.hidden.ts'))]
        .map((path) => `${path}:1:x`)
        .sort(),
    )
  })

  it('直接指定文件时不应用 include', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.ts')
    await writeFile(file, 'x\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x', path: file, include: '*.js' },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${displayed(file)}:1:x`)
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

  it('multiline 下 ^ 仍按行锚定', async () => {
    const dir = await tempDir()
    const file = join(dir, 'm.txt')
    await writeFile(file, 'a\nb\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: '^b', path: file, multiline: true },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${displayed(file)}:2:b`)
  })

  it('CRLF 文件上 $ 在行尾匹配', async () => {
    const dir = await tempDir()
    const file = join(dir, 'crlf.txt')
    await writeFile(file, 'foo\r\nbar\r\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: 'foo$', path: file }, makeContext(dir))
    expect(textOf(result)).toBe(`${displayed(file)}:1:foo`)
  })

  it('Unicode 字符类保持 RE2 的 ASCII 语义', async () => {
    const dir = await tempDir()
    const file = join(dir, 'unicode.txt')
    await writeFile(file, '中文\nline\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: '\\w+', path: file }, makeContext(dir))
    expect(textOf(result)).toBe(`${displayed(file)}:2:line`)
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

  it('二进制文件在 NUL 之前的匹配也不输出', async () => {
    const dir = await tempDir()
    // rg 会先报告 NUL 之前的匹配；旧契约按前 8KiB 探针把整文件视为二进制，必须整文件跳过。
    await writeFile(join(dir, 'bin.dat'), Buffer.from('match\n\u0000tail\n'))
    const result = await callCapability(grep, 'fs.grep', { pattern: '^match$', path: dir }, makeContext(dir))
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('No matches found')
  })

  it('直接指定二进制文件时报错', async () => {
    const dir = await tempDir()
    const file = join(dir, 'bin.dat')
    await writeFile(file, Buffer.from([0x00, 0x01]))
    const result = await callCapability(grep, 'fs.grep', { pattern: 'x', path: file }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('binary')
  })

  it('UTF-16 BOM 文本不被当作二进制', async () => {
    const dir = await tempDir()
    const file = join(dir, 'utf16.txt')
    await writeFile(
      file,
      Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from('hello\nworld\n', 'utf16le')]),
    )
    const result = await callCapability(grep, 'fs.grep', { pattern: 'world', path: file }, makeContext(dir))
    expect(textOf(result)).toBe(`${displayed(file)}:2:world`)
  })

  it('搜索隐藏文件但排除 .git', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await writeFile(join(dir, '.git', 'config'), 'x\n')
    await writeFile(join(dir, '.hidden.txt'), 'x\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: 'x', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(`${displayed(join(dir, '.hidden.txt'))}:1:x`)
  })

  it.each(['.git', '.git/config'])('直接指定 %s 也不暴露 Git 元数据', async (target) => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    await writeFile(join(dir, '.git', 'config'), 'needle\n')
    const result = await callCapability(
      grep, 'fs.grep', { pattern: 'needle', path: join(dir, target) }, makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('No matches found')
  })

  it.each(['pattern', 'include'])('拒绝 %s 中的 NUL，而不是尝试启动 rg', async (argument) => {
    const dir = await tempDir()
    const result = await callCapability(
      grep, 'fs.grep', { pattern: 'x', path: dir, [argument]: '\0' }, makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain(`${argument} must not contain NUL`)
  })

  it('.git/info/exclude 生效且 .ignore 不生效', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git', 'info'), { recursive: true })
    await writeFile(join(dir, '.git', 'info', 'exclude'), 'excluded.txt\n')
    await writeFile(join(dir, '.ignore'), 'ignored.txt\n')
    await writeFile(join(dir, 'excluded.txt'), 'x\n')
    await writeFile(join(dir, 'ignored.txt'), 'x\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: '^x$', path: dir }, makeContext(dir))
    expect(textOf(result)).toBe(`${displayed(join(dir, 'ignored.txt'))}:1:x`)
  })

  it('超长行按字符预算居中截取', async () => {
    const dir = await tempDir()
    const file = join(dir, 'long.txt')
    const line = `${'a'.repeat(600)}match${'b'.repeat(600)}`
    await writeFile(file, `${line}\n`)
    const result = await callCapability(grep, 'fs.grep', { pattern: 'match', path: file }, makeContext(dir))
    const text = textOf(result)
    expect(text).toContain('match')
    expect(text).toContain('line truncated to 500 chars')
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

  it('非法正则被拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: '(', path: file }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('Invalid regex')
  })
})

describe('rg 进程生命周期与失败语义', () => {
  it('启动边界收到已中止的 signal 时仍等待子进程关闭', async () => {
    const controller = new AbortController()
    controller.abort('CANCELLED')
    const result = await runRipgrep(
      { binary: process.execPath, prefixArgs: ['-e', 'setInterval(() => {}, 1000)'] },
      [], process.cwd(), controller.signal, () => true,
    )
    expect(result.stopped).toBe('abort')
    expect(result.signal).toBe('SIGKILL')
  })

  it('rg 的诊断捕获不超过 64 KiB 字符，避免失败输出无限积累', async () => {
    const dir = await tempDir()
    const fake = await installFakeRipgrep(dir, {
      target: join(dir, 'a.txt'), matches: 0, stderr: 'x'.repeat(128 * 1024), exitCode: 2,
    })
    const result = await runRipgrep(
      fake.command, [], dir, new AbortController().signal, () => true,
    )
    expect(result.code).toBe(2)
    expect(result.stderr).toHaveLength(64 * 1024)
  })

  it.each(['missing', 'invalid'] as const)('rg 启动失败（%s）不是无匹配，并确认未启动', async (kind) => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const binary = kind === 'missing' ? join(dir, 'missing-rg') : '\0'
    const capabilities = createSearchCapabilities({ binary })
    const confirmation = confirmingContext(dir)
    const result = await callCapability(
      capabilities['fs.grep']!, 'fs.grep', { pattern: 'x', path: file }, confirmation.context,
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('ripgrep could not be started')
    expect(confirmation.confirmations()).toBe(1)
  })

  it('损坏的 rg JSON 导致运行失败并回收进程', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const fake = await installFakeRipgrep(dir, {
      target: file, matches: 0, rawStdout: 'not JSON\n', hang: true,
    })
    const capabilities = createSearchCapabilities(fake.command)
    const resultPromise = callCapability(
      capabilities['fs.grep']!, 'fs.grep', { pattern: 'x', path: file }, makeContext(dir),
    )
    const pid = await waitForFakePid(fake.pidFile)
    expect((await resultPromise).error).toBe(true)
    expectProcessGone(pid)
  })

  it('超时终止正在运行的 rg 并回收进程，保留 partial 结果', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'match line\n')
    const fake = await installFakeRipgrep(dir, { target: file, hang: true })
    const capabilities = createSearchCapabilities(fake.command)
    const controller = new AbortController()
    const parse = JSON.parse
    // PID 只证明子进程启动。观察真实解析器消费首条匹配，待当前 stdout 回调收集完该条结果后才取消。
    const parsed = vi.spyOn(JSON, 'parse').mockImplementation((text, reviver) => {
      const value = parse(text, reviver)
      if (value?.type === 'match' && value.data?.path?.text === file) {
        queueMicrotask(() => controller.abort('TIMED_OUT'))
      }
      return value
    })
    try {
      const resultPromise = callCapability(
        capabilities['fs.grep']!,
        'fs.grep',
        { pattern: 'match', path: file },
        makeContext(dir, { signal: controller.signal }),
      )
      const pid = await waitForFakePid(fake.pidFile)
      const result = await resultPromise
      expect(result.details).toMatchObject({
        code: 'TIMEOUT',
        search: { outcome: 'TIMED_OUT', incomplete: true },
      })
      expect(textOf(result)).toContain(`${displayed(file)}:1:match line`)
      expect(textOf(result)).toContain('[Search timed out; results are incomplete.]')
      expectProcessGone(pid)
    } finally {
      parsed.mockRestore()
      controller.abort('CANCELLED')
    }
  })

  it('达到结果上限时主动终止 rg 并回收进程', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'match line\n')
    const fake = await installFakeRipgrep(dir, { target: file, matches: 3, hang: true })
    const capabilities = createSearchCapabilities(fake.command)
    const resultPromise = callCapability(
      capabilities['fs.grep']!,
      'fs.grep',
      { pattern: 'match', path: file, limit: 2 },
      makeContext(dir),
    )
    const pid = await waitForFakePid(fake.pidFile)
    const result = await resultPromise
    expect(textOf(result).split('\n').filter((line) => line.startsWith(displayed(file)))).toHaveLength(2)
    expect(textOf(result)).toContain('2 results limit reached')
    expectProcessGone(pid)
  })

  it('rg 退出码 2 的路径错误进入 skipped，不报告为无匹配', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const blocked = join(dir, 'blocked')
    const fake = await installFakeRipgrep(dir, {
      target: file,
      matches: 0,
      exitCode: 2,
      stderr: `rg: ${blocked}: Permission denied (os error 13)\n`,
    })
    const capabilities = createSearchCapabilities(fake.command)
    const result = await callCapability(
      capabilities['fs.grep']!,
      'fs.grep',
      { pattern: 'x', path: dir },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('could not be searched')
    expect(textOf(result)).not.toBe('No matches found')
  })

  it('rg 的不可解析失败成为运行错误，不报告为无匹配', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x\n')
    const fake = await installFakeRipgrep(dir, {
      target: file,
      matches: 0,
      exitCode: 2,
      stderr: 'rg: unexpected fatal failure\n',
    })
    const capabilities = createSearchCapabilities(fake.command)
    const result = await callCapability(
      capabilities['fs.grep']!,
      'fs.grep',
      { pattern: 'x', path: dir },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('ripgrep failed while searching')
    expect(textOf(result)).not.toBe('No matches found')
  })

  it('正常完成与协作停止都只在 rg 退出后确认执行范围', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'match line\n')
    const normal = confirmingContext(dir)
    const normalResult = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'match', path: file },
      normal.context,
    )
    expect(normalResult.error).toBe(false)
    expect(normal.confirmations()).toBe(1)

    const fake = await installFakeRipgrep(dir, { target: file, hang: true })
    const capabilities = createSearchCapabilities(fake.command)
    const stopped = confirmingContext(dir)
    const controller = new AbortController()
    const resultPromise = callCapability(
      capabilities['fs.grep']!,
      'fs.grep',
      { pattern: 'match', path: file },
      { ...stopped.context, signal: controller.signal },
    )
    await waitForFakePid(fake.pidFile)
    controller.abort('TIMED_OUT')
    await resultPromise
    expect(stopped.confirmations()).toBe(1)
  })
})

describe('matchCenteredExcerpt', () => {
  it('短行原样返回，长行居中截取', () => {
    expect(matchCenteredExcerpt('short', 0, 5)).toBe('short')
    const long = `${'a'.repeat(400)}needle${'b'.repeat(400)}`
    const excerpt = matchCenteredExcerpt(long, 400, 406)
    expect(excerpt).toContain('needle')
    expect(excerpt.startsWith('... ')).toBe(true)
    expect(excerpt).toContain('line truncated to 500 chars')
  })
})
