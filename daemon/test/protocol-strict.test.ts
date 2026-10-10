import { describe, expect, it } from 'vitest'

import type { CapabilityResult } from '../src/contracts.js'
import {
  ProtocolError,
  decodeCapabilities,
  decodeCompleted,
  decodeEnvelope,
  decodeIdeCommand,
  decodeInvoke,
  decodeUploadRequest,
  decodeUploadTicket,
  decodeUpdateCommand,
  decodeUpdateResult,
  encodeCompleted,
  encodeIdeCommand,
  encodeProgress,
  parseStrictJson,
  parseStrictObject,
  utf8ByteLength,
} from '../src/protocol.js'

const UUID = '11111111-2222-4333-8444-555555555555'
const SHA = 'a'.repeat(64)

const TINY_LIMITS = { maxBytes: 64, maxNestingDepth: 4, maxNumberLength: 8, maxStringBytes: 4 }

function envelope(messageType: string, extra: Record<string, unknown> = {}): string {
  return JSON.stringify({ protocolVersion: 4, messageType, payload: {}, ...extra })
}

function result(contents: unknown[]): CapabilityResult {
  return { callId: 'inv-1', error: false, details: {}, contents: contents as CapabilityResult['contents'] }
}

function completedText(contents: unknown[]): string {
  return JSON.stringify({ result: { callId: 'inv-1', error: false, details: {}, contents } })
}

describe('utf8ByteLength boundaries', () => {
  it('counts 2-byte, 3-byte and surrogate-pair characters', () => {
    expect(utf8ByteLength('é', 'x')).toBe(2)
    expect(utf8ByteLength('中', 'x')).toBe(3)
    expect(utf8ByteLength('😀', 'x')).toBe(4)
  })

  it('rejects a lone low surrogate', () => {
    expect(() => utf8ByteLength('\udc00', 'x')).toThrow(ProtocolError)
  })
})

describe('parseStrictJson / parseStrictObject', () => {
  it('rejects non-string input and non-object roots', () => {
    expect(() => parseStrictJson(123, 'x', TINY_LIMITS)).toThrow(/must be a string/)
    expect(() => parseStrictObject('[1,2]', 'x', TINY_LIMITS)).toThrow(/must be a JSON object/)
  })

  it('rejects oversized strings and invalid unicode in the raw document', () => {
    expect(() => parseStrictJson('"aaaaa"', 'x', TINY_LIMITS)).toThrow(/string exceeds/)
    expect(() => parseStrictJson('"\\ud800"', 'x', TINY_LIMITS)).toThrow(/valid Unicode/)
  })

  it('enforces nesting depth on nested objects', () => {
    let nested = '{"a":1}'
    for (let index = 0; index < 8; index++) {
      nested = `{"a":${nested}}`
    }
    expect(() => parseStrictJson(nested, 'x', TINY_LIMITS)).toThrow(/nesting depth/)
  })

  it('enforces the overall byte limit', () => {
    expect(() => parseStrictJson('1234567890', 'x', { ...TINY_LIMITS, maxBytes: 4 })).toThrow(
      /exceeds 4 UTF-8 bytes/,
    )
  })
})

describe('envelope field validation branches', () => {
  it('rejects non-integer protocolVersion and non-string optional fields', () => {
    expect(() => decodeEnvelope(envelope('HELLO', { protocolVersion: '3' }))).toThrow(
      /protocolVersion must be an integer/,
    )
    expect(() => decodeEnvelope(envelope('READY', { environmentId: UUID, invocationId: 5 }))).toThrow(
      /invocationId must be a string/,
    )
  })

  it('rejects blank required text in INVOKE payloads', () => {
    expect(() =>
      decodeInvoke(JSON.stringify({ capabilityId: '', capabilityVersion: '2', arguments: {}, timeoutMillis: 0 })),
    ).toThrow(/capabilityId must be non-blank text/)
  })
})

describe('READY capabilities validation branches', () => {
  const environment = {
    operatingSystem: 'linux',
    architecture: 'arm64',
    timeZone: 'UTC',
    userName: 'dev',
    homeDirectory: '/home/dev',
    note: 'Linux environment.',
    tempDirectory: '/tmp',
  }

  it('rejects a non-integer version and an invalid daemonVersion', () => {
    expect(() =>
      decodeCapabilities(JSON.stringify({ version: 'four', daemonVersion: '1.0.12', environment })),
    ).toThrow(/version must be an integer/)
    expect(() =>
      decodeCapabilities(JSON.stringify({ version: 5, daemonVersion: 'bad version', environment })),
    ).toThrow(/READY capabilities validation failed/)
  })
})

