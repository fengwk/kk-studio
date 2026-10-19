import { afterEach, describe, expect, it, vi } from 'vitest'
import { chmod, mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  STOP_CANCELLED,
  STOP_NORMAL,
  STOP_TIMED_OUT,
  SearchControl,
  SearchStoppedError,
  findFiles,
  grepFiles,
  matchCenteredExcerpt,
  runSearchJob,
  type GrepOptions,
  type SearchReply,
} from '../../src/coding/search-core.js'
import { toPosix } from '../../src/coding/support.js'

const blockedReads = vi.hoisted(() => new Set<string>())

vi.mock('node:fs/promises', async (importOriginal) => {
  const original = await importOriginal<typeof import('node:fs/promises')>()
  return {
    ...original,
    async readdir(...args: Parameters<typeof original.readdir>) {
      const target = args[0]
      if (typeof target === 'string' && blockedReads.has(target)) {
        const error = new Error('EACCES') as NodeJS.ErrnoException
        error.code = 'EACCES'
        throw error
      }
      return original.readdir(...args)
    },
  }
})

/**
 * search-core 直接单元测试：通过纯入口与可控检查点验证遍历、忽略、匹配及停止时的 partial 结果；
 * 真实 Worker 端到端由 search.test.ts 与 worker-location.test.ts 覆盖。
 */

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-core-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(
    tempDirs.splice(0).map(async (dir) => {
      await chmod(dir, 0o755).catch(() => {})
      await rm(dir, { recursive: true, force: true })
    }),
  )
})

const createStopBuffer = (initial = STOP_NORMAL): SharedArrayBuffer => {
  const buf = new SharedArrayBuffer(4)
  Atomics.store(new Int32Array(buf), 0, initial)
  return buf
}
const noTimeout = (): SearchControl => new SearchControl(createStopBuffer())

const defaultGrep: GrepOptions = {
  multiline: false,
  literal: false,
  ignoreCase: false,
  include: null,
}

describe('findFiles', () => {
  it('按 glob 匹配文件名并跳过符号链接与 .git', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.ts'), '')
    await writeFile(join(dir, 'b.txt'), '')
    await mkdir(join(dir, '.git'))
    await writeFile(join(dir, '.git', 'c.ts'), '')
    await mkdir(join(dir, 'sub'))
    await writeFile(join(dir, 'sub', 'd.ts'), '')
    await symlink(join(dir, 'sub'), join(dir, 'link'))
    const outcome = await findFiles('*.ts', dir, 100, noTimeout())
    expect(outcome.matches.map((path) => path.split('/').pop()).sort()).toEqual(['a.ts', 'd.ts'])
    expect(outcome.limitReached).toBe(false)
  })

  it('带斜杠的 pattern 相对检索根匹配', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, 'src'))
    await writeFile(join(dir, 'src', 'a.ts'), '')
    await writeFile(join(dir, 'a.ts'), '')
    const outcome = await findFiles('src/*.ts', dir, 100, noTimeout())
    expect(outcome.matches).toHaveLength(1)
    expect(outcome.matches[0]?.endsWith('/src/a.ts')).toBe(true)
  })

  it('limit 达到时标注结果不完整', async () => {
    const dir = await tempDir()
    for (let index = 0; index < 5; index++) {
      await writeFile(join(dir, `f${index}.txt`), '')
    }
    const outcome = await findFiles('*.txt', dir, 2, noTimeout())
    expect(outcome.limitReached).toBe(true)
    expect(outcome.matches.length).toBe(3)
  })

  it('不可读目录计入 skipped 而非静默忽略', async () => {
    const dir = await tempDir()
    const blocked = join(dir, 'blocked')
    await mkdir(blocked)
    await writeFile(join(blocked, 'a.txt'), '')
    blockedReads.add(blocked)
    try {
      // readdir 对这个目录返回 EACCES，不能依赖 Windows 上无效的 mode 000。
      const outcome = await findFiles('*.none', dir, 100, noTimeout())
      expect(outcome.matches).toEqual([])
      expect(outcome.skipped.length).toBe(1)
    } finally {
      blockedReads.delete(blocked)
    }
  })

  it('检索目录本身被忽略时返回空结果', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, '.gitignore'), 'ignored/\n')
    await mkdir(join(dir, 'ignored'))
    await writeFile(join(dir, 'ignored', 'a.txt'), '')
    const outcome = await findFiles('*.txt', join(dir, 'ignored'), 100, noTimeout())
    expect(outcome.matches).toEqual([])
  })
})

