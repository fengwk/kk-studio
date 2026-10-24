import { describe, expect, it } from 'vitest'

import type { CapabilityResult } from '../src/contracts.js'
import {
  CAPABILITY_CATALOG_VERSION,
  PROTOCOL_VERSION,
  ProtocolError,
  decodeCapabilities,
  decodeCompleted,
  decodeEnvelope,
  decodeIdeCommand,
  decodeIdeReply,
  decodeInvoke,
  decodeProgress,
  decodeUpdateCommand,
  decodeUpdateResult,
  decodeUploadCommit,
  decodeUploadRequest,
  decodeUploadTicket,
  encodeCapabilities,
  encodeCompleted,
  encodeEnvelope,
  encodeIdeCommand,
  encodeIdeReply,
  encodeInvoke,
  encodeProgress,
  encodeUpdateCommand,
  encodeUpdateResult,
  encodeUploadCommit,
  encodeUploadRequest,
  encodeUploadTicket,
  errorPayload,
  utf8ByteLength,
} from '../src/protocol.js'
import { artifactFixture } from './support/update-fixture.js'

const ENVIRONMENT_ID = '11111111-2222-4333-8444-555555555555'

function envelope(messageType: string, extra: Record<string, unknown> = {}): string {
  return JSON.stringify({ protocolVersion: PROTOCOL_VERSION, messageType, payload: {}, ...extra })
}

describe('envelope codec', () => {
  it('round-trips a bound invocation envelope', () => {
    const text = encodeEnvelope({
      protocolVersion: PROTOCOL_VERSION,
      messageType: 'INVOKE',
      environmentId: ENVIRONMENT_ID,
      invocationId: 'inv-1',
      payload: { capabilityId: 'fs.read' },
    })
    const decoded = decodeEnvelope(text)
    expect(decoded.messageType).toBe('INVOKE')
    expect(decoded.environmentId).toBe(ENVIRONMENT_ID)
    expect(decoded.invocationId).toBe('inv-1')
    expect(decoded.payload).toEqual({ capabilityId: 'fs.read' })
  })

  it('encodes HELLO without environmentId and rejects a declared one', () => {
    const text = encodeEnvelope({
      protocolVersion: PROTOCOL_VERSION,
      messageType: 'HELLO',
      environmentId: null,
      invocationId: null,
      payload: {},
    })
    expect(text).not.toContain('environmentId')
    expect(decodeEnvelope(text).environmentId).toBeNull()
    expect(() => decodeEnvelope(envelope('HELLO', { environmentId: ENVIRONMENT_ID }))).toThrow(ProtocolError)
  })

  it('requires non-null environmentId for bound messages', () => {
    expect(() => decodeEnvelope(envelope('READY'))).toThrow(/requires non-null environmentId/)
    expect(() => decodeEnvelope(envelope('HEARTBEAT'))).toThrow(ProtocolError)
  })

  it('requires invocationId for invocation messages', () => {
    expect(() => decodeEnvelope(envelope('INVOKE', { environmentId: ENVIRONMENT_ID }))).toThrow(
      /requires non-blank invocationId/,
    )
    expect(() =>
      decodeEnvelope(envelope('STARTED', { environmentId: ENVIRONMENT_ID, invocationId: '  ' })),
    ).toThrow(ProtocolError)
  })

  it('allows a pre-handshake ERROR without environmentId', () => {
    const decoded = decodeEnvelope(envelope('ERROR', { payload: { code: 'RETRY_LATER' } }))
    expect(decoded.environmentId).toBeNull()
  })

  it('rejects unknown envelope fields', () => {
    expect(() => decodeEnvelope(envelope('READY', { environmentId: ENVIRONMENT_ID, extra: 1 }))).toThrow(
      /unknown field/,
    )
  })

  it('rejects duplicate keys (JSON.parse would silently swallow them)', () => {
    const text = `{"protocolVersion":3,"protocolVersion":3,"messageType":"HELLO","payload":{}}`
    expect(() => decodeEnvelope(text)).toThrow(/duplicate field/)
  })

  it('rejects trailing content and unknown protocol versions and message types', () => {
    expect(() => decodeEnvelope(`${envelope('HELLO')} trailing`)).toThrow(/valid JSON/)
    expect(() => decodeEnvelope(envelope('HELLO', { protocolVersion: 2 }))).toThrow(/unsupported protocolVersion/)
    expect(() => decodeEnvelope(envelope('NOPE'))).toThrow(/unknown daemon messageType/)
  })

  it('requires a non-blank canonical UUID environmentId', () => {
    expect(() => decodeEnvelope(envelope('READY', { environmentId: 'not-a-uuid' }))).toThrow(/canonical UUID/)
    const lowerHex = 'abcdef01-2222-4333-8444-555555555555'
    expect(lowerHex).toBe(lowerHex.toLowerCase())
    expect(() => decodeEnvelope(envelope('READY', { environmentId: lowerHex.toUpperCase() }))).toThrow(
      /canonical UUID/,
    )
  })

  it('rejects a payload that is not an object', () => {
    expect(() => decodeEnvelope(envelope('HELLO', { payload: [] }))).toThrow(/payload must be a JSON object/)
  })

  it('enforces the 16 MiB payload boundary', () => {
    const big = `"${'a'.repeat(17 * 1024 * 1024)}"`
    expect(() => decodeEnvelope(envelope('HELLO', { payload: { text: JSON.parse(big) } }))).toThrow(
      /exceeds .* UTF-8 bytes/,
    )
  })

  it('enforces nesting and number bounds', () => {
    let nested = '1'
    for (let index = 0; index < 120; index++) {
      nested = `[${nested}]`
    }
    expect(() => decodeEnvelope(envelope('HELLO', { payload: { nested: JSON.parse(nested) } }))).toThrow(
      /nesting depth/,
    )
    // 数字 token 长度必须在原始文本上校验：JSON.parse/stringify 会把超长数字重新格式化。
    const longNumber = `{"protocolVersion":3,"messageType":"HELLO","payload":{"n":${'1'.repeat(1200)}}}`
    expect(() => decodeEnvelope(longNumber)).toThrow(/number token/)
  })

  it('exposes utf8ByteLength with surrogate validation', () => {
    expect(utf8ByteLength('abc', 'x')).toBe(3)
    expect(utf8ByteLength('中文', 'x')).toBe(6)
    expect(() => utf8ByteLength('\ud800', 'x')).toThrow(ProtocolError)
  })
})

