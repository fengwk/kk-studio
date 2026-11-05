import { describe, expect, it } from 'vitest'
import {
  parseStrictJson,
  ProtocolError,
  utf8ByteLength,
} from '../src/protocol.js'

describe('protocol.ts pure unit tests', () => {
  describe('utf8ByteLength', () => {
    it('calculates bytes for ascii, multibyte and surrogate pairs', () => {
      expect(utf8ByteLength('abc', 'test')).toBe(3)
      expect(utf8ByteLength('é', 'test')).toBe(2)
      expect(utf8ByteLength('中文', 'test')).toBe(6)
      expect(utf8ByteLength('🚀', 'test')).toBe(4) // surrogate pair
    })

    it('rejects lone surrogates', () => {
      expect(() => utf8ByteLength('\uD800abc', 'test')).toThrow(/must be valid Unicode/)
      expect(() => utf8ByteLength('abc\uDC00', 'test')).toThrow(/must be valid Unicode/)
    })
  })

  describe('parseStrictJson', () => {
    const limits = {
      maxBytes: 100,
      maxNestingDepth: 3,
      maxNumberLength: 5,
    }

    it('rejects non-string input', () => {
      expect(() => parseStrictJson(123, 'test', limits)).toThrow(/must be a string/)
    })

    it('rejects input exceeding maxBytes', () => {
      expect(() => parseStrictJson('a'.repeat(101), 'test', limits)).toThrow(/exceeds 100 UTF-8 bytes/)
    })

    it('rejects duplicate object keys', () => {
      expect(() => parseStrictJson('{"a":1,"a":2}', 'test', limits)).toThrow(/duplicate key "a"/)
    })

    it('rejects nesting depth exceeding limit in objects and arrays', () => {
      // depth: {"a":{"b":{"c":1}}} -> 3 deep
      expect(() => parseStrictJson('{"a":{"b":{"c":{"d":1}}}}', 'test', limits)).toThrow(/maximum nesting depth/)
      expect(() => parseStrictJson('[[[[1]]]]', 'test', limits)).toThrow(/maximum nesting depth/)
    })

    it('rejects numbers exceeding maxNumberLength', () => {
      expect(() => parseStrictJson('{"n":123456}', 'test', limits)).toThrow(/number exceeds 5 characters/)
    })

    it('rejects invalid JSON syntax', () => {
      expect(() => parseStrictJson('{"invalid":}', 'test', limits)).toThrow(/must be valid JSON/)
    })

    it('successfully parses valid JSON within limits', () => {
      expect(parseStrictJson('{"ok":true,"num":123}', 'test', limits)).toEqual({
        ok: true,
        num: 123,
      })
    })
  })
})
