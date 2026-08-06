import { describe, expect, it } from 'vitest'
import { queryKeys } from '@/shared/lib/query-keys'

describe('queryKeys', () => {
  it('keeps one authoritative snapshot key per Thread', () => {
    expect(queryKeys.threads.snapshot('thread-1')).toEqual(['threads', 'snapshot', 'thread-1'])
  })

  it('keeps Chat-scoped Thread lists and chat details in distinct cache scopes', () => {
    expect(queryKeys.chats.detail('chat-1')).toEqual(['chats', 'detail', 'chat-1'])
    expect(queryKeys.chats.threads('chat-1')).toEqual(['chats', 'threads', 'chat-1'])
  })
})