describe('READY capabilities codec', () => {
  const environment = {
    operatingSystem: 'linux' as const,
    architecture: 'amd64' as const,
    timeZone: 'Asia/Shanghai',
    userName: 'dev',
    homeDirectory: '/home/dev',
    note: 'Linux environment.',
    tempDirectory: '/home/dev/.kk-studio/tmp',
  }

  it('round-trips capabilities', () => {
    const text = encodeCapabilities({ version: 5, daemonVersion: '1.0.14', environment })
    const decoded = decodeCapabilities(text)
    expect(decoded.version).toBe(5)
    expect(decoded.daemonVersion).toBe('1.0.14')
    expect(decoded.environment).toEqual(environment)
  })

  it('rejects unknown fields, wrong version and invalid OS/daemonVersion', () => {
    expect(() => decodeCapabilities(JSON.stringify({ version: 5, daemonVersion: '1.0.14', environment, x: 1 }))).toThrow(
      /unexpected|unknown/,
    )
    expect(() =>
      decodeCapabilities(JSON.stringify({ version: 4, daemonVersion: '1.0.14', environment })),
    ).toThrow(/unsupported READY capabilities version/)
    expect(() =>
      decodeCapabilities(
        JSON.stringify({ version: 5, daemonVersion: '1.0.14', environment: { ...environment, operatingSystem: 'plan9' } }),
      ),
    ).toThrow(/unsupported daemon operating system/)
    expect(() =>
      decodeCapabilities(JSON.stringify({ version: 5, daemonVersion: '1.0.14', environment: { ...environment, architecture: 'ia32' } })),
    ).toThrow(/unsupported daemon architecture/)
    expect(() =>
      decodeCapabilities(JSON.stringify({ version: 5, daemonVersion: 'bad version', environment })),
    ).toThrow(ProtocolError)
  })
})