describe('result content validation branches', () => {
  it('rejects invalid mediaType and blank names', async () => {
    await expect(
      encodeCompleted(result([{ type: 'binary', bytes: new Uint8Array([1]), mediaType: 'NOPE' }]), 1024, null),
    ).rejects.toThrow(/mediaType must be canonical/)
    await expect(
      encodeCompleted(
        result([{ type: 'resource', uploadId: UUID, mediaType: 'text/plain', name: ' ', size: 1, sha256: SHA }]),
        1024,
        null,
      ),
    ).rejects.toThrow(/name must not be blank/)
    const longMedia = `${'a'.repeat(200)}/${'b'.repeat(200)}`
    await expect(
      encodeCompleted(result([{ type: 'binary', bytes: new Uint8Array([1]), mediaType: longMedia }]), 1024, null),
    ).rejects.toThrow(/mediaType must not exceed/)
  })

  it('rejects oversized names and previews', async () => {
    await expect(
      encodeCompleted(
        result([
          { type: 'resource', uploadId: UUID, mediaType: 'text/plain', name: 'n'.repeat(513), size: 1, sha256: SHA },
        ]),
        1024,
        null,
      ),
    ).rejects.toThrow(/name must not exceed/)
    await expect(
      encodeCompleted(
        result([
          {
            type: 'resource',
            uploadId: UUID,
            mediaType: 'text/plain',
            name: null,
            size: 1,
            sha256: SHA,
            preview: 'p'.repeat(16385),
          },
        ]),
        1024,
        null,
      ),
    ).rejects.toThrow(/preview must not exceed/)
  })

  it('encodes json and resource content and rejects unsupported content', async () => {
    expect(JSON.parse(encodeProgress(result([{ type: 'json', json: { a: 1 } }])))).toBeDefined()
    expect(
      JSON.parse(
        await encodeCompleted(
          result([
            { type: 'resource', uploadId: UUID, mediaType: 'text/plain', name: null, size: 3, sha256: SHA },
          ]),
          1024,
          null,
        ),
      ),
    ).toBeDefined()
    expect(() => encodeProgress(result([{ type: 'weird' }]))).toThrow(/must not contain weird content/)
    await expect(encodeCompleted(result([{ type: 'weird' }]), 1024, null)).rejects.toThrow(
      /unsupported result content/,
    )
  })

  it('validates the aggregate budget input and resource references before uploading', async () => {
    await expect(encodeCompleted(result([]), 0, null)).rejects.toThrow(/maximumResourceBytes must be positive/)
    await expect(
      encodeCompleted(
        result([{ type: 'resource', uploadId: 'not-a-uuid', mediaType: 'text/plain', name: null, size: 1, sha256: SHA }]),
        1024,
        null,
      ),
    ).rejects.toThrow(/must be uploaded to global storage/)
    await expect(
      encodeCompleted(
        result([{ type: 'resource', uploadId: UUID, mediaType: 'text/plain', name: null, size: 1, sha256: 'x' }]),
        1024,
        null,
      ),
    ).rejects.toThrow(/must declare size and sha256/)
  })

  it('requires a configured uploader for binary content', async () => {
    await expect(
      encodeCompleted(result([{ type: 'binary', bytes: new Uint8Array([1]), mediaType: 'text/plain' }]), 1024, null),
    ).rejects.toThrow(/resource upload is not configured/)
  })

  it('decodes json/resource content and rejects malformed result trees', () => {
    expect(decodeCompleted(completedText([{ type: 'json', json: 1 }]), 'inv-1', 1024).contents[0]).toEqual({
      type: 'json',
      json: 1,
    })
    expect(
      decodeCompleted(
        completedText([
          { type: 'resource', uploadId: UUID, mediaType: 'text/plain', name: null, size: 2, sha256: SHA, preview: 'p' },
        ]),
        'inv-1',
        1024,
      ).contents[0],
    ).toMatchObject({ type: 'resource', size: 2 })
    expect(() => decodeCompleted(completedText([1]), 'inv-1', 1024)).toThrow(/must be a JSON object/)
    expect(() => decodeCompleted(completedText([{ type: 'text', text: 1 }]), 'inv-1', 1024)).toThrow(
      /text must be a string/,
    )
    expect(() => decodeCompleted(completedText([{ type: 'json' }]), 'inv-1', 1024)).toThrow(/must declare json/)
    expect(() =>
      decodeCompleted(completedText([{ type: 'resource', uploadId: UUID, mediaType: 'text/plain', name: null, size: 1, sha256: 'x' }]), 'inv-1', 1024),
    ).toThrow(/sha256 must be 64 lowercase hex/)
    expect(() => decodeCompleted(completedText([{ type: 'weird' }]), 'inv-1', 1024)).toThrow(/unknown content type/)
  })

  it('rejects mismatched callId, non-array contents and too many items', () => {
    expect(() => decodeCompleted(completedText([]), 'other', 1024)).toThrow(/callId does not match/)
    const notArray = JSON.stringify({ result: { callId: 'inv-1', error: false, details: {}, contents: {} } })
    expect(() => decodeCompleted(notArray, 'inv-1', 1024)).toThrow(/must be an array/)
    const tooMany = completedText(Array.from({ length: 65 }, () => ({ type: 'text', text: 'x' })))
    expect(() => decodeCompleted(tooMany, 'inv-1', 1024)).toThrow(/must not exceed 64 items/)
  })
})

