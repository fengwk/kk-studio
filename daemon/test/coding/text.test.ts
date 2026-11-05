import { describe, expect, it } from 'vitest'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  decodeText,
  detectImageMediaType,
  encodeText,
  isBinaryBytes,
  looksBinary,
  PROBE_BYTES,
  readTextWindow,
} from '../../src/coding/text.js'

async function readWindow(content: string | Uint8Array, columnOffset: number | null = null): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'kk-text-window-'))
  try {
    const file = join(dir, 'text')
    await writeFile(file, content)
    return await readTextWindow(file, { charset: 'UTF-8', bomLength: 0, utf16: false }, 1, 2000, columnOffset, '/p', null, () => {})
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

describe('readTextWindow', () => {
  it('投影有界窗口与行号宽度', async () => {
    const text = await readWindow('a\nb\nc\n')
    expect(text).toBe(
      ['path: /p', 'ends_with_newline: yes', 'range: 1:1-3:1', '', '1|a', '2|b', '3|c'].join('\n'),
    )
  })

  it('column_offset 只作用于起始行', async () => {
    const text = await readWindow('abc\ndef\n', 2)
    expect(text).toContain('range: 1:2-2:3')
    expect(text).toContain('1|bc')
    expect(text).toContain('2|def')
  })

  it('column_offset 越界报错', async () => {
    await expect(readWindow('a\n', 5)).rejects.toThrowError(
      /column_offset 5 is out of range/,
    )
  })

  it('空文件输出 range: empty', async () => {
    expect(await readWindow('')).toContain('range: empty')
  })

  it('CRLF 折叠为一次换行', async () => {
    const text = await readWindow('a\r\nb')
    expect(text).toContain('ends_with_newline: no')
    expect(text).toContain('2|b')
  })

  it('正文码点预算耗尽时报告 character_limit', async () => {
    const text = await readWindow('a'.repeat(60005))
    expect(text).toContain('truncation_reason: character_limit')
    expect(text).toContain('next: 1:60001')
  })

  it('代理项编码按 malformed 拒绝', async () => {
    await expect(readWindow(Uint8Array.from([0xed, 0xa0, 0x80]))).rejects.toThrowError(/malformed text encoding/)
  })

  it('跨块拒绝非最短编码、代理项和越界码点', async () => {
    // 每个字节边界都可能是文件读取边界，不完整前缀不能绕过校验。
    for (const sequence of [
      [0xe0, 0x81, 0x81],
      [0xf0, 0x8f, 0xbf, 0xbf],
      [0xed, 0xa0, 0x80],
      [0xf4, 0x90, 0x80, 0x80],
    ]) {
      for (let split = 1; split < sequence.length; split++) {
        const bytes = Buffer.concat([Buffer.alloc(8192 - split, 0x61), Buffer.from(sequence)])
        await expect(readWindow(bytes)).rejects.toThrowError(/malformed text encoding/)
      }
    }
  })

  it('NUL 字符按二进制拒绝', async () => {
    await expect(readWindow('a\u0000b')).rejects.toThrowError(/contains NUL character/)
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

  it('严格拒绝非最短 UTF-8、代理项和越界码点', () => {
    // 与 fatal TextDecoder 对相同字节的合法性保持一致。
    for (const sequence of [
      [0xc0, 0x81],
      [0xc1, 0xbf],
      [0xe0, 0x81, 0x81],
      [0xe0, 0x9f, 0xbf],
      [0xf0, 0x80, 0x81, 0x81],
      [0xf0, 0x8f, 0xbf, 0xbf],
      [0xed, 0xa0, 0x80],
      [0xed, 0xbf, 0xbf],
      [0xf4, 0x90, 0x80, 0x80],
    ]) {
      const bytes = Uint8Array.from(sequence)
      expect(() => new TextDecoder('utf-8', { fatal: true }).decode(bytes)).toThrow()
      expect(() => decodeText(bytes)).toThrowError(/binary: (invalid|malformed) text encoding/)
    }
    expect(() => decodeText(Uint8Array.from([0xe0, 0x81, 0x81]))).toThrowError(/malformed text encoding/)
    expect(() => decodeText(Uint8Array.from([0xf0, 0x8f, 0xbf, 0xbf]))).toThrowError(/malformed text encoding/)
  })

  it('接受各 UTF-8 长度和代理区两侧的合法边界', () => {
    const text = '\u007f\u0080\u07ff\u0800\ud7ff\ue000\uffff\u{10000}\u{10ffff}'
    expect(decodeText(Buffer.from(text, 'utf8')).text).toBe(text)
  })

  it('孤立代理项编码拒绝', () => {
    expect(() => encodeText('a\ud800', 'UTF-8', 0)).toThrowError(/losslessly encoded/)
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

describe('isBinaryBytes / looksBinary', () => {
  it('纯文本与允许的控制字符不判定为二进制', () => {
    // 包含普通 ASCII、TAB(0x09)、LF(0x0a)、CR(0x0d)、ESC(0x1b)
    const textBytes = Buffer.from('hello\tworld\r\n\x1b[31mred\x1b[0m')
    expect(isBinaryBytes(textBytes)).toBe(false)
    expect(looksBinary(textBytes, { charset: 'UTF-8', bomLength: 0, utf16: false })).toBe(false)
  })

  it('非法控制字符与 NUL 判定为二进制', () => {
    expect(isBinaryBytes(Buffer.from([0x00]))).toBe(true)
    expect(isBinaryBytes(Buffer.from([0x01]))).toBe(true)
    expect(isBinaryBytes(Buffer.from([0x08]))).toBe(true)
    expect(isBinaryBytes(Buffer.from([0x0e]))).toBe(true)
    expect(isBinaryBytes(Buffer.from([0x1f]))).toBe(true)
  })

  it('PROBE_BYTES 探测窗口边界判定', () => {
    expect(PROBE_BYTES).toBe(8192)
    // 恰好在 PROBE_BYTES 窗口内的控制字符能被探测
    const withinWindow = Buffer.alloc(PROBE_BYTES, 0x61)
    withinWindow[PROBE_BYTES - 1] = 0x00
    expect(isBinaryBytes(withinWindow)).toBe(true)

    // 超出 PROBE_BYTES 窗口的控制字符不会被探测
    const outsideWindow = Buffer.alloc(PROBE_BYTES + 10, 0x61)
    outsideWindow[PROBE_BYTES] = 0x00
    expect(isBinaryBytes(outsideWindow)).toBe(false)
  })

  it('UTF-16 编码下 looksBinary 一律返回 false', () => {
    // UTF-16 编码自然包含 0x00 字节，looksBinary 跳过字节扫描
    const utf16Bytes = Buffer.from([0x00, 0x61, 0x00, 0x62])
    expect(looksBinary(utf16Bytes, { charset: 'UTF-16BE', bomLength: 2, utf16: true })).toBe(false)
    expect(looksBinary(utf16Bytes, { charset: 'UTF-16LE', bomLength: 2, utf16: true })).toBe(false)
  })
})
