import { describe, expect, it } from 'vitest'
import { decodeInteractionPage } from './ai-interaction-codec'
import { interactionIdentity } from '@/shared/lib/interactions'

const uuid = '33333333-3333-3333-3333-333333333333'
const owner = {
  type: 'CHAT', chatId: uuid, chatTitle: 'Chat', issueId: null,
  issueTitle: null, agentName: null, rootThreadName: 'Root',
}
const environment = {
  type: 'ENVIRONMENT_WAIT', rootThreadId: uuid, owner, createTime: 1780000000.125,
  interactionId: null, status: null, threadId: null, sessionId: null,
  toolCallId: null, toolName: null, argumentsJson: null, approvalJson: null,
  environmentId: uuid, environmentName: 'archlinux', waitingCount: 2,
}
const input = {
  ...environment, type: 'INPUT', interactionId: uuid, status: 'WAITING_INPUT',
  threadId: uuid, sessionId: uuid, toolCallId: 'call', toolName: 'ask_user',
  argumentsJson: '{}', environmentId: null, environmentName: null, waitingCount: null,
}
const page = (items: unknown[]) => ({ items, nextCursor: null, total: items.length, freshnessAt: null })

describe('interaction wire union', () => {
  it('decodes all three variants without inventing an actionable environment id', () => {
    const result = decodeInteractionPage(page([
      input, { ...input, type: 'APPROVAL', status: 'WAITING_APPROVAL', approvalJson: '{}' },
      environment,
    ]))
    expect(result.items.map((item) => item.type)).toEqual(['INPUT', 'APPROVAL', 'ENVIRONMENT_WAIT'])
    expect(result.items[2].interactionId).toBeNull()
    expect(result.items[2].threadId).toBeNull()
    expect(result.items[2].owner.chatTitle).toBe('Chat')
    expect(result.items[2].createTime).toBe(1780000000.125)
  })

  it('uses root+environment identity instead of merging null invocation ids', () => {
    const items = decodeInteractionPage(page([
      environment, { ...environment, rootThreadId: '44444444-4444-4444-4444-444444444444' },
    ])).items
    expect(new Set(items.map(interactionIdentity)).size).toBe(2)
  })

  it('preserves the single reconciliation deadline', () => {
    expect(decodeInteractionPage({ ...page([]), freshnessAt: 1780000000.5 }).freshnessAt)
      .toBe(1780000000.5)
  })

  it.each([
    { ...environment, interactionId: uuid },
    { ...environment, waitingCount: 0 },
    { ...environment, waitingCount: 1.5 },
    { ...environment, environmentId: 'not-a-uuid' },
    { ...environment, status: 'WAITING_APPROVAL' },
    { ...environment, type: undefined },
    { ...input, status: 'WAITING_APPROVAL' },
    { ...input, environmentId: uuid },
    { ...input, approvalJson: '{}' },
    { ...input, owner: { ...owner, chatTitle: undefined } },
  ])('rejects contradictory or incomplete payload %#', (item) => {
    expect(() => decodeInteractionPage(page([item]))).toThrow()
  })

  it.each([
    { ...page([]), total: -1 },
    { ...page([]), total: 1.5 },
    { ...page([]), freshnessAt: undefined },
    { ...page([]), nextCursor: undefined },
    { ...page([environment]), items: [{ ...environment, createTime: [2026, 10, 8] }] },
  ])('fails closed on malformed page %#', (value) => {
    expect(() => decodeInteractionPage(value)).toThrow()
  })
})
