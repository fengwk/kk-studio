import { describe, expect, it } from 'vitest'

import {
  MAX_IDE_LEASE_BINDINGS,
  PROTOCOL_VERSION,
  decodeDataBind,
  decodeEnvelope,
  decodeHeartbeat,
  decodeIdeLease,
  decodeRuntimeStatus,
  encodeDataBind,
  encodeHeartbeat,
  encodeIdeLease,
  encodeRuntimeStatus,
  validateDataBindOrigin,
  type IdeLease,
  type RuntimeStatus,
} from '../src/protocol.js'

const ENVIRONMENT_ID = '11111111-2222-4333-8444-555555555555'
const VIEW_ID = '22222222-3333-4444-8555-666666666666'
const DOCUMENT_ID = '77777777-8888-4999-8aaa-bbbbbbbbbbbb'
const TICKET = 'A'.repeat(43)

const status: RuntimeStatus = {
  revision: '12',
  status: {
    daemonVersion: '4.0.3',
    update: { targetVersion: '4.0.4', downloadedBytes: 12, totalBytes: 100 },
  },
}

const binding = {
  generation: '7',
  ticket: TICKET,
  streamUrl: 'https://studio.example.com/api/daemon-data?generation=7',
}

const lease: IdeLease = {
  round: '3',
  bindings: [{ viewId: VIEW_ID, documentId: DOCUMENT_ID, bindingEpoch: '9' }],
}

function envelope(messageType: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    protocolVersion: PROTOCOL_VERSION,
    messageType,
    environmentId: ENVIRONMENT_ID,
    payload: {},
    ...extra,
  }
}

describe('runtime status and heartbeat codecs', () => {
  it('round-trips the complete status and heartbeat with a null update', () => {
    expect(decodeRuntimeStatus(encodeRuntimeStatus(status))).toEqual(status)
    expect(
      decodeHeartbeat(encodeHeartbeat({ runtimeStatus: status, ideLeaseRound: '9' })),
    ).toEqual({ runtimeStatus: status, ideLeaseRound: '9' })
    expect(
      decodeHeartbeat(
        encodeHeartbeat({
          runtimeStatus: { revision: '1', status: { daemonVersion: '4.0.3', update: null } },
          ideLeaseRound: null,
        }),
      ),
    ).toEqual({
      runtimeStatus: { revision: '1', status: { daemonVersion: '4.0.3', update: null } },
      ideLeaseRound: null,
    })
  })

  it('rejects malformed revisions, update progress, and heartbeat fields', () => {
    expect(() => decodeRuntimeStatus(JSON.stringify({ ...status, revision: '0' }))).toThrow(/revision/)
    expect(() => decodeRuntimeStatus(JSON.stringify({ ...status, revision: 12 }))).toThrow(/revision/)
    expect(() =>
      decodeRuntimeStatus(JSON.stringify({ ...status, status: { daemonVersion: '4.0.3' } })),
    ).toThrow(/update/)
    expect(() =>
      decodeRuntimeStatus(
        JSON.stringify({
          ...status,
          status: { ...status.status, update: { targetVersion: '4.0.4', downloadedBytes: 11, totalBytes: 10 } },
        }),
      ),
    ).toThrow(/totalBytes/)
    expect(() =>
      decodeRuntimeStatus(
        JSON.stringify({
          ...status,
          status: { ...status.status, update: { targetVersion: '4.0.4', downloadedBytes: -1, totalBytes: null } },
        }),
      ),
    ).toThrow(/downloadedBytes/)
    expect(() =>
      decodeRuntimeStatus(
        JSON.stringify({ ...status, status: { ...status.status, update: { ...status.status.update, extra: true } } }),
      ),
    ).toThrow(/unexpected|unknown/)
    expect(() => decodeHeartbeat(JSON.stringify({ runtimeStatus: status }))).toThrow(/ideLeaseRound/)
    expect(() => decodeHeartbeat(JSON.stringify({ runtimeStatus: status, ideLeaseRound: '0' }))).toThrow(/ideLeaseRound/)
    expect(() =>
      decodeHeartbeat(JSON.stringify({ runtimeStatus: status, ideLeaseRound: null, extra: true })),
    ).toThrow(/unexpected|unknown/)
    expect(() => decodeHeartbeat(JSON.stringify({ runtimeStatus: null, ideLeaseRound: null }))).toThrow(/runtimeStatus/)
  })
})

