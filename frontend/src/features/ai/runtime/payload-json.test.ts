import { describe, expect, it } from 'vitest'
import { asRecord, getRecordList, getString, parsePayload } from '@/features/ai/runtime/payload-json'

describe('payload-json helpers', () => {
  it('parses only object payloads and safely falls back for absent or malformed input', () => {
    expect(parsePayload('{"content":"ok"}')).toEqual({ content: 'ok' })
    expect(parsePayload(null)).toEqual({})
    expect(parsePayload('')).toEqual({})
    expect(parsePayload('[]')).toEqual({})
    expect(parsePayload('{bad')).toEqual({})
    expect(asRecord(null)).toEqual({})
    expect(asRecord([])).toEqual({})
  })

  it('normalizes record lists and scalar payload fields without coercion', () => {
    expect(getRecordList([{ id: 1 }, null, [], 'text', {}])).toEqual([{ id: 1 }])
    expect(getString('text')).toBe('text')
    expect(getString(1)).toBe('')
  })
})
