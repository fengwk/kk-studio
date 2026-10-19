import { describe, expect, it } from 'vitest'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  decodeText,
  detectImageMediaType,
  encodeText,
  forEachLine,
  readWindowFromChunks,
  TextWindowScanner,
} from '../../src/coding/text.js'

describe('TextWindowScanner', () => {
  it('投影有界窗口与行号宽度', () => {
    const text = readWindowFromChunks(['a\nb\nc\n'], 1, 2000, null, '/p', null)
    expect(text).toBe(
      ['path: /p', 'ends_with_newline: yes', 'range: 1:1-3:1', '', '1|a', '2|b', '3|c'].join('\n'),
    )
  })

  it('column_offset 只作用于起始行', () => {
    const text = readWindowFromChunks(['abc\ndef\n'], 1, 2000, 2, '/p', null)
    expect(text).toContain('range: 1:2-2:3')
    expect(text).toContain('1|bc')
    expect(text).toContain('2|def')
  })

  it('column_offset 越界报错', () => {
    expect(() => readWindowFromChunks(['a\n'], 1, 2000, 5, '/p', null)).toThrowError(
      /column_offset 5 is out of range/,
    )
  })

  it('空文件输出 range: empty', () => {
    expect(readWindowFromChunks([], 1, 2000, null, '/p', null)).toContain('range: empty')
  })

  it('CRLF 折叠为一次换行', () => {
    const text = readWindowFromChunks(['a\r\nb'], 1, 2000, null, '/p', null)
    expect(text).toContain('ends_with_newline: no')
    expect(text).toContain('2|b')
  })

  it('正文码点预算耗尽时报告 character_limit', () => {
    const scanner = new TextWindowScanner(1, 2000, null, '/p', null)
    scanner.push('a'.repeat(60005))
    const text = scanner.finish()
    expect(text).toContain('truncation_reason: character_limit')
    expect(text).toContain('next: 1:60001')
  })

  it('孤立代理项按 malformed 拒绝', () => {
    const scanner = new TextWindowScanner(1, 2000, null, '/p', null)
    scanner.push('\ud800')
    expect(() => scanner.finish()).toThrowError(/malformed text encoding/)
  })

  it('NUL 字符按二进制拒绝', () => {
    const scanner = new TextWindowScanner(1, 2000, null, '/p', null)
    expect(() => scanner.push('a\u0000b')).toThrowError(/contains NUL character/)
  })
})

describe('decodeText / encodeText', () => {
  it('UTF-8 BOM 保留', () => {
    const decoded = decodeText(Buffer.from([0xef, 0xbb, 0xbf, 0x61]))
    expect(decoded).toMatchObject({ text: 'a', charset: 'UTF-8', bomLength: 3 })
  })

  it('UTF-16LE/BE 往返', () => {
    for (const [charset, bom] of [
      ['UTF-16LE', [0xff, 0xfe]],
      ['UTF-16BE', [0xfe, 0xff]],
    ] as const) {
      const encoded = encodeText('中', charset, 2)
      expect(Array.from(encoded.subarray(0, 2))).toEqual(bom)
      expect(decodeText(encoded)).toMatchObject({ text: '中', charset })
    }
  })

  it('非法 UTF-8 与 NUL 按二进制拒绝', () => {
    expect(() => decodeText(Buffer.from([0x61, 0x00, 0x62]))).toThrowError(/binary/)
    expect(() => decodeText(Buffer.from([0x61, 0xc3, 0x28]))).toThrowError(/binary/)
  })

  it('孤立代理项编码拒绝', () => {
    expect(() => encodeText('a\ud800', 'UTF-8', 0)).toThrowError(/losslessly encoded/)
  })
})

describe('forEachLine', () => {
  it('LF/CRLF/孤立 CR 都是行边界', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'kk-text-'))
    try {
      const file = join(dir, 'lines.txt')
      await writeFile(file, 'a\r\nb\nc\rd')
      const seen: Array<[number, string]> = []
      const outcome = await forEachLine(
        file,
        { charset: 'UTF-8', bomLength: 0, utf16: false },
        (number, line) => {
          seen.push([number, line])
          return true
        },
      )
      expect(seen).toEqual([
        [1, 'a'],
        [2, 'b'],
        [3, 'c'],
        [4, 'd'],
      ])
      expect(outcome).toEqual({ totalLines: 4, endsWithNewline: false })
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })

  it('提前终止仍报告已扫描行数', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'kk-text-'))
    try {
      const file = join(dir, 'stop.txt')
      await writeFile(file, 'a\nb\nc\n')
      const outcome = await forEachLine(
        file,
        { charset: 'UTF-8', bomLength: 0, utf16: false },
        (number) => number < 2,
      )
      expect(outcome.totalLines).toBe(2)
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })

  it('forEachLine 在读取 8KiB 块前后调用 checkpoint 且异常时正确关闭 handle', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'kk-text-chk-'))
    try {
      const file = join(dir, 'longline.txt')
      // 写入 24KiB 的无换行长数据
      await writeFile(file, 'a'.repeat(24 * 1024))
      let checkpoints = 0
      let consumerCalled = 0
      const outcome = await forEachLine(
        file,
        { charset: 'UTF-8', bomLength: 0, utf16: false },
        () => {
          consumerCalled++
          return true
        },
        () => {
          checkpoints++
        },
      )
      // 24KiB 分 3 个 8KiB 块，每块读前后各调用 1 次，共至少 6 次 checkpoint
      expect(checkpoints).toBeGreaterThanOrEqual(6)
      expect(consumerCalled).toBe(1)
      expect(outcome.totalLines).toBe(1)

      // 验证 checkpoint 中途抛错时 handle 能够关闭并冒泡异常
      let thrownCheckpoints = 0
      await expect(
        forEachLine(
          file,
          { charset: 'UTF-8', bomLength: 0, utf16: false },
          () => true,
          () => {
            thrownCheckpoints++
            if (thrownCheckpoints === 2) {
              throw new Error('checkpoint abort')
            }
          },
        ),
      ).rejects.toThrow('checkpoint abort')
    } finally {
      await rm(dir, { recursive: true, force: true })
    }
  })
})

describe('detectImageMediaType', () => {
  it('识别 PNG/JPEG/GIF/WEBP 并忽略非图片', () => {
    expect(detectImageMediaType(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))).toBe('image/png')
    expect(detectImageMediaType(Buffer.from([0xff, 0xd8, 0xff]))).toBe('image/jpeg')
    expect(detectImageMediaType(Buffer.from('GIF89a'))).toBe('image/gif')
    expect(
      detectImageMediaType(Buffer.from('RIFF0000WEBP')),
    ).toBe('image/webp')
    expect(detectImageMediaType(Buffer.from('hello'))).toBeNull()
  })
})