describe('DATA_BIND codec', () => {
  it('round-trips a same-origin binding and accepts only the configured origin', () => {
    expect(decodeDataBind(encodeDataBind(binding))).toEqual(binding)
    expect(validateDataBindOrigin(binding, 'https://studio.example.com')).toEqual(binding)
    expect(() => validateDataBindOrigin(binding, 'https://other.example.com')).toThrow(/same-origin/)
    expect(() => validateDataBindOrigin(binding, 'ftp://studio.example.com')).toThrow(/http or https/)
    expect(() => validateDataBindOrigin(binding, 'not a url')).toThrow(/absolute/)
  })

  it('rejects invalid generation, ticket, URL structure, and unknown fields', () => {
    expect(() => decodeDataBind(JSON.stringify({ ...binding, generation: '0' }))).toThrow(/generation/)
    expect(() => decodeDataBind(JSON.stringify({ ...binding, ticket: 'short' }))).toThrow(/ticket/)
    expect(() =>
      decodeDataBind(JSON.stringify({ ...binding, streamUrl: 'https://user:secret@studio.example.com/data' })),
    ).toThrow(/credentials/)
    expect(() =>
      decodeDataBind(JSON.stringify({ ...binding, streamUrl: 'https://studio.example.com/data#fragment' })),
    ).toThrow(/fragment/)
    expect(() =>
      decodeDataBind(JSON.stringify({ ...binding, streamUrl: 'wss://studio.example.com/data' })),
    ).toThrow(/http or https/)
    expect(() =>
      decodeDataBind(JSON.stringify({ ...binding, streamUrl: 'https://studio.example.com/\u0000data' })),
    ).toThrow(/control characters/)
    expect(() => decodeDataBind(JSON.stringify({ ...binding, extra: true }))).toThrow(/unexpected|unknown/)
  })
})

describe('IDE_LEASE codec', () => {
  it('round-trips batches without imposing a lower bound on empty bindings', () => {
    expect(decodeIdeLease(encodeIdeLease(lease))).toEqual(lease)
    expect(decodeIdeLease(JSON.stringify({ round: '4', bindings: [] }))).toEqual({ round: '4', bindings: [] })
  })

  it('rejects duplicate views, malformed identifiers, and oversized or unknown fields', () => {
    expect(() =>
      decodeIdeLease(
        JSON.stringify({
          round: '1',
          bindings: [
            { viewId: VIEW_ID, documentId: DOCUMENT_ID, bindingEpoch: '1' },
            { viewId: VIEW_ID, documentId: DOCUMENT_ID, bindingEpoch: '2' },
          ],
        }),
      ),
    ).toThrow(/repeat viewId/)
    expect(() =>
      decodeIdeLease(
        JSON.stringify({
          round: '1',
          bindings: [{ viewId: 'not-a-uuid', documentId: DOCUMENT_ID, bindingEpoch: '1' }],
        }),
      ),
    ).toThrow(/viewId/)
    expect(() =>
      decodeIdeLease(
        JSON.stringify({
          round: '1',
          bindings: [{ viewId: VIEW_ID, documentId: DOCUMENT_ID, bindingEpoch: '0' }],
        }),
      ),
    ).toThrow(/bindingEpoch/)
    expect(() =>
      decodeIdeLease(
        JSON.stringify({
          round: '1',
          bindings: Array.from({ length: MAX_IDE_LEASE_BINDINGS + 1 }, (_, index) => ({
            viewId: `${String(index).padStart(8, '0')}-3333-4444-8555-666666666666`,
            documentId: DOCUMENT_ID,
            bindingEpoch: '1',
          })),
        }),
      ),
    ).toThrow(/must not exceed/)
    expect(() =>
      decodeIdeLease(JSON.stringify({ ...lease, bindings: [{ ...lease.bindings[0], extra: true }] })),
    ).toThrow(/unexpected|unknown/)
  })
})

describe('extension envelope scope', () => {
  it('requires an environment and forbids invocationId for environment-level messages', () => {
    for (const messageType of ['RUNTIME_STATUS', 'DATA_BIND', 'IDE_LEASE']) {
      expect(decodeEnvelope(JSON.stringify(envelope(messageType)))).toMatchObject({
        messageType,
        environmentId: ENVIRONMENT_ID,
        invocationId: null,
      })
      expect(() =>
        decodeEnvelope(JSON.stringify(envelope(messageType, { environmentId: null }))),
      ).toThrow(/environmentId/)
      expect(() =>
        decodeEnvelope(JSON.stringify(envelope(messageType, { invocationId: 'inv-1' }))),
      ).toThrow(/invocationId/)
    }
  })
})
