import { describe, expect, it } from 'vitest'
import {
  appendUniqueChats,
  filterChats,
  mergeChatPage,
} from '@/features/chat/chat-utils'
import { compareCatalogVersions } from '@/shared/lib/catalog-version'
import type { ChatDTO } from '@/shared/api/generated/contracts'

function chat(overrides: Partial<ChatDTO> = {}): ChatDTO {
  return {
    id: 'chat-1',
    title: 'title',
    agentName: 'agent',
    environmentName: null,
    yoloEnabled: false,
    version: '1',
    createdAt: 1781920800000,
    updatedAt: 1781920800000,
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

describe('per-id version preference inside mergeChatPage', () => {
  function mergedFirstItem(current: ChatDTO | undefined, incoming: ChatDTO): ChatDTO {
    const page = mergeChatPage(
      current == null ? undefined : { items: [current], nextCursor: null },
      { items: [incoming], nextCursor: null },
    )
    return page.items[0]!
  }

  it('keeps the current chat when it has a strictly newer version', () => {
    const current = chat({ id: 'chat-1', version: '5', title: 'current' })
    expect(mergedFirstItem(current, chat({ id: 'chat-1', version: '4', title: 'incoming' })))
      .toBe(current)
  })

  it('prefers incoming on equal or newer versions', () => {
    const incoming = chat({ id: 'chat-1', version: '4', title: 'incoming' })
    expect(mergedFirstItem(chat({ version: '4', title: 'current' }), incoming)).toBe(incoming)
    expect(mergedFirstItem(chat({ version: '3', title: 'current' }), incoming)).toBe(incoming)
  })
})

describe('mergeChatPage', () => {
  it('returns the incoming page unchanged when no current cache exists', () => {
    const incoming = { items: [chat({ id: 'chat-1', version: '1' })], nextCursor: 'next' }
    expect(mergeChatPage(undefined, incoming)).toBe(incoming)
  })

  it('merges page items by id preferring newer versions and keeps the incoming order and cursor', () => {
    const current = {
      items: [
        chat({ id: 'chat-1', version: '10', title: 'cached newer' }),
        chat({ id: 'chat-2', version: '1' }),
      ],
      nextCursor: 'stale',
    }
    const incoming = {
      items: [
        chat({ id: 'chat-1', version: '2', title: 'server stale' }),
        chat({ id: 'chat-3', version: '1', title: 'new' }),
      ],
      nextCursor: 'next',
    }
    const merged = mergeChatPage(current, incoming)
    expect(merged.items.map((item) => item.title)).toEqual(['cached newer', 'new'])
    // server page order is authoritative; only conflicting ids fall back to the cache
    expect(merged.items.map((item) => item.id)).toEqual(['chat-1', 'chat-3'])
    expect(merged.nextCursor).toBe('next')
  })
})

describe('appendUniqueChats', () => {
  it('appends only unseen chats and keeps the first occurrence order', () => {
    const current = [chat({ id: 'chat-1' }), chat({ id: 'chat-2' })]
    expect(appendUniqueChats(current, [
      chat({ id: 'chat-2', title: 'duplicate' }),
      chat({ id: 'chat-3', title: 'new' }),
    ])).toEqual([chat({ id: 'chat-1' }), chat({ id: 'chat-2' }), chat({ id: 'chat-3', title: 'new' })])
  })
})

describe('filterChats', () => {
  // 服务端契约是 updated_at 倒序：刚编辑过的对话即使创建更早也必须留在最前，前端不得按 createdAt 重排。
  it('keeps the server order instead of re-sorting by createdAt', () => {
    const chats = [
      chat({ id: '2', title: 'just edited', createdAt: 1781913600000, updatedAt: 1782086400000 }),
      chat({ id: '10', title: 'untouched', createdAt: 1782086400000, updatedAt: 1781913600000 }),
    ]
    expect(filterChats(chats, '').map((item) => item.id)).toEqual(['2', '10'])
  })

  // 同刻同值时服务端按 title asc, id asc 兜底，前端透传即可，不再用 id 降序覆盖。
  it('keeps the server order for equal timestamps', () => {
    const chats = [
      chat({ id: '2', title: 'same title', createdAt: 1782000000000, updatedAt: 1782000000000 }),
      chat({ id: '10', title: 'same title', createdAt: 1782000000000, updatedAt: 1782000000000 }),
    ]
    expect(filterChats(chats, '').map((item) => item.id)).toEqual(['2', '10'])
  })

  it('filters by title without reordering matching chats', () => {
    const chats = [
      chat({ id: '2', title: 'alpha', updatedAt: 3 }),
      chat({ id: '5', title: 'beta', updatedAt: 2 }),
      chat({ id: '10', title: 'alphabet', updatedAt: 1 }),
    ]
    expect(filterChats(chats, 'alp').map((item) => item.id)).toEqual(['2', '10'])
    expect(filterChats(chats, 'missing')).toEqual([])
    expect(filterChats(chats, '  ')).toEqual(chats)
  })

  it('treats the empty title as a searchable value', () => {
    const chats = [
      chat({ id: '1', title: '', createdAt: 1781913600000 }),
      chat({ id: '2', title: 'title', createdAt: 1781913600000 }),
    ]
    expect(filterChats(chats, '')).toHaveLength(2)
    expect(filterChats(chats, 'title')).toHaveLength(1)
  })
})
