import { describe, expect, it } from 'vitest'
import {
  areHeadersEqual,
  DEFAULT_TIMEOUT_MILLIS,
  hasUnsavedChanges,
  parseAndValidateHeaders,
  validateMcpName,
  validateMcpUrl,
  validateTimeoutMillis,
} from './mcp-utils'

describe('mcp-utils', () => {
  describe('validateMcpName', () => {
    it('accepts valid lowercase names with numbers and underscores', () => {
      expect(validateMcpName('filesystem').valid).toBe(true)
      expect(validateMcpName('fs_v2').valid).toBe(true)
      expect(validateMcpName('a_1_b').valid).toBe(true)
    })

    it('rejects blank names', () => {
      expect(validateMcpName('').valid).toBe(false)
      expect(validateMcpName('   ').valid).toBe(false)
    })

    it('rejects names starting with numbers or uppercase letters', () => {
      expect(validateMcpName('1fs').valid).toBe(false)
      expect(validateMcpName('FileSystem').valid).toBe(false)
      expect(validateMcpName('_private').valid).toBe(false)
      expect(validateMcpName('my-server').valid).toBe(false)
    })

    it('rejects names exceeding 32 characters', () => {
      const longName = 'a'.repeat(33)
      const res = validateMcpName(longName)
      expect(res.valid).toBe(false)
      expect(res.error).toContain('32')
    })
  })

  describe('validateMcpUrl', () => {
    it('accepts valid http and https URLs', () => {
      expect(validateMcpUrl('http://localhost:8080/mcp').valid).toBe(true)
      expect(validateMcpUrl('https://api.example.com/v1/mcp').valid).toBe(true)
    })

    it('rejects blank URLs', () => {
      expect(validateMcpUrl('').valid).toBe(false)
      expect(validateMcpUrl('   ').valid).toBe(false)
    })

    it('rejects URLs with surrounding whitespace', () => {
      const res = validateMcpUrl(' https://example.com/mcp ')
      expect(res.valid).toBe(false)
      expect(res.error).toContain('whitespace')
    })

    it('rejects non-http/https schemes', () => {
      expect(validateMcpUrl('ftp://example.com/mcp').valid).toBe(false)
      expect(validateMcpUrl('javascript:alert(1)').valid).toBe(false)
      expect(validateMcpUrl('file:///etc/passwd').valid).toBe(false)
    })

    it('rejects URLs without host', () => {
      expect(validateMcpUrl('http://').valid).toBe(false)
    })

    it('rejects user-info credentials in URL', () => {
      const res = validateMcpUrl('https://user:password@example.com/mcp')
      expect(res.valid).toBe(false)
      expect(res.error).toContain('user-info')
    })

    it('rejects URLs exceeding 2048 characters', () => {
      const longUrl = `https://example.com/${'a'.repeat(2040)}`
      const res = validateMcpUrl(longUrl)
      expect(res.valid).toBe(false)
      expect(res.error).toContain('2048')
    })
  })

  describe('parseAndValidateHeaders', () => {
    it('returns empty headers object for empty or whitespace input', () => {
      expect(parseAndValidateHeaders('')).toEqual({ valid: true, headers: {} })
      expect(parseAndValidateHeaders('   \n  ')).toEqual({ valid: true, headers: {} })
    })

    it('parses valid headers without altering or trimming values', () => {
      const json = JSON.stringify({
        Authorization: ' Bearer token_123 ',
        'X-Custom-Header': 'preserve   spaces',
        'X-Placeholder': '${MY_ENV_VAR}',
      })
      const result = parseAndValidateHeaders(json)
      expect(result.valid).toBe(true)
      expect(result.headers).toEqual({
        Authorization: ' Bearer token_123 ',
        'X-Custom-Header': 'preserve   spaces',
        'X-Placeholder': '${MY_ENV_VAR}',
      })
      // 验证未被 trim
      expect(result.headers['Authorization']).toBe(' Bearer token_123 ')
    })

    it('rejects invalid JSON syntax', () => {
      const result = parseAndValidateHeaders('{ invalid json }')
      expect(result.valid).toBe(false)
      expect(result.error).toContain('valid JSON')
    })

    it('rejects non-object JSON values like arrays, primitives, and null', () => {
      expect(parseAndValidateHeaders('["a", "b"]').valid).toBe(false)
      expect(parseAndValidateHeaders('"a string"').valid).toBe(false)
      expect(parseAndValidateHeaders('123').valid).toBe(false)
      expect(parseAndValidateHeaders('true').valid).toBe(false)
      expect(parseAndValidateHeaders('null').valid).toBe(false)
    })

    it('rejects non-string values inside headers object (unknown/invalid values)', () => {
      expect(parseAndValidateHeaders('{"timeout": 123}').valid).toBe(false)
      expect(parseAndValidateHeaders('{"enabled": true}').valid).toBe(false)
      expect(parseAndValidateHeaders('{"nested": {"foo": "bar"}}').valid).toBe(false)
      expect(parseAndValidateHeaders('{"list": ["a"]}').valid).toBe(false)
      expect(parseAndValidateHeaders('{"nullVal": null}').valid).toBe(false)
    })

    it('rejects empty or blank header names', () => {
      expect(parseAndValidateHeaders('{"": "val"}').valid).toBe(false)
      expect(parseAndValidateHeaders('{"   ": "val"}').valid).toBe(false)
    })

    it('rejects header names containing control characters', () => {
      expect(parseAndValidateHeaders('{"X-\\u0000-Header": "val"}').valid).toBe(false)
    })

    it('rejects header values containing control characters', () => {
      expect(parseAndValidateHeaders('{"Header": "line\\u0000break"}').valid).toBe(false)
    })

    it('rejects header names exceeding 256 characters', () => {
      const longName = 'H'.repeat(257)
      const res = parseAndValidateHeaders(JSON.stringify({ [longName]: 'value' }))
      expect(res.valid).toBe(false)
      expect(res.error).toContain('256')
    })

    it('rejects header values exceeding 8192 characters', () => {
      const longVal = 'v'.repeat(8193)
      const res = parseAndValidateHeaders(JSON.stringify({ Authorization: longVal }))
      expect(res.valid).toBe(false)
      expect(res.error).toContain('8192')
    })
  })

  describe('validateTimeoutMillis', () => {
    it('returns default 60000 when empty, undefined or null', () => {
      expect(validateTimeoutMillis(undefined)).toEqual({
        valid: true,
        timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
      })
      expect(validateTimeoutMillis(null)).toEqual({
        valid: true,
        timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
      })
      expect(validateTimeoutMillis('')).toEqual({
        valid: true,
        timeoutMillis: DEFAULT_TIMEOUT_MILLIS,
      })
    })

    it('accepts positive integer numbers and numeric strings', () => {
      expect(validateTimeoutMillis(30000)).toEqual({ valid: true, timeoutMillis: 30000 })
      expect(validateTimeoutMillis('45000')).toEqual({ valid: true, timeoutMillis: 45000 })
    })

    it('rejects zero, negative numbers, floats and invalid strings', () => {
      expect(validateTimeoutMillis(0).valid).toBe(false)
      expect(validateTimeoutMillis(-100).valid).toBe(false)
      expect(validateTimeoutMillis(12.34).valid).toBe(false)
      expect(validateTimeoutMillis('abc').valid).toBe(false)
      expect(validateTimeoutMillis(NaN).valid).toBe(false)
    })
  })

  describe('areHeadersEqual and hasUnsavedChanges', () => {
    it('correctly checks header equality with exact whitespace matching', () => {
      expect(areHeadersEqual({}, {})).toBe(true)
      expect(areHeadersEqual({ a: '1' }, { a: '1' })).toBe(true)
      expect(areHeadersEqual({ a: ' 1 ' }, { a: '1' })).toBe(false)
      expect(areHeadersEqual({ a: '1' }, { a: '2' })).toBe(false)
      expect(areHeadersEqual({ a: '1' }, { b: '1' })).toBe(false)
    })

    it('detects unsaved changes in URL, headers, enabled, or timeout', () => {
      const base = {
        url: 'https://example.com/mcp',
        headers: { Authorization: 'Bearer 123' },
        enabled: true,
        timeoutMillis: 60000,
      }

      expect(hasUnsavedChanges(base, { ...base })).toBe(false)

      expect(hasUnsavedChanges({ ...base, url: 'https://example.com/other' }, base)).toBe(true)
      expect(
        hasUnsavedChanges(
          { ...base, headers: { Authorization: 'Bearer 123', 'X-New': '1' } },
          base,
        ),
      ).toBe(true)
      expect(hasUnsavedChanges({ ...base, enabled: false }, base)).toBe(true)
      expect(hasUnsavedChanges({ ...base, timeoutMillis: 30000 }, base)).toBe(true)
    })
  })
})