describe('resource transfer validation branches', () => {
  it('rejects invalid sha, unknown ticket state and oversized failure message', () => {
    expect(() =>
      decodeUploadRequest(
        JSON.stringify({ transferId: UUID, mediaType: 'text/plain', name: null, size: 1, sha256: 'x' }),
      ),
    ).toThrow(/sha256 must be 64 lowercase hex/)
    expect(() => decodeUploadTicket(JSON.stringify({ transferId: UUID, state: 'NOPE' }))).toThrow(
      /state is unknown/,
    )
    expect(() =>
      decodeUploadTicket(JSON.stringify({ transferId: UUID, state: 'FAILED', message: 'm'.repeat(513) })),
    ).toThrow(/message must not exceed/)
  })

  it('validates presigned PUT metadata', () => {
    const put = (presignedPut: Record<string, unknown>): string =>
      JSON.stringify({ transferId: UUID, state: 'PENDING', uploadId: UUID, presignedPut })
    expect(() =>
      decodeUploadTicket(
        put({ method: 'PUT', url: `https://x/${'a'.repeat(5000)}`, headers: {}, expiresAt: '2030-01-01T00:00:00.000Z' }),
      ),
    ).toThrow(/url must not exceed 4096/)
    expect(() =>
      decodeUploadTicket(put({ method: 'PUT', url: 'u', headers: [], expiresAt: '2030-01-01T00:00:00.000Z' })),
    ).toThrow(/headers must be an object/)
    expect(() =>
      decodeUploadTicket(put({ method: 'PUT', url: 'u', headers: { '': 'v' }, expiresAt: '2030-01-01T00:00:00.000Z' })),
    ).toThrow(/non-blank strings/)
    expect(() =>
      decodeUploadTicket(
        put({
          method: 'PUT',
          url: 'u',
          headers: { n: 'x'.repeat(2000) },
          expiresAt: '2030-01-01T00:00:00.000Z',
        }),
      ),
    ).toThrow(/must not exceed 1024/)
    const manyHeaders = Object.fromEntries(Array.from({ length: 17 }, (_, index) => [`h${index}`, 'v']))
    expect(() =>
      decodeUploadTicket(put({ method: 'PUT', url: 'u', headers: manyHeaders, expiresAt: '2030-01-01T00:00:00.000Z' })),
    ).toThrow(/must not exceed 16 entries/)
  })
})

describe('managed update validation branches', () => {
  it('rejects an artifact sha with wrong shape and an invalid result message', () => {
    expect(() =>
      decodeUpdateCommand(
        JSON.stringify({
          operationId: UUID,
          targetVersion: '1.0.13',
          artifactUrl: 'https://github.com/fengwk/kk-studio/releases/download/v1.0.13/kk-studio-daemon-v1.0.13.jar',
          artifactSha256: 'zz',
        }),
      ),
    ).toThrow(/64 hex characters/)
    expect(() =>
      decodeUpdateResult(JSON.stringify({ operationId: UUID, phase: 'FAILED', message: '   ' })),
    ).toThrow(/bounded non-blank/)
    expect(() =>
      decodeUpdateResult(JSON.stringify({ operationId: UUID, phase: 'FAILED', message: 'm'.repeat(501) })),
    ).toThrow(/bounded non-blank/)
  })
})

describe('IDE command validation branches', () => {
  it('rejects blank optional fields, unknown mode and malformed headers', () => {
    expect(() => decodeIdeCommand(JSON.stringify({ action: 'OPEN_VIEW', viewId: '  ' }))).toThrow(
      /viewId must not be blank/,
    )
    expect(() => decodeIdeCommand(JSON.stringify({ action: 'OPEN_VIEW', mode: 'TCP' }))).toThrow(/mode is unknown/)
    expect(() => decodeIdeCommand(JSON.stringify({ action: 'OPEN_VIEW', headers: {} }))).toThrow(
      /headers must be an array/,
    )
    expect(() => decodeIdeCommand(JSON.stringify({ action: 'OPEN_VIEW', headers: [['a']] }))).toThrow(
      /headers must be an array/,
    )
  })

  it('round-trips valid mode and headers', () => {
    const command = { action: 'OPEN_STREAM' as const, mode: 'WS' as const, headers: [['a', 'b']] as [string, string][] }
    const decoded = decodeIdeCommand(encodeIdeCommand(command))
    expect(decoded).toEqual(command)
  })
})
