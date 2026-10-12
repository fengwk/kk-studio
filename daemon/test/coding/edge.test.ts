import { afterEach, describe, expect, it } from 'vitest'
import { chmod, mkdir, mkdtemp, open, readFile, realpath, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { textResult } from '../../src/contracts.js'
import { fileCapabilities, setFileCapabilityHooks } from '../../src/coding/files.js'
import { searchCapabilities, matchCenteredExcerpt } from '../../src/coding/search.js'
import {
  ByteTailBuffer,
  OutputSpool,
  TextOutputStore,
  alignToCharacterBoundary,
  leadingCharacterBoundary,
  spoolLargeTextResult,
  type PrivateFs,
} from '../../src/coding/output.js'
import { commitTextFile, pathExists } from '../../src/coding/commit.js'
import {
  createUtf16Decoder,
  decodeText,
  encodeText,
  forEachLine,
} from '../../src/coding/text.js'
import { toPosix } from '../../src/coding/support.js'
import { callCapability, makeContext, openTestStore, textOf } from './helpers.js'
import { ensurePrivateDirectory } from '../../src/native/index.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-edge-')))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(
    tempDirs.splice(0).map(async (dir) => {
      try {
        await rm(dir, { recursive: true, force: true })
      } catch {
        await chmod(dir, 0o755).catch(() => {})
        await rm(dir, { recursive: true, force: true }).catch(() => {})
      }
    }),
  )
})

const read = fileCapabilities['fs.read']!
const write = fileCapabilities['fs.write']!
const edit = fileCapabilities['fs.edit']!
const find = searchCapabilities['fs.find']!
const grep = searchCapabilities['fs.grep']!

function utf8(value: string): Uint8Array {
  return new Uint8Array(Buffer.from(value, 'utf8'))
}

describe('fs.read 边界', () => {
  it('limit 超过上限先于 I/O 被拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x')
    const result = await callCapability(read, 'fs.read', { path: file, limit: 5000 }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('<= 2000')
  })

  it('column_offset 作用于起始行', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'abc\ndef\n')
    const result = await callCapability(
      read,
      'fs.read',
      { path: file, column_offset: 2 },
      makeContext(dir),
    )
    expect(textOf(result)).toContain('range: 1:2-2:3')
  })

  it('注入的 LSP 钩子在 header 报告支持状态', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.ts')
    await writeFile(file, 'const a = 1\n')
    setFileCapabilityHooks({
      lspSupport: () => ({ language: 'typescript', available: true }),
    })
    try {
      const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
      expect(textOf(result)).toContain('lsp: supported (typescript)')
    } finally {
      setFileCapabilityHooks({})
    }
  })

  it('LSP 钩子不可用或不适用时不输出 header', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.ts')
    await writeFile(file, 'const a = 1\n')
    setFileCapabilityHooks({
      lspSupport: () => ({ language: 'typescript', available: false }),
    })
    try {
      const result = await callCapability(read, 'fs.read', { path: file }, makeContext(dir))
      expect(textOf(result)).not.toContain('lsp:')
    } finally {
      setFileCapabilityHooks({})
    }
  })

  it('write/edit 成功提交后通知 LSP 钩子', async () => {
    const dir = await tempDir()
    const file = join(dir, 'notify.txt')
    const changed: string[] = []
    setFileCapabilityHooks({ fileChanged: (path) => changed.push(path) })
    try {
      await callCapability(
        fileCapabilities['fs.write']!,
        'fs.write',
        { path: file, content: 'a\n' },
        makeContext(dir),
      )
      await callCapability(
        fileCapabilities['fs.edit']!,
        'fs.edit',
        { path: file, old_string: 'a', new_string: 'b' },
        makeContext(dir),
      )
      expect(changed).toEqual([file, file])
    } finally {
      setFileCapabilityHooks({})
    }
  })

  it('目录分页与超出范围提示', async () => {
    const dir = await tempDir()
    const listing = join(dir, 'listing')
    await mkdir(listing)
    for (let index = 1; index <= 5; index++) {
      await writeFile(join(listing, `f${index}.txt`), '')
    }
    const page = await callCapability(
      read,
      'fs.read',
      { path: listing, offset: 2, limit: 2 },
      makeContext(dir),
    )
    expect(textOf(page)).toContain('f2.txt')
    expect(textOf(page)).toContain('[Showing entries 2-3 of 5. Re-run read with offset=4 to continue.]')
    const beyond = await callCapability(
      read,
      'fs.read',
      { path: listing, offset: 10 },
      makeContext(dir),
    )
    expect(textOf(beyond)).toContain('[Showing 0 entries of 5.]')
  })

  it('不存在的路径报告 NOT_FOUND', async () => {
    const dir = await tempDir()
    const result = await callCapability(
      read,
      'fs.read',
      { path: join(dir, 'missing') },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'NOT_FOUND' })
  })

  it.skipIf(process.platform === 'win32')('非普通文件在任何 I/O 之前拒绝', async () => {
    const dir = await tempDir()
    const result = await callCapability(read, 'fs.read', { path: '/dev/null' }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('not a regular file')
  })
})