describe('capability INVOKE codec', () => {
  it('round-trips and validates capabilityVersion/timeout', () => {
    const text = encodeInvoke({
      capabilityId: 'fs.read',
      capabilityVersion: CAPABILITY_CATALOG_VERSION,
      arguments: { workdir: '/tmp' },
      timeoutMillis: 1000,
    })
    const decoded = decodeInvoke(text)
    expect(decoded.capabilityId).toBe('fs.read')
    expect(decoded.arguments).toEqual({ workdir: '/tmp' })
    expect(decoded.timeoutMillis).toBe(1000)
  })

  it('rejects unknown fields, bad capability ids and negative timeouts', () => {
    expect(() =>
      decodeInvoke(JSON.stringify({ capabilityId: 'fs.read', capabilityVersion: '3', arguments: {}, timeoutMillis: 1, extra: 1 })),
    ).toThrow(/unknown field/)
    expect(() =>
      decodeInvoke(JSON.stringify({ capabilityId: 'FS READ', capabilityVersion: '3', arguments: {}, timeoutMillis: 1 })),
    ).toThrow(/canonical syntax/)
    expect(() =>
      decodeInvoke(JSON.stringify({ capabilityId: 'fs.read', capabilityVersion: '3', arguments: [], timeoutMillis: 1 })),
    ).toThrow(/must be a JSON object/)
    expect(() =>
      decodeInvoke(JSON.stringify({ capabilityId: 'fs.read', capabilityVersion: '3', arguments: {}, timeoutMillis: -1 })),
    ).toThrow(/non-negative/)
  })

  it('round-trips expectedFile for fs.edit', () => {
    const validSha256 = 'a'.repeat(64)
    const text = encodeInvoke({
      capabilityId: 'fs.edit',
      capabilityVersion: CAPABILITY_CATALOG_VERSION,
      arguments: { path: '/tmp/test.txt', old_string: 'a', new_string: 'b' },
      timeoutMillis: 1000,
      expectedFile: { path: '/tmp/test.txt', sha256: validSha256 },
    })
    const decoded = decodeInvoke(text)
    expect(decoded.capabilityId).toBe('fs.edit')
    expect(decoded.expectedFile).toEqual({ path: '/tmp/test.txt', sha256: validSha256 })
  })

  it('rejects expectedFile on non-fs.edit, bad shapes, unknown fields and invalid sha256', () => {
    const validSha256 = 'f'.repeat(64)

    // 1. non-fs.edit capability
    expect(() =>
      decodeInvoke(JSON.stringify({
        capabilityId: 'fs.write',
        capabilityVersion: '3',
        arguments: {},
        timeoutMillis: 0,
        expectedFile: { path: '/tmp/a', sha256: validSha256 },
      })),
    ).toThrow(/expectedFile is only valid for fs\.edit/)

    // 2. null / non-object expectedFile
    expect(() =>
      decodeInvoke(JSON.stringify({
        capabilityId: 'fs.edit',
        capabilityVersion: '3',
        arguments: {},
        timeoutMillis: 0,
        expectedFile: null,
      })),
    ).toThrow(/must be a JSON object/)

    // 3. unknown fields in expectedFile
    expect(() =>
      decodeInvoke(JSON.stringify({
        capabilityId: 'fs.edit',
        capabilityVersion: '3',
        arguments: {},
        timeoutMillis: 0,
        expectedFile: { path: '/tmp/a', sha256: validSha256, extra: 'bad' },
      })),
    ).toThrow(/unknown field/)

    // 4. uppercase or non-hex sha256 syntax
    expect(() =>
      decodeInvoke(JSON.stringify({
        capabilityId: 'fs.edit',
        capabilityVersion: '3',
        arguments: {},
        timeoutMillis: 0,
        expectedFile: { path: '/tmp/a', sha256: 'A'.repeat(64) },
      })),
    ).toThrow(/lowercase SHA-256/)

    expect(() =>
      decodeInvoke(JSON.stringify({
        capabilityId: 'fs.edit',
        capabilityVersion: '3',
        arguments: {},
        timeoutMillis: 0,
        expectedFile: { path: '/tmp/a', sha256: 'g'.repeat(64) },
      })),
    ).toThrow(/lowercase SHA-256/)

    // 5. bad length sha256
    expect(() =>
      decodeInvoke(JSON.stringify({
        capabilityId: 'fs.edit',
        capabilityVersion: '3',
        arguments: {},
        timeoutMillis: 0,
        expectedFile: { path: '/tmp/a', sha256: 'abc' },
      })),
    ).toThrow(/lowercase SHA-256/)
  })
})

