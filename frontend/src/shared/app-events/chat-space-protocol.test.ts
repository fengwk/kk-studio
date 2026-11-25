import { describe, expect, it } from 'vitest'
import { decodeServerMessage, INVALID_EVENT_PAYLOAD } from './protocol'

const resource = { kind: 'chat-space', id: '780cbbd3-a765-45df-aec6-8048b6d8ce2a' }
const scheduleId = '11111111-2222-4333-8444-555555555555'
const decode = (frame: unknown) => decodeServerMessage(JSON.stringify(frame))

describe('chat-space event frames', () => {
  it.each([
    ['tree', { treeVersion: '9007199254740993' }],
    ['threads', {}],
    ['space', { version: '0' }],
    ['schedules', {}],
    ['schedules', { scheduleId }],
  ])('decodes %s without a durable cursor', (name, data) => {
    const frame = { type: 'event', resource, name, data }
    expect(decode(frame)).toEqual(frame)
    expect(decode({ ...frame, cursor: '0' })).toBeNull()
  })

  it('scopes bad payloads to the space and accepts only the hint ACK cursor', () => {
    expect(decode({ type: 'event', resource, name: 'tree', data: { treeVersion: '01' } }))
      .toMatchObject({ type: 'error', resource, code: INVALID_EVENT_PAYLOAD })
    expect(decode({ type: 'event', resource, name: 'schedules', data: { scheduleId: 'not-a-uuid' } }))
      .toMatchObject({ type: 'error', resource, code: INVALID_EVENT_PAYLOAD })
    expect(decode({ type: 'event', resource, name: 'schedules', data: { scheduleId, extra: 1 } }))
      .toMatchObject({ type: 'error', resource, code: INVALID_EVENT_PAYLOAD })
    const ack = { type: 'subscribed', resource, cursor: '0' }
    expect(decode(ack)).toEqual(ack)
    expect(decode({ ...ack, cursor: '1' })).toBeNull()
  })
})