describe('grepFiles', () => {
  it('单行匹配、literal、ignore_case 与 include', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'Hello\nworld\n')
    await writeFile(join(dir, 'b.ts'), 'Hello\n')
    const plain = await grepFiles('world', dir, 100, defaultGrep, noTimeout())
    expect(plain.lines[0]?.endsWith('a.txt:2:world')).toBe(true)

    const ignoreCase = await grepFiles('hello', dir, 100, { ...defaultGrep, ignoreCase: true }, noTimeout())
    expect(ignoreCase.lines.length).toBe(2)

    const include = await grepFiles('Hello', dir, 100, { ...defaultGrep, include: '*.ts' }, noTimeout())
    expect(include.lines).toHaveLength(1)
    expect(include.lines[0]?.includes('b.ts')).toBe(true)

    const literal = await grepFiles('wor.d', dir, 100, { ...defaultGrep, literal: true }, noTimeout())
    expect(literal.lines).toEqual([])
  })

  it('目录扫描静默跳过二进制文件', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'bin.dat'), Buffer.from([0x00, 0x01, 0x02]))
    await writeFile(join(dir, 'a.txt'), 'x\n')
    const outcome = await grepFiles('x', dir, 100, defaultGrep, noTimeout())
    expect(outcome.lines).toHaveLength(1)
    expect(outcome.skipped).toEqual([])
  })

  it('直接指定二进制文件时报错', async () => {
    const dir = await tempDir()
    const file = join(dir, 'bin.dat')
    await writeFile(file, Buffer.from([0x00, 0x01]))
    await expect(grepFiles('x', file, 100, defaultGrep, noTimeout())).rejects.toThrowError(
      /binary/,
    )
  })

  it('multiline 跨行匹配整段输出', async () => {
    const dir = await tempDir()
    const file = join(dir, 'm.txt')
    await writeFile(file, 'a\nb\nc\n')
    const outcome = await grepFiles(
      'a\\nb',
      file,
      100,
      { ...defaultGrep, multiline: true },
      noTimeout(),
    )
    expect(outcome.lines).toEqual([`${toPosix(file)}:1:a`, `${toPosix(file)}:2:b`])
  })

  it('multiline 空匹配推进 lastIndex', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'm.txt'), 'ab\n')
    const outcome = await grepFiles(
      'a*',
      join(dir, 'm.txt'),
      100,
      { ...defaultGrep, multiline: true },
      noTimeout(),
    )
    expect(outcome.lines.length).toBeGreaterThan(0)
  })

  it('limit 达到时标注 moreMatches', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'x\nx\nx\n')
    const outcome = await grepFiles('x', dir, 1, defaultGrep, noTimeout())
    expect(outcome.moreMatches).toBe(true)
    expect(outcome.lines).toHaveLength(1)
  })

  it('超长行按字符预算居中截取', async () => {
    const dir = await tempDir()
    const line = `${'a'.repeat(600)}match${'b'.repeat(600)}`
    await writeFile(join(dir, 'long.txt'), `${line}\n`)
    const outcome = await grepFiles('match', dir, 100, defaultGrep, noTimeout())
    expect(outcome.lines[0]).toContain('match')
    expect(outcome.lines[0]).toContain('line truncated to 500 chars')
  })

  it('超过单行字符上界的行按 skip 处理', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'huge.txt'), `${'a'.repeat(1024 * 1024 + 1)}\n`)
    const outcome = await grepFiles('a', dir, 100, defaultGrep, noTimeout())
    expect(outcome.skipped.some((entry) => entry.reason.includes('1048576'))).toBe(true)
  })

  it('不可读目录计入 skipped', async () => {
    const dir = await tempDir()
    const blocked = join(dir, 'blocked')
    await mkdir(blocked)
    await writeFile(join(blocked, 'a.txt'), 'x\n')
    blockedReads.add(blocked)
    try {
      const outcome = await grepFiles('zzz', dir, 100, defaultGrep, noTimeout())
      expect(outcome.skipped.length).toBe(1)
    } finally {
      blockedReads.delete(blocked)
    }
  })

  it('非文件非目录路径被拒绝', async () => {
    const dir = await tempDir()
    await expect(
      grepFiles('x', join(dir, 'missing'), 100, defaultGrep, noTimeout()),
    ).rejects.toThrowError()
  })
})

