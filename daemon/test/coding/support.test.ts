import { afterEach, describe, expect, it } from 'vitest'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { CapabilityResult } from '../../src/contracts.js'
import {
  CancelledError,
  ToolInputRejectedError,
  ToolRunFailureError,
  ToolServiceFailureError,
  classifyFileSystemError,
  displayPath,
  exists,
  guide,
  isAbsolute,
  isSymbolicLink,
  normalize,
  optionalBoolean,
  optionalPositiveInt,
  optionalPositiveIntOrNull,
  requiredString,
  resolveExistingPath,
  resolveWritablePath,
  toErrorResult,
  toPosix,
} from '../../src/coding/support.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-support-'))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

function textOf(result: CapabilityResult): string {
  const first = result.contents[0]
  return first !== undefined && first.type === 'text' ? first.text : ''
}

describe('toErrorResult', () => {
  it('按错误类型给出三段式文案与稳定错误码', () => {
    const rejected = toErrorResult('c', new ToolInputRejectedError('bad input', 'INVALID_ARGUMENT'))
    expect(textOf(rejected)).toContain('The tool was not executed.')
    expect(rejected.details).toEqual({ code: 'INVALID_ARGUMENT' })

    expect(textOf(toErrorResult('c', new ToolRunFailureError('oops', 'CODEC')))).toContain(
      'cannot be confirmed',
    )
    expect(textOf(toErrorResult('c', new ToolServiceFailureError('svc')))).toContain(
      'cannot be confirmed',
    )
    expect(textOf(toErrorResult('c', new CancelledError()))).toContain('Operation cancelled')
    expect(toErrorResult('c', new CancelledError()).details).toEqual({ code: 'CANCELLED' })
  })

  it('普通异常不回显原始 message', () => {
    const result = toErrorResult('c', new Error('secret-path'))
    expect(textOf(result)).not.toContain('secret-path')
    expect(textOf(result)).toContain('not repeated')
    expect(result.details).toEqual({ code: 'INTERNAL' })
  })
})

describe('guide', () => {
  it('补齐句末标点', () => {
    expect(guide('boom', 'FAILED', 'retry')).toBe(
      'boom. The tool ran but failed; side effects may have occurred. retry.',
    )
    expect(guide('boom!', 'NOT_EXECUTED', 'retry?')).toBe(
      'boom! The tool was not executed. retry?',
    )
  })
})

describe('classifyFileSystemError', () => {
  it('映射 errno 到稳定错误码', () => {
    expect(classifyFileSystemError({ code: 'EACCES' })).toMatchObject({ code: 'PERMISSION_DENIED' })
    expect(classifyFileSystemError({ code: 'EPERM' })).toMatchObject({ code: 'PERMISSION_DENIED' })
    expect(classifyFileSystemError({ code: 'ENOENT' })).toMatchObject({ code: 'NOT_FOUND' })
    expect(classifyFileSystemError({ code: 'EISDIR' })).toMatchObject({ code: 'INVALID_ARGUMENT' })
    expect(classifyFileSystemError({ code: 'ENOTDIR' })).toMatchObject({ code: 'INVALID_ARGUMENT' })
    expect(classifyFileSystemError({ code: 'EEXIST' })).toMatchObject({ code: 'ALREADY_EXISTS' })
    expect(classifyFileSystemError({ code: 'ENOSPC' })).toMatchObject({ code: 'NO_SPACE' })
    expect(classifyFileSystemError({ code: 'EUNKNOWN' })).toMatchObject({ code: 'IO' })
  })
})

describe('参数校验', () => {
  it('requiredString 与 optionalPositiveInt 边界', () => {
    expect(() => requiredString({}, 'path')).toThrowError(/path is required/)
    expect(optionalPositiveInt({}, 'limit', 10, 20)).toBe(10)
    expect(optionalPositiveInt({ limit: 20 }, 'limit', 10, 20)).toBe(20)
    expect(() => optionalPositiveInt({ limit: 21 }, 'limit', 10, 20)).toThrowError(/<= 20/)
    expect(() => optionalPositiveInt({ limit: 0 }, 'limit', 10, 20)).toThrowError()
    expect(() => optionalPositiveInt({ limit: 1.5 }, 'limit', 10, 20)).toThrowError()
    expect(optionalPositiveIntOrNull({}, 'column_offset')).toBeNull()
    expect(optionalPositiveIntOrNull({ column_offset: 3 }, 'column_offset')).toBe(3)
    expect(() => optionalPositiveIntOrNull({ column_offset: 0 }, 'column_offset')).toThrowError()
    expect(optionalBoolean({ replace_all: true }, 'replace_all')).toBe(true)
    expect(optionalBoolean({}, 'replace_all')).toBe(false)
  })
})

describe('路径工具', () => {
  it('isAbsolute / normalize / toPosix / displayPath', () => {
    expect(isAbsolute('/a')).toBe(true)
    expect(isAbsolute('C:/a')).toBe(true)
    expect(isAbsolute('\\\\srv\\share')).toBe(true)
    expect(isAbsolute('rel')).toBe(false)
    expect(normalize('a/./b/../c')).toBe('a/c')
    expect(normalize('/a/b/../c')).toBe('/a/c')
    expect(toPosix('a\\b')).toBe('a/b')
    expect(displayPath('/real', '/raw/./x')).toBe('/raw/x')
    expect(displayPath('/real', 'rel')).toBe('/real')
    expect(displayPath(null, null)).toBe('<missing>')
  })

  it('resolveExistingPath 校验绝对性与存在性', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x')
    expect(await resolveExistingPath(file)).toContain('a.txt')
    await expect(resolveExistingPath('rel')).rejects.toThrowError(/absolute path/)
    await expect(resolveExistingPath(join(dir, 'missing'))).rejects.toThrowError(/does not exist/)
  })

  it('resolveWritablePath 允许尚不存在的末段', async () => {
    const dir = await tempDir()
    const target = join(dir, 'new', 'deep', 'f.txt')
    expect(await resolveWritablePath(target)).toContain('f.txt')
  })

  it('exists 与 isSymbolicLink', async () => {
    const dir = await tempDir()
    const file = join(dir, 'a.txt')
    await writeFile(file, 'x')
    const link = join(dir, 'link')
    await symlink(file, link)
    expect(await exists(file)).toBe(true)
    expect(await exists(join(dir, 'nope'))).toBe(false)
    expect(await isSymbolicLink(link)).toBe(true)
    expect(await isSymbolicLink(file)).toBe(false)
    expect(await isSymbolicLink(join(dir, 'nope'))).toBe(false)
  })
})