describe('capability result codec', () => {
  const textResult = (text: string): CapabilityResult => ({ callId: 'inv', error: false, details: {}, contents: [{ type: 'text', text }] })

  it('encodes PROGRESS with text/json only', () => {
    const text = encodeProgress(textResult('hi'))
    expect(JSON.parse(text).result.contents[0]).toEqual({ type: 'text', text: 'hi' })
    expect(() =>
      encodeProgress({
        callId: 'inv',
        error: false,
        details: {},
        contents: [{ type: 'binary', bytes: new Uint8Array([1]), mediaType: 'text/plain' }],
      }),
    ).toThrow(/must not contain binary/)
  })

  it('decodes PROGRESS and rejects resource content', () => {
    const text = encodeProgress(textResult('hi'))
    expect(decodeProgress(text, 'inv', 1024).contents[0]).toEqual({ type: 'text', text: 'hi' })
    const withResource = JSON.stringify({
      result: {
        callId: 'inv',
        error: false,
        details: {},
        contents: [
          { type: 'resource', uploadId: ENVIRONMENT_ID, mediaType: 'text/plain', name: null, size: 1, sha256: 'a'.repeat(64) },
        ],
      },
    })
    expect(() => decodeProgress(withResource, 'inv', 1024)).toThrow(/must not contain resource/)
  })

  it('preflights aggregate resource bytes before uploading anything', async () => {
    let uploads = 0
    const result: CapabilityResult = {
      callId: 'inv',
      error: false,
      details: {},
      contents: [
        { type: 'binary', bytes: new Uint8Array(4), mediaType: 'application/octet-stream' },
        { type: 'binary', bytes: new Uint8Array(4), mediaType: 'application/octet-stream' },
      ],
    }
    await expect(
      encodeCompleted(result, 6, async () => {
        uploads++
        throw new Error('must not be called')
      }),
    ).rejects.toThrow(/aggregate resource bytes exceed/)
    expect(uploads).toBe(0)
  })

  it('uploads binary content once and encodes metadata only', async () => {
    let uploads = 0
    const result: CapabilityResult = {
      callId: 'inv',
      error: false,
      details: { code: 'OK' },
      contents: [
        { type: 'text', text: 'note' },
        { type: 'binary', bytes: new Uint8Array([1, 2, 3]), mediaType: 'application/octet-stream', name: 'a.bin' },
      ],
    }
    const text = await encodeCompleted(result, 1024, async (content) => {
      uploads++
      expect(content.bytes.byteLength).toBe(3)
      return {
        type: 'resource',
        uploadId: ENVIRONMENT_ID,
        mediaType: 'application/octet-stream',
        name: 'a.bin',
        size: 3,
        sha256: 'b'.repeat(64),
      }
    })
    expect(uploads).toBe(1)
    const decoded = decodeCompleted(text, 'inv', 1024)
    expect(decoded.contents[1]).toMatchObject({ type: 'resource', uploadId: ENVIRONMENT_ID, size: 3 })
    expect(text).not.toContain('AQID')
  })

  it('rejects resources whose aggregate exceeds the budget on decode', () => {
    const payload = JSON.stringify({
      result: {
        callId: 'inv',
        error: false,
        details: {},
        contents: [
          { type: 'resource', uploadId: ENVIRONMENT_ID, mediaType: 'text/plain', name: null, size: 10, sha256: 'a'.repeat(64) },
        ],
      },
    })
    expect(() => decodeCompleted(payload, 'inv', 5)).toThrow(/aggregate declared resource bytes/)
  })
})