describe('gitignore 解析', () => {
  it('.git/info/exclude 生效', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git', 'info'), { recursive: true })
    await writeFile(join(dir, '.git', 'info', 'exclude'), 'a.txt\n')
    await writeFile(join(dir, 'a.txt'), '')
    await writeFile(join(dir, 'b.txt'), '')
    const outcome = await findFiles('*.txt', dir, 100, noTimeout())
    expect(outcome.matches.map((path) => path.split('/').pop())).toEqual(['b.txt'])
  })

  it('linked worktree 的 .git 文件与 commondir 生效', async () => {
    const dir = await tempDir()
    const gitDir = join(dir, 'real-git')
    await mkdir(join(gitDir, 'info'), { recursive: true })
    await writeFile(join(gitDir, 'info', 'exclude'), 'a.txt\n')
    const work = join(dir, 'work')
    await mkdir(work)
    await writeFile(join(work, '.git'), `gitdir: ${gitDir}\n`)
    await writeFile(join(work, 'a.txt'), '')
    await writeFile(join(work, 'b.txt'), '')
    const outcome = await findFiles('*.txt', work, 100, noTimeout())
    expect(outcome.matches.map((path) => path.split('/').pop())).toEqual(['b.txt'])
  })

  it('.git 指针非法或缺失时忽略规则为空', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, '.git'), 'not-a-pointer\n')
    await writeFile(join(dir, 'a.txt'), '')
    const outcome = await findFiles('*.txt', dir, 100, noTimeout())
    expect(outcome.matches.map((path) => path.split('/').pop())).toEqual(['a.txt'])
  })

  it('嵌套 .gitignore 的否定规则可重新包含', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, '.gitignore'), '*.log\n')
    await mkdir(join(dir, 'sub'))
    await writeFile(join(dir, 'sub', '.gitignore'), '!keep.log\n')
    await writeFile(join(dir, 'sub', 'keep.log'), '')
    await writeFile(join(dir, 'sub', 'drop.log'), '')
    const outcome = await findFiles('*.log', dir, 100, noTimeout())
    expect(outcome.matches.map((path) => path.split('/').pop())).toEqual(['keep.log'])
  })
})

describe('runSearchJob 与辅助', () => {
  it('find/grep 作业返回纯结果', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'hello\n')
    const find = (await runSearchJob({
      kind: 'find',
      pattern: '*.txt',
      path: dir,
      limit: 100,
      stopBuffer: createStopBuffer(),
    })) as Extract<SearchReply, { ok: true; kind: 'find' }>
    expect(find.ok).toBe(true)
    expect(find.matches).toHaveLength(1)

    const grep = (await runSearchJob({
      kind: 'grep',
      pattern: 'hello',
      path: dir,
      limit: 100,
      multiline: false,
      literal: false,
      ignoreCase: false,
      include: null,
      stopBuffer: createStopBuffer(),
    })) as Extract<SearchReply, { ok: true; kind: 'grep' }>
    expect(grep.lines).toHaveLength(1)
  })

  it('非法正则映射为 input 失败', async () => {
    const dir = await tempDir()
    const reply = await runSearchJob({
      kind: 'grep',
      pattern: '(',
      path: dir,
      limit: 100,
      multiline: false,
      literal: false,
      ignoreCase: false,
      include: null,
      stopBuffer: createStopBuffer(),
    })
    expect(reply).toMatchObject({ ok: false, error: 'input' })
  })

  it('直接文件二进制映射为 run 失败', async () => {
    const dir = await tempDir()
    const file = join(dir, 'bin.dat')
    await writeFile(file, Buffer.from([0x00]))
    const reply = await runSearchJob({
      kind: 'grep',
      pattern: 'x',
      path: file,
      limit: 100,
      multiline: false,
      literal: false,
      ignoreCase: false,
      include: null,
      stopBuffer: createStopBuffer(),
    })
    expect(reply).toMatchObject({ ok: false, error: 'run' })
  })

  it('SearchControl 遇原子标记抛出 SearchStoppedError', () => {
    const buffer = new SharedArrayBuffer(4)
    const view = new Int32Array(buffer)
    const control = new SearchControl(buffer)
    // 初始状态 0 不抛错
    expect(() => control.check()).not.toThrow()

    // 1 -> TIMED_OUT
    Atomics.store(view, 0, STOP_TIMED_OUT)
    expect(() => control.check()).toThrowError(SearchStoppedError)
    try {
      control.check()
    } catch (error) {
      expect(error).toBeInstanceOf(SearchStoppedError)
      expect((error as SearchStoppedError).reason).toBe('TIMED_OUT')
    }

    // 2 -> CANCELLED
    Atomics.store(view, 0, STOP_CANCELLED)
    try {
      control.check()
    } catch (error) {
      expect(error).toBeInstanceOf(SearchStoppedError)
      expect((error as SearchStoppedError).reason).toBe('CANCELLED')
    }
  })

  it('matchCenteredExcerpt 覆盖短行与居中截取', () => {
    expect(matchCenteredExcerpt('short', 0, 5)).toBe('short')
    const long = `${'a'.repeat(400)}needle${'b'.repeat(400)}`
    const excerpt = matchCenteredExcerpt(long, 400, 406)
    expect(excerpt).toContain('needle')
    expect(excerpt.startsWith('... ')).toBe(true)
    expect(excerpt).toContain('line truncated to 500 chars')
  })
})

