import { describe, expect, it } from 'vitest'
import {
  compareCatalogVersions,
  filterChats,
  formatBackendDate,
  mergeChatIntoList,
  mergeChatList,
  preferNewerChat,
} from '@/features/ai/chat/chat-utils'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'

function chat(overrides: Partial<ChatDTO> = {}): ChatDTO {
  return {
    id: 'chat-1',
    title: 'title',
    agentName: 'agent',
    environment: null,
    yoloEnabled: false,
    version: '1',
    createTime: '2026-06-20T02:00:00',
    updateTime: '2026-06-20T02:00:00',
    ...overrides,
  }
}

describe('compareCatalogVersions', () => {
  it('compares canonical decimal versions with plain comparison', () => {
    expect(compareCatalogVersions('1', '1')).toBe(0)
    expect(compareCatalogVersions('10', '9')).toBe(1)
    expect(compareCatalogVersions('9', '10')).toBe(-1)
  })

  it('treats non-numeric versions as zero instead of throwing', () => {
    expect(compareCatalogVersions('abc', '2')).toBe(-1)
    expect(compareCatalogVersions('2', 'abc')).toBe(1)
    expect(compareCatalogVersions('abc', 'xyz')).toBe(0)
  })
})

describe('preferNewerChat', () => {
  it('keeps the current chat when it has a strictly newer version', () => {
    const current = chat({ id: 'chat-1', version: '5', title: 'current' })
    expect(preferNewerChat(current, chat({ id: 'chat-1', version: '4', title: 'incoming' }))).toBe(
      current,
    )
  })

  it('prefers incoming on missing current and on equal or newer versions', () => {
    const incoming = chat({ id: 'chat-1', version: '4', title: 'incoming' })
    expect(preferNewerChat(undefined, incoming)).toBe(incoming)
    expect(preferNewerChat(chat({ version: '4', title: 'current' }), incoming)).toBe(incoming)
    expect(
      preferNewerChat(chat({ version: '3', title: 'current' }), incoming),
    ).toBe(incoming)
  })
})

describe('mergeChatList', () => {
  it('returns the incoming list unchanged when no current cache exists', () => {
    const incoming = [chat({ id: 'chat-1', version: '1' })]
    expect(mergeChatList(undefined, incoming)).toBe(incoming)
  })

  it('merges by id preferring newer versions and keeps the incoming order', () => {
    const current = [
      chat({ id: 'chat-1', version: '10', title: 'cached newer' }),
      chat({ id: 'chat-2', version: '1' }),
    ]
    const incoming = [
      chat({ id: 'chat-1', version: '2', title: 'server stale' }),
      chat({ id: 'chat-3', version: '1', title: 'new' }),
    ]
    const merged = mergeChatList(current, incoming)
    expect(merged.map((item) => item.title)).toEqual(['cached newer', 'new'])
    // server list order is authoritative; only conflicting ids fall back to the cache
    expect(merged.map((item) => item.id)).toEqual(['chat-1', 'chat-3'])
  })
})

describe('mergeChatIntoList', () => {
  it('returns the undefined cache unchanged when no current list exists', () => {
    expect(mergeChatIntoList(undefined, chat())).toBeUndefined()
  })

  it('replaces a matching chat only when the incoming version is newer or equal', () => {
    const current = [
      chat({ id: 'chat-1', version: '10', title: 'cached' }),
      chat({ id: 'chat-2', version: '1', title: 'other' }),
    ]
    // stale authoritative response must not downgrade the cache
    expect(
      mergeChatIntoList(current, chat({ id: 'chat-1', version: '9', title: 'server' })),
    ).toEqual([
      chat({ id: 'chat-1', version: '10', title: 'cached' }),
      chat({ id: 'chat-2', version: '1', title: 'other' }),
    ])
    // equal or newer response wins
    expect(
      mergeChatIntoList(current, chat({ id: 'chat-1', version: '10', title: 'server' })),
    ).toEqual([
      chat({ id: 'chat-1', version: '10', title: 'server' }),
      chat({ id: 'chat-2', version: '1', title: 'other' }),
    ])
  })

  it('appends the incoming chat at the end when its id is not cached', () => {
    const current = [chat({ id: 'chat-1', version: '1' })]
    expect(
      mergeChatIntoList(current, chat({ id: 'chat-2', version: '1', title: 'new' })),
    ).toEqual([
      chat({ id: 'chat-1', version: '1' }),
      chat({ id: 'chat-2', version: '1', title: 'new' }),
    ])
  })
})