describe('resource transfer codec', () => {
  const transferId = '22222222-3333-4444-8555-666666666666'

  it('round-trips request/commit/ticket', () => {
    const request = encodeUploadRequest({
      transferId,
      mediaType: 'text/plain',
      name: null,
      size: 3,
      sha256: 'a'.repeat(64),
    })
    expect(decodeUploadRequest(request)).toEqual({ transferId, mediaType: 'text/plain', name: null, size: 3, sha256: 'a'.repeat(64) })

    const commit = encodeUploadCommit({ transferId, uploadId: ENVIRONMENT_ID })
    expect(decodeUploadCommit(commit)).toEqual({ transferId, uploadId: ENVIRONMENT_ID })

    const pending = encodeUploadTicket({
      transferId,
      state: 'PENDING',
      uploadId: ENVIRONMENT_ID,
      presignedPut: { method: 'PUT', url: 'https://s3.example.com/x', headers: { 'x-amz-acl': 'private' }, expiresAt: '2030-01-01T00:00:00.000Z' },
      message: null,
    })
    const decoded = decodeUploadTicket(pending)
    expect(decoded.state).toBe('PENDING')
    expect(decoded.presignedPut?.method).toBe('PUT')
  })

  it('rejects invalid ticket shapes and secrets-free metadata', () => {
    expect(() =>
      decodeUploadTicket(JSON.stringify({ transferId, state: 'PENDING', uploadId: ENVIRONMENT_ID })),
    ).toThrow(/presignedPut/)
    expect(() =>
      decodeUploadTicket(
        JSON.stringify({ transferId, state: 'FAILED', message: 'x', uploadId: ENVIRONMENT_ID }),
      ),
    ).toThrow(ProtocolError)
    expect(() =>
      decodeUploadTicket(
        JSON.stringify({
          transferId,
          state: 'PENDING',
          uploadId: ENVIRONMENT_ID,
          presignedPut: { method: 'GET', url: 'u', headers: {}, expiresAt: '2030-01-01T00:00:00.000Z' },
        }),
      ),
    ).toThrow(/must be PUT/)
    expect(() =>
      decodeUploadTicket(
        JSON.stringify({
          transferId,
          state: 'PENDING',
          uploadId: ENVIRONMENT_ID,
          presignedPut: { method: 'PUT', url: 'u', headers: {}, expiresAt: '2030-01-01' },
        }),
      ),
    ).toThrow(/ISO-8601/)
  })

  it('rejects unknown and duplicate fields in control payloads', () => {
    expect(() => decodeUploadRequest(JSON.stringify({ transferId, mediaType: 'text/plain', name: null, size: 1, sha256: 'a'.repeat(64), x: 1 }))).toThrow(
      /unknown field/,
    )
    // 重复键必须在原始文本上构造：JS 对象字面量会自行去重。
    const duplicate = `{"transferId":${JSON.stringify(transferId)},"mediaType":"text/plain","transferId":${JSON.stringify(transferId)},"name":null,"size":1,"sha256":"${'a'.repeat(64)}"}`
    expect(() => decodeUploadRequest(duplicate)).toThrow(/duplicate field/)
  })
})