describe('确定性 control 注入与 partial 结果保留', () => {
  class CountingControl extends SearchControl {
    public checks = 0
    constructor(
      private readonly stopAtCheck: number = Infinity,
      private readonly reason: 'TIMED_OUT' | 'CANCELLED' = 'TIMED_OUT',
    ) {
      super(createStopBuffer())
    }
    override check(): void {
      this.checks++
      if (this.checks >= this.stopAtCheck) {
        throw new SearchStoppedError(this.reason)
      }
    }
  }

  it('findFiles 在匹配部分文件后被 stop，保留 partial matches 且带 stopReason', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.ts'), '')
    await writeFile(join(dir, 'b.ts'), '')
    await writeFile(join(dir, 'c.ts'), '')

    const counter = new CountingControl()
    const complete = await findFiles('*.ts', dir, 10, counter)
    expect(complete.matches).toHaveLength(3)

    // 倒数第 2 次 check 触发 stop（确保已匹配到部分文件）
    const stopControl = new CountingControl(counter.checks - 2, 'TIMED_OUT')
    const outcome = await findFiles('*.ts', dir, 10, stopControl)
    expect(outcome.stopReason).toBe('TIMED_OUT')
    // 验证不伪造完成，且保留了中断前收集的 matches
    expect(outcome.matches.length).toBeGreaterThan(0)
    expect(outcome.matches.length).toBeLessThan(3)
    expect(outcome.limitReached).toBe(false)
  })

  it('findFiles empty timeout 返回空 matches 且带 stopReason', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.ts'), '')
    const control = new CountingControl(1, 'TIMED_OUT')
    const outcome = await findFiles('*.ts', dir, 10, control)
    expect(outcome.matches).toEqual([])
    expect(outcome.stopReason).toBe('TIMED_OUT')
  })

  it('grepFiles 在匹配部分文件后被 stop，保留 partial lines 且带 stopReason', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'needle 1\n')
    await writeFile(join(dir, 'b.txt'), 'needle 2\n')
    await writeFile(join(dir, 'c.txt'), 'needle 3\n')

    const counter = new CountingControl()
    const complete = await grepFiles('needle', dir, 10, defaultGrep, counter)
    expect(complete.lines).toHaveLength(3)

    // 在搜到部分文件后触发 stop
    const stopControl = new CountingControl(counter.checks - 6, 'TIMED_OUT')
    const outcome = await grepFiles('needle', dir, 10, defaultGrep, stopControl)
    expect(outcome.stopReason).toBe('TIMED_OUT')
    expect(outcome.lines.length).toBeGreaterThan(0)
    expect(outcome.lines.length).toBeLessThan(3)
    expect(outcome.moreMatches).toBe(false)
  })

  it('grepFiles empty timeout 返回空 lines 且带 stopReason', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'needle\n')
    const control = new CountingControl(1, 'TIMED_OUT')
    const outcome = await grepFiles('needle', dir, 10, defaultGrep, control)
    expect(outcome.lines).toEqual([])
    expect(outcome.stopReason).toBe('TIMED_OUT')
  })

  it('runSearchJob 携带 stopBuffer 遇到 stopReason 时正常回传 partial 纯结果', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), 'hello\n')
    const stopBuffer = createStopBuffer(STOP_TIMED_OUT)
    const reply = await runSearchJob({
      kind: 'grep',
      pattern: 'hello',
      path: dir,
      limit: 100,
      multiline: false,
      literal: false,
      ignoreCase: false,
      include: null,
      stopBuffer,
    })
    expect(reply.ok).toBe(true)
    if (reply.ok && reply.kind === 'grep') {
      expect(reply.stopReason).toBe('TIMED_OUT')
      expect(reply.lines).toEqual([])
    }
  })
})
