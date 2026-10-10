import { describe, expect, it } from 'vitest'
import { TextFileCodec } from '../../src/coding/lsp-codec.js'
import { LspError } from '../../src/coding/lsp-error.js'

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
    expect(TextFileCodec.isBinary(binaryBytes)).toBe(true)
    expect(() => TextFileCodec.decode(binaryBytes)).toThrow(/appears to be binary/)
  })

  it('detects binary files containing NUL characters', () => {
    const nulBytes = Buffer.from('hello\0world', 'utf8')
    expect(TextFileCodec.isBinary(nulBytes)).toBe(true)
    expect(() => TextFileCodec.decode(nulBytes)).toThrow(/appears to be binary/)
  })

  it('detects invalid text encoding', () => {
    // 0xff 0xff is not a valid BOM and 0xff is invalid UTF-8 byte
    const invalidUtf8 = Buffer.from([0x30, 0x80, 0xbf])
    expect(() => TextFileCodec.decode(invalidUtf8)).toThrow(/invalid text encoding/)
  })
})
