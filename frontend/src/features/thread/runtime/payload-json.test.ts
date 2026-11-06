import { describe, expect, it } from 'vitest'
import { asRecord, formatRawJsonText, getRecordList, parseRawJsonText } from '@/features/thread/runtime/payload-json'
import { getString } from '@/shared/lib/value'

describe('payload-json helpers', () => {
  it('reads object nodes without interpreting strings as serialized payloads', () => {
    expect(asRecord({ content: 'ok' })).toEqual({ content: 'ok' })
    expect(asRecord('{"content":"ok"}')).toEqual({})
    expect(asRecord(null)).toEqual({})
    expect(asRecord([])).toEqual({})
  })

  it('parses explicitly raw JSON while retaining invalid text for inspection', () => {
    expect(parseRawJsonText('{"content":"ok"}')).toEqual({ content: 'ok' })
    expect(parseRawJsonText('{bad')).toBe('{bad')
    expect(formatRawJsonText('{"content":"ok"}')).toBe('{\n  "content": "ok"\n}')
    expect(formatRawJsonText('{bad')).toBe('{bad')
  })

  it('normalizes record lists and scalar payload fields without coercion', () => {
    expect(getRecordList([{ id: 1 }, null, [], 'text', {}])).toEqual([{ id: 1 }])
    expect(getString('text')).toBe('text')
    expect(getString(1)).toBe('')
  })
})
