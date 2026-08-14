import { describe, expect, it } from 'vitest'
import {
  decodeServerMessage,
  encodeClientMessage,
} from '@/shared/app-events/protocol'

const threadResource = { kind: 'thread', id: 't-1' } as const
const canvasResource = { kind: 'canvas', id: 'c-1' } as const

describe('encodeClientMessage', () => {
  it('encodes subscribe/unsubscribe with the resource', () => {
    expect(JSON.parse(encodeClientMessage({ type: 'subscribe', resource: threadResource }))).toEqual({
      type: 'subscribe',
      resource: threadResource,
    })
    expect(
      JSON.parse(encodeClientMessage({ type: 'unsubscribe', resource: canvasResource })),
    ).toEqual({ type: 'unsubscribe', resource: canvasResource })
  })
})

describe('decodeServerMessage', () => {
  it('decodes subscribed/resync frames', () => {
    expect(decodeServerMessage(JSON.stringify({ type: 'subscribed', resource: threadResource })))
      .toEqual({ type: 'subscribed', resource: threadResource })
    expect(decodeServerMessage(JSON.stringify({ type: 'resync', resource: canvasResource })))
      .toEqual({ type: 'resync', resource: canvasResource })
  })

  it('decodes event frames with known names and any JSON data', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'event', resource: threadResource, name: 'revision' }),
      ),
    ).toEqual({ type: 'event', resource: threadResource, name: 'revision', data: undefined })
    expect(
      decodeServerMessage(
        JSON.stringify({
          type: 'event',
          resource: threadResource,
          name: 'realtime',
          data: '{"type":"MODEL_DELTA"}',
        }),
      ),
    ).toEqual({
      type: 'event',
      resource: threadResource,
      name: 'realtime',
      data: '{"type":"MODEL_DELTA"}',
    })
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'event', resource: canvasResource, name: 'version', data: { version: '3' } }),
      ),
    ).toEqual({ type: 'event', resource: canvasResource, name: 'version', data: { version: '3' } })
  })

  it('decodes error frames with and without a resource', () => {
    expect(decodeServerMessage(JSON.stringify({ type: 'error', message: 'boom' }))).toEqual({
      type: 'error',
      resource: undefined,
      message: 'boom',
    })
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'error', resource: threadResource, message: 'boom' }),
      ),
    ).toEqual({ type: 'error', resource: threadResource, message: 'boom' })
  })

  it('rejects malformed JSON and non-object frames', () => {
    expect(decodeServerMessage('not json')).toBeNull()
    expect(decodeServerMessage('')).toBeNull()
    expect(decodeServerMessage('null')).toBeNull()
    expect(decodeServerMessage('[]')).toBeNull()
    expect(decodeServerMessage('"string"')).toBeNull()
  })

  it('rejects unknown types, missing resources and malformed resources', () => {
    expect(decodeServerMessage(JSON.stringify({ type: 'unknown' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ type: 'subscribed' }))).toBeNull()
    expect(decodeServerMessage(JSON.stringify({ type: 'subscribed', resource: {} }))).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ type: 'subscribed', resource: { kind: 'file', id: 'x' } })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ type: 'subscribed', resource: { kind: 'thread' } })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ type: 'subscribed', resource: { kind: 'thread', id: '' } })),
    ).toBeNull()
    expect(
      decodeServerMessage(JSON.stringify({ type: 'resync', resource: { kind: 'canvas', id: 7 } })),
    ).toBeNull()
  })

  it('rejects event frames with unknown names and keeps valid ones', () => {
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'event', resource: threadResource, name: 'snapshot' }),
      ),
    ).toBeNull()
    expect(
      decodeServerMessage(
        JSON.stringify({ type: 'event', resource: threadResource, name: 'revision', data: {} }),
      ),
    ).toEqual({ type: 'event', resource: threadResource, name: 'revision', data: {} })
  })
})
