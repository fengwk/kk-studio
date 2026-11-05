import { describe, expect, it } from 'vitest'
import { TextFileCodec } from '../../src/coding/lsp-codec.js'
import { LspError } from '../../src/coding/lsp-error.js'
import { decodeText } from '../../src/coding/text.js'

describe('TextFileCodec', () => {
  it('decodes plain UTF-8 text', () => {
    const bytes = Buffer.from('hello world\n', 'utf8')
    const decoded = TextFileCodec.decode(bytes)
    expect(decoded.text).toBe('hello world\n')
    expect(decoded.charset).toBe('utf-8')
    expect(decoded.bomLength).toBe(0)
  })

  it('decodes UTF-8 with BOM', () => {
    const bytes = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('with bom', 'utf8')])
    const decoded = TextFileCodec.decode(bytes)
    expect(decoded.text).toBe('with bom')
    expect(decoded.charset).toBe('utf-8')
    expect(decoded.bomLength).toBe(3)
  })

  it('retains the distinct TextDecoder behavior for a second leading BOM', () => {
    // LSP follows TextDecoder; file editing retains the second BOM as content.
    const bytes = Buffer.from([0xef, 0xbb, 0xbf, 0xef, 0xbb, 0xbf, 0x61])
    expect(TextFileCodec.decode(bytes).text).toBe('a')
    expect(decodeText(bytes).text).toBe('\ufeffa')
  })

  it('decodes UTF-16LE with BOM', () => {
    const content = Buffer.from('hello', 'utf16le')
    const bytes = Buffer.concat([Buffer.from([0xff, 0xfe]), content])
    const decoded = TextFileCodec.decode(bytes)
    expect(decoded.text).toBe('hello')
    expect(decoded.charset).toBe('utf-16le')
    expect(decoded.bomLength).toBe(2)
  })

  it('decodes UTF-16BE with BOM', () => {
    // hello in UTF-16BE
    const beBytes = Buffer.from([0xfe, 0xff, 0x00, 0x68, 0x00, 0x65, 0x00, 0x6c, 0x00, 0x6c, 0x00, 0x6f])
    const decoded = TextFileCodec.decode(beBytes)
    expect(decoded.text).toBe('hello')
    expect(decoded.charset).toBe('utf-16be')
    expect(decoded.bomLength).toBe(2)
  })

  it('detects binary files via control bytes', () => {
    const binaryBytes = Buffer.from([0x01, 0x02, 0x03, 0x04])
    expect(() => TextFileCodec.decode(binaryBytes)).toThrow(/appears to be binary/)
  })

  it('detects binary files containing NUL characters', () => {
    const nulBytes = Buffer.from('hello\0world', 'utf8')
    expect(() => TextFileCodec.decode(nulBytes)).toThrow(/appears to be binary/)
  })

  it('detects invalid text encoding', () => {
    // Continuation bytes cannot appear without a leading byte.
    const invalidUtf8 = Buffer.from([0x30, 0x80, 0xbf])
    expect(() => TextFileCodec.decode(invalidUtf8)).toThrow(/invalid text encoding/)
    // LSP collapses malformed sequences to its own invalid-encoding error.
    expect(() => TextFileCodec.decode(Buffer.from([0xe0, 0x81, 0x81]))).toThrowError(
      new LspError('file appears to be binary: invalid text encoding'),
    )
  })

  it('detects NUL characters inside BOM-decoded text', () => {
    // UTF-16LE BOM followed by a NUL character (0x00 0x00)
    const nulUtf16 = Buffer.from([0xff, 0xfe, 0x00, 0x00])
    expect(() => TextFileCodec.decode(nulUtf16)).toThrow(/contains NUL character/)
  })

  it('handles empty or short byte arrays without throwing in starts check', () => {
    const emptyDecoded = TextFileCodec.decode(Buffer.alloc(0))
    expect(emptyDecoded.text).toBe('')
    expect(emptyDecoded.bomLength).toBe(0)

    const oneByte = TextFileCodec.decode(Buffer.from('a', 'utf8'))
    expect(oneByte.text).toBe('a')
  })

  it('respects PROBE_BYTES scanning boundary', () => {
    // 恰好在 8192 字节内的控制字符被检出
    const within = Buffer.alloc(8192, 0x61)
    within[8191] = 0x01
    expect(() => TextFileCodec.decode(within)).toThrow(/appears to be binary/)

    // 超出 8192 字节的控制字符不会触发二进制判定
    const beyond = Buffer.alloc(8200, 0x61)
    beyond[8192] = 0x01
    expect(TextFileCodec.decode(beyond).text).toBe(beyond.toString('utf8'))
  })
})
