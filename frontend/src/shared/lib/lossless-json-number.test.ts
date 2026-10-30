import { describe, expect, it, vi } from 'vitest'
import {
  canonicalJsonNumber,
  parseJsonPreservingPathEqualsNumbers,
  stringifyJsonPreservingNumbers,
  supportsLosslessJsonNumbers,
  wireJsonNumber,
} from '@/shared/lib/lossless-json-number'

describe('lossless-json-number', () => {
  it('normalizes exact decimal literals without JS Number rounding', () => {
    expect(canonicalJsonNumber('2.5')).toBe('2.5')
    expect(canonicalJsonNumber('0.01')).toBe('0.01')
    expect(canonicalJsonNumber('200')).toBe('200')
    expect(canonicalJsonNumber('1e3')).toBe('1000')
    expect(canonicalJsonNumber('1e-3')).toBe('0.001')
    expect(canonicalJsonNumber('2')).toBe('2')
    expect(canonicalJsonNumber('2.000')).toBe('2')
    expect(canonicalJsonNumber('9007199254740993')).toBe('9007199254740993')
    expect(canonicalJsonNumber('-0.000')).toBe('0')
  })

  it('enforces JSON number grammar, precision and scale', () => {
    expect(() => canonicalJsonNumber('01')).toThrow(/JSON number/)
    expect(() => canonicalJsonNumber('+1')).toThrow(/JSON number/)
    expect(() => canonicalJsonNumber('1e19')).toThrow(/scale/)
    expect(() => canonicalJsonNumber('1e-19')).toThrow(/scale/)
    expect(() => canonicalJsonNumber('1'.repeat(65))).toThrow(/precision/)
  })

  it('preserves path/equals number source through parse and writes it back as a raw number', () => {
    expect(supportsLosslessJsonNumbers()).toBe(true)
    const parsed = parseJsonPreservingPathEqualsNumbers(
      '{"requestBody":{"path":"/n","equals":9007199254740993,"other":1}}',
    ) as {
      requestBody: { path: string; equals: { rawJson: string }; other: number }
    }
    expect(parsed.requestBody.equals).toEqual({ rawJson: '9007199254740993' })
    expect(parsed.requestBody.other).toBe(1)
    expect(JSON.stringify({ equals: wireJsonNumber(parsed.requestBody.equals, 'equals') }))
      .toBe('{"equals":9007199254740993}')
  })

  it('serializes cloned raw-number markers and internal sentinels as JSON numbers', () => {
    expect(stringifyJsonPreservingNumbers({
      path: '/n',
      equals: { rawJson: '9007199254740993' },
      native: { rawJSON: '2.500' },
      escaped: 'a\n"b',
    })).toBe(
      '{"path":"/n","equals":9007199254740993,"native":2.5,"escaped":"a\\n\\"b"}',
    )
    expect(() => stringifyJsonPreservingNumbers({
      self: (() => {
        const value: Record<string, unknown> = {}
        value.self = value
        return value
      })(),
    })).toThrow(/circular/)
  })

  it('explicitly rejects number conditions when JSON.parse source access is unavailable', () => {
    const original = JSON.parse
    const spy = vi.spyOn(JSON, 'parse').mockImplementation(((
      input: string,
      reviver?: (this: unknown, key: string, value: unknown) => unknown,
    ) => original(input, function (this: unknown, key: string, value: unknown) {
      return reviver ? reviver.call(this, key, value) : value
    })) as typeof JSON.parse)
    try {
      expect(supportsLosslessJsonNumbers()).toBe(false)
      expect(() =>
        parseJsonPreservingPathEqualsNumbers('{"requestBody":{"path":"/n","equals":2}}'),
      ).toThrow(/source text access/)
      expect(() => wireJsonNumber({ rawJson: '2' }, 'equals')).toThrow(/exact JSON number/)
    } finally {
      spy.mockRestore()
    }
  })
})