describe('fs.write / commit 边界', () => {
  it('相对路径被拒绝', async () => {
    const dir = await tempDir()
    const result = await callCapability(
      write,
      'fs.write',
      { path: 'rel.txt', content: 'x' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('absolute path')
  })

  it('覆盖只读文件时保留其精确权限', async () => {
    const dir = await tempDir()
    const file = join(dir, 'ro.txt')
    await writeFile(file, 'old')
    const { markReadOnly, clearReadOnly } = await import('../support/fs-permissions.js')
    await markReadOnly(file)
    try {
      const result = await callCapability(
        write,
        'fs.write',
        { path: file, content: 'new' },
        makeContext(dir),
      )
      if (process.platform === 'win32') {
        // Windows 没有 POSIX mode；只读属性必须挡住覆盖，原内容和属性都保持。
        expect(result.error).toBe(true)
        expect(await readFile(file, 'utf8')).toBe('old')
        const { execFile } = await import('node:child_process')
        const { promisify } = await import('node:util')
        const { stdout } = await promisify(execFile)('attrib', [file], { windowsHide: true })
        expect(String(stdout)).toMatch(/(^|\s)R(\s|$)/)
      } else {
        expect(result.error).toBe(false)
        expect(await readFile(file, 'utf8')).toBe('new')
        expect((await stat(file)).mode & 0o777).toBe(0o444)
      }
    } finally {
      await clearReadOnly(file)
    }
  })

  it('自动创建缺失的父目录', async () => {
    const dir = await tempDir()
    const file = join(dir, 'nested', 'deep', 'f.txt')
    const result = await callCapability(
      write,
      'fs.write',
      { path: file, content: 'x' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    expect(await readFile(file, 'utf8')).toBe('x')
  })

  it('commitTextFile 拒绝符号链接目标并报告 pathExists', async () => {
    const dir = await tempDir()
    const target = join(dir, 'real.txt')
    await writeFile(target, 'x')
    const link = join(dir, 'link.txt')
    await symlink(target, link)
    await expect(commitTextFile(link, utf8('y'))).rejects.toThrowError(/symbolic link/)
    expect(await pathExists(target)).toBe(true)
    expect(await pathExists(join(dir, 'nope'))).toBe(false)
  })
})

describe('fs.edit 边界', () => {
  it('编辑保留 UTF-16LE BOM 与编码', async () => {
    const dir = await tempDir()
    const file = join(dir, 'u16.txt')
    await writeFile(file, encodeText('a\nb\n', 'UTF-16LE', 2))
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'b', new_string: 'c' },
      makeContext(dir),
    )
    expect(result.error).toBe(false)
    const decoded = decodeText(new Uint8Array(await readFile(file)))
    expect(decoded.charset).toBe('UTF-16LE')
    expect(decoded.bomLength).toBe(2)
    expect(decoded.text).toBe('a\nc\n')
  })

  it('行级 diff 输出包含上下文与变更标记', async () => {
    const dir = await tempDir()
    const file = join(dir, 'd.txt')
    await writeFile(file, 'a\nb\nc\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'b', new_string: 'B' },
      makeContext(dir),
    )
    const text = textOf(result)
    expect(text).toContain('diff:')
    expect(text).toContain('- 2|b')
    expect(text).toContain('+ 2|B')
  })

  it('规范化后无变化时拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'n.txt')
    await writeFile(file, 'a\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'a\r\n', new_string: 'a\n' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('must differ after line-ending normalization')
  })

  it('replace_all 遇到重叠匹配时拒绝', async () => {
    const dir = await tempDir()
    const file = join(dir, 'o.txt')
    await writeFile(file, 'aaa\n')
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: file, old_string: 'aa', new_string: 'x', replace_all: true },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('overlapping')
  })

  it('目标是目录时拒绝', async () => {
    const dir = await tempDir()
    const target = join(dir, 'adir')
    await mkdir(target)
    const result = await callCapability(
      edit,
      'fs.edit',
      { path: target, old_string: 'a', new_string: 'b' },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('must be a file')
  })
})

describe('fs.find / fs.grep 边界', () => {
  it('find 达到 limit 时标注结果不完整', async () => {
    const dir = await tempDir()
    for (let index = 0; index < 3; index++) {
      await writeFile(join(dir, `m${index}.txt`), '')
    }
    const result = await callCapability(
      find,
      'fs.find',
      { pattern: '*.txt', path: dir, limit: 1 },
      makeContext(dir),
    )
    expect(textOf(result)).toContain('1 results limit reached')
  })

  it('find 列出不可搜索路径', async () => {
    const dir = await tempDir()
    await writeFile(join(dir, 'a.txt'), '')
    const blocked = join(dir, 'blocked')
    await mkdir(blocked)
    await writeFile(join(blocked, 'b.txt'), '')
    const { denyRead, restoreRead } = await import('../support/fs-permissions.js')
    await denyRead(blocked)
    try {
      const result = await callCapability(
        find,
        'fs.find',
        { pattern: '*.txt', path: dir },
        makeContext(dir),
      )
      expect(textOf(result)).toContain('could not be searched')
    } finally {
      await restoreRead(blocked)
    }
  })

  it('grep 直接指向二进制文件时显式失败', async () => {
    const dir = await tempDir()
    const file = join(dir, 'bin.dat')
    await writeFile(file, Buffer.from([0x00, 0x01]))
    const result = await callCapability(grep, 'fs.grep', { pattern: 'x', path: file }, makeContext(dir))
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('binary')
  })

  it('grep 直接指向 .git 元数据时跳过', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, '.git'))
    const file = join(dir, '.git', 'config')
    await writeFile(file, 'x\n')
    const result = await callCapability(grep, 'fs.grep', { pattern: 'x', path: file }, makeContext(dir))
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('No matches found')
  })

  it('grep include 支持路径模式', async () => {
    const dir = await tempDir()
    await mkdir(join(dir, 'sub'))
    await writeFile(join(dir, 'sub', 'a.ts'), 'x\n')
    await writeFile(join(dir, 'b.ts'), 'x\n')
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x', path: dir, include: 'sub/*.ts' },
      makeContext(dir),
    )
    expect(textOf(result)).toBe(`${toPosix(join(dir, 'sub', 'a.ts'))}:1:x`)
  })

  it('grep 多行模式对超大文件显式失败', async () => {
    const dir = await tempDir()
    const file = join(dir, 'huge.txt')
    const handle = await open(file, 'w')
    try {
      await handle.truncate(64 * 1024 * 1024 + 1)
    } finally {
      await handle.close()
    }
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'x', path: file, multiline: true },
      makeContext(dir),
    )
    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('multiline')
  })

  it('grep 超时映射为 TIMEOUT', async () => {
    const dir = await tempDir()
    for (let index = 0; index < 3000; index++) {
      await writeFile(join(dir, `f${index}.txt`), 'x\n')
    }
    const result = await callCapability(
      grep,
      'fs.grep',
      { pattern: 'zzz', path: dir },
      makeContext(dir),
      1,
    )
    expect(result.error).toBe(true)
    expect(result.details).toMatchObject({ code: 'TIMEOUT' })
  })

  it('matchCenteredExcerpt 对超长行居中截取', () => {
    const line = 'a'.repeat(300) + 'MATCH' + 'b'.repeat(300)
    const excerpt = matchCenteredExcerpt(line, 300, 305)
    expect(excerpt.startsWith('... ')).toBe(true)
    expect(excerpt.endsWith(' ... (line truncated to 500 chars)')).toBe(true)
    expect(excerpt).toContain('MATCH')
  })
})

describe('output 边界', () => {
  it('ByteTailBuffer 只保留最近写入的字节', () => {
    const buffer = new ByteTailBuffer(3)
    buffer.append(Uint8Array.from([1, 2, 3, 4, 5]))
    expect(Array.from(buffer.toBytes())).toEqual([3, 4, 5])
    expect(buffer.size()).toBe(3)
  })

  it('UTF-8 字符边界裁剪', () => {
    const bytes = new Uint8Array(Buffer.from('中'))
    expect(alignToCharacterBoundary(bytes, 1)).toBe(0)
    expect(alignToCharacterBoundary(bytes, 3)).toBe(3)
    expect(leadingCharacterBoundary(new Uint8Array([0xad]))).toBe(1)
    expect(leadingCharacterBoundary(new Uint8Array([0xe4]))).toBe(0)
  })

  it('head/tail 重叠时不重复展示', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c', 5, 2000, store.captureBudgetBytes())
    await spool.write(utf8('abcdefghij'))
    const result = await spool.finish(false)
    expect(textOf(result)).not.toContain('omitted')
    expect(textOf(result)).toContain('abcdefghij')
  })

  it('终结说明追加在文本末尾且错误态保留 error 标记', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c')
    await spool.write(utf8('out\n'))
    const result = await spool.finish(true, 'exit code 1')
    expect(result.error).toBe(true)
    expect(textOf(result)).toBe('out\nexit code 1')
  })

  it('close 清理未发布的中转文件', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const spool = new OutputSpool(store, 'c', 5, 2000, store.captureBudgetBytes())
    await spool.write(utf8('x'.repeat(100)))
    await spool.close()
    expect(await store.partialFiles()).toEqual([])
  })

  it('publish 拒绝非法后缀，acquire 对非受控路径返回 no-op', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    await expect(store.publish(join(dir, 'foo.txt'))).rejects.toThrowError(/\.part/)
    const lease = await store.acquire('/etc/passwd')
    expect(() => lease.close()).not.toThrow()
    await expect(openTestStore(dir, 0)).rejects.toThrowError(/positive/)
  })

  it('私有文件端口失败时创建 workspace 的登记被回滚', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const failing: PrivateFs = {
      ensurePrivateDirectory,
      writePrivateFile: async () => {
        throw new Error('private fs unavailable')
      },
    }
    const storeWithFailure = await TextOutputStore.open(dir, undefined, undefined, failing)
    await expect(storeWithFailure.createStagingFile('call')).rejects.toThrowError(
      /private fs unavailable/,
    )
    // 失败的中转 workspace 不残留：不产生任何未发布文件。
    expect(await store.partialFiles()).toEqual([])
  })

  it('已外化结果不二次 spool', async () => {
    const dir = await tempDir()
    const store = await openTestStore(dir)
    const result = textResult('c', 'z'.repeat(60000))
    result.details = { textOutput: { totalBytes: 1 } }
    const again = await spoolLargeTextResult(store, result)
    expect(again).toBe(result)
  })
})

describe('text 边界', () => {
  it('UTF-16 解码拒绝孤立代理项与奇数尾部字节', () => {
    const decoder = createUtf16Decoder(true)
    decoder.push(Uint8Array.from([0x00, 0xd8]))
    expect(() => decoder.finish()).toThrowError(/invalid text encoding/)
    const odd = createUtf16Decoder(true)
    odd.push(Uint8Array.from([0x00]))
    expect(() => odd.finish()).toThrowError()
  })

  it('decodeText 识别 UTF-16BE BOM', () => {
    const encoded = encodeText('中', 'UTF-16BE', 2)
    expect(decodeText(encoded)).toMatchObject({ text: '中', charset: 'UTF-16BE', bomLength: 2 })
    expect(Array.from(encodeText('a', 'UTF-16LE', 0))).toEqual([0x61, 0x00])
  })

  it('forEachLine 遇到 NUL 按二进制失败', async () => {
    const dir = await tempDir()
    const file = join(dir, 'nul.txt')
    await writeFile(file, Buffer.from([0x61, 0x00]))
    await expect(
      forEachLine(file, { charset: 'UTF-8', bomLength: 0, utf16: false }, () => true),
    ).rejects.toThrowError(/binary/)
  })
})
