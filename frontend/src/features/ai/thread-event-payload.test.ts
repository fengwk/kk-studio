import { describe, expect, it } from 'vitest'
import { asRecord, getInteger, getRecordList, getString, getToolContentType, parsePayload } from '@/features/ai/thread-event-payload'

describe('thread event payload helpers', () => {
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
    expect(getInteger(1)).toBe(1)
    expect(getInteger(-1)).toBe(-1)
    expect(getInteger(1.2)).toBeNull()
    expect(getInteger('1')).toBeNull()
  })

  it('accepts only transcript-supported tool content types', () => {
    expect(getToolContentType('text')).toBe('text')
    expect(getToolContentType('image')).toBe('image')
    expect(getToolContentType('audio')).toBe('audio')
    expect(getToolContentType('video')).toBe('video')
    expect(getToolContentType('artifact')).toBeNull()
  })
})