describe('filterChats', () => {
  it('filters by title and keeps the server order for equal timestamps', () => {
    const chats = [
      chat({ id: '2', title: 'beta', createTime: '2026-06-21T00:00:00' }),
      chat({ id: '10', title: 'alpha', createTime: '2026-06-21T00:00:00' }),
    ]
    // numeric-aware id tie-break: '10' is newer than '2'
    expect(filterChats(chats, '')).toMatchObject([{ id: '10' }, { id: '2' }])
    expect(filterChats(chats, 'alp')).toMatchObject([{ id: '10' }])
    expect(filterChats(chats, 'missing')).toEqual([])
    expect(filterChats(chats, '  ')).toHaveLength(2)
  })

  it('sorts by the extracted timestamp before falling back to the id tie-break', () => {
    const chats = [
      chat({ id: '1', title: 'old', createTime: '2026-06-20T00:00:00' }),
      chat({ id: '2', title: 'new', createTime: '2026-06-22T00:00:00' }),
    ]
    expect(filterChats(chats, '').map((item) => item.title)).toEqual(['new', 'old'])
  })

  it('handles invalid timestamp shapes by falling back to the id tie-break', () => {
    const chats = [
      chat({ id: '2', title: 'a', createTime: null }),
      chat({ id: '10', title: 'b', createTime: 'not-a-date' }),
      chat({ id: '3', title: 'c', createTime: [] }),
    ]
    // invalid times sort as 0; the remaining array order is then fixed by the id tie-break
    expect(filterChats(chats, '').map((item) => item.id)).toEqual(['10', '3', '2'])
  })

  it('treats the empty title as a searchable value', () => {
    const chats = [
      chat({ id: '1', title: '', createTime: '2026-06-20T00:00:00' }),
      chat({ id: '2', title: null, createTime: '2026-06-20T00:00:00' }),
    ]
    // both nullish titles match any search; the title fallback branch is exercised
    expect(filterChats(chats, '')).toHaveLength(2)
    expect(filterChats(chats, 'title')).toHaveLength(0)
  })
})

describe('backendTimeValue', () => {
  it('sorts by the full date-time array with defaults for missing parts', () => {
    const chats = [
      chat({ id: 'array-full', title: 'a', createTime: [2026, 6, 21, 1, 2, 3] }),
      chat({ id: 'array-partial', title: 'b', createTime: [2026] }),
    ]
    // full parts and defaulted (year, 1, 1) parts both produce comparable UTC sort keys
    expect(filterChats(chats, '').map((item) => item.id)).toEqual(['array-full', 'array-partial'])
  })

  it('sorts by the raw numeric timestamp value', () => {
    const chats = [
      chat({ id: 'low', title: 'a', createTime: 1234 }),
      chat({ id: 'high', title: 'b', createTime: 5678 }),
    ]
    expect(filterChats(chats, '').map((item) => item.id)).toEqual(['high', 'low'])
  })

  it('coerces non-numeric array parts to defaults and falls back to the id tie-break', () => {
    const chats = [
      // month/day NaN coerce through (month || 1)/(day || 1); year NaN yields a non-finite Date.UTC result
      chat({ id: 'a1', title: 'a', createTime: ['x', 6, 21] }),
      chat({ id: 'a2', title: 'b', createTime: [2026, 'x', 'y'] }),
    ]
    // both compute a zero sort key, so the numeric-aware id tie-break decides
    expect(filterChats(chats, '').map((item) => item.id)).toEqual(['a2', 'a1'])
  })
})

describe('formatBackendDate', () => {
  it('formats date-time arrays with 1-based month and zero-padded fields', () => {
    expect(formatBackendDate([2026, 6, 5, 9, 7, 30])).toBe('2026-06-05 09:07')
    expect(formatBackendDate([2026])).toBe('2026-01-01 00:00')
  })

  it('returns a placeholder for null, empty arrays and non-finite years', () => {
    expect(formatBackendDate(null)).toBe('-')
    expect(formatBackendDate([])).toBe('-')
    expect(formatBackendDate([Number.NaN])).toBe('-')
  })

  it('converts numeric timestamps distinguishing seconds from milliseconds', () => {
    expect(formatBackendDate(0)).toBe('-')
    expect(formatBackendDate(1_782_000_000)).toBe('2026-06-21 00:00')
    expect(formatBackendDate(1_782_000_000_000)).toBe('2026-06-21 00:00')
  })

  it('normalizes ISO-like string timestamps to the first 16 characters', () => {
    expect(formatBackendDate('2026-06-20T02:01:00')).toBe('2026-06-20 02:01')
    expect(formatBackendDate('2026-06-20 02:01:00')).toBe('2026-06-20 02:01')
  })
})