describe('managed update codec', () => {
  const operationId = '33333333-4444-4555-8666-777777777777'
  const targetVersion = artifactFixture.targetVersion
  const artifactUrl = artifactFixture.accepted[0]!.artifactUrl

  it('round-trips the official platform archive command and result', () => {
    const command = encodeUpdateCommand({ operationId, targetVersion, artifactUrl, artifactSha256: 'A'.repeat(64) })
    expect(decodeUpdateCommand(command)).toEqual({ operationId, targetVersion, artifactUrl, artifactSha256: 'A'.repeat(64) })
    const result = encodeUpdateResult({ operationId, phase: 'PREPARED', message: null })
    expect(decodeUpdateResult(result)).toEqual({ operationId, phase: 'PREPARED', message: null })
  })

  // 真实入口必须接受共享 fixture 的全部五平台官方归档，并拒绝其中每一个错配形态（旧 jar、错 tag/版本、错平台、任意 URL）。
  it.each(artifactFixture.accepted)('accepts the official $name update command', (entry) => {
    expect(
      decodeUpdateCommand(
        JSON.stringify({ operationId, targetVersion, artifactUrl: entry.artifactUrl, artifactSha256: 'a'.repeat(64) }),
      ).artifactUrl,
    ).toBe(entry.artifactUrl)
  })

  it.each(artifactFixture.rejected)('rejects $name', (entry) => {
    expect(() =>
      decodeUpdateCommand(
        JSON.stringify({ operationId, targetVersion, artifactUrl: entry.artifactUrl, artifactSha256: 'a'.repeat(64) }),
      ),
    ).toThrow(/artifactUrl/)
  })

  it('rejects an artifact whose release tag disagrees with targetVersion', () => {
    const other = artifactFixture.accepted[0]!.artifactUrl.replace(`v${targetVersion}/`, 'v1.1.0/')
    expect(() =>
      decodeUpdateCommand(
        JSON.stringify({ operationId, targetVersion, artifactUrl: other, artifactSha256: 'a'.repeat(64) }),
      ),
    ).toThrow(/official platform release archive/)
  })

  it('rejects arbitrary artifact urls and unknown fields', () => {
    expect(() =>
      decodeUpdateCommand(JSON.stringify({ operationId, targetVersion, artifactUrl: 'https://evil.example.com/x.tar.gz', artifactSha256: 'a'.repeat(64) })),
    ).toThrow(/official platform release archive/)
    expect(() =>
      decodeUpdateResult(JSON.stringify({ operationId, phase: 'ACCEPTED', message: null, x: 1 })),
    ).toThrow(/unknown field/)
    expect(() => decodeUpdateResult(JSON.stringify({ operationId, phase: 'NOPE' }))).toThrow(/unknown update phase/)
  })
})

describe('IDE metadata codec', () => {
  it('round-trips an OPEN_VIEW command', () => {
    const text = encodeIdeCommand({ action: 'OPEN_VIEW', viewId: ENVIRONMENT_ID, directory: '/tmp' })
    const decoded = decodeIdeCommand(text)
    expect(decoded.action).toBe('OPEN_VIEW')
    expect(decoded.directory).toBe('/tmp')
  })

  it('rejects unknown actions and malformed headers', () => {
    expect(() => decodeIdeCommand(JSON.stringify({ action: 'NOPE' }))).toThrow(/unknown/)
    expect(() =>
      decodeIdeCommand(JSON.stringify({ action: 'OPEN_STREAM', headers: [['x']] })),
    ).toThrow(/\[name, value\]/)
    expect(() => decodeIdeCommand(JSON.stringify({ action: 'OPEN_STREAM', mode: 'TCP' }))).toThrow(/mode/)
  })

  it('round-trips an IDE reply', () => {
    const text = encodeIdeReply({ ok: true, viewId: ENVIRONMENT_ID, workspacePath: '/w' })
    expect(decodeIdeReply(text)).toEqual({ ok: true, viewId: ENVIRONMENT_ID, workspacePath: '/w' })
    expect(() => decodeIdeReply(JSON.stringify({ ok: 'yes' }))).toThrow(/boolean/)
  })
})

describe('error payload helper', () => {
  it('builds a bounded message payload', () => {
    expect(errorPayload('boom')).toEqual({ message: 'boom' })
  })
})
