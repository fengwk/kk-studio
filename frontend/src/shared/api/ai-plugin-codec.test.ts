import { describe, expect, it } from 'vitest'
import {
  decodePluginAuthPrepareDTO,
  decodePluginDTO,
  decodePluginList,
} from './ai-plugin-codec'

describe('ai-plugin-codec', () => {
  const sampleDeepLinkDTO = {
    pluginId: 'mavis',
    name: 'MiniMax Mavis',
    version: '1.0.0',
    authKind: {
      type: 'DEEP_LINK',
      regionCandidates: ['CN', 'EN'],
    },
    status: 'CONNECTED',
    region: 'CN',
    expiresAt: '2026-10-01T12:00:00Z',
    nextRefreshAt: '2026-09-25T12:00:00Z',
    lastRefreshedAt: '2026-09-01T12:00:00Z',
    lastRefreshError: null,
    configuration: {
      revision: '5',
      valuesJson: '{"enabled":true}',
      valuesSchema:
        '{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":[],"additionalProperties":false}',
      defaultsJson: '{"enabled":true}',
      secrets: {
        apiKey: {
          description: 'Mavis key',
          configured: true,
        },
      },
    },
    desiredEpoch: '10',
    appliedEpoch: '10',
    desiredRevision: '5',
    appliedRevision: '5',
    connectionCheckSupported: true,
    harnessToolNames: ['search'],
    canvasFunctionNames: ['draw'],
    applicationError: null,
  }

  const sampleStaticKeyDTO = {
    pluginId: 'custom-static',
    name: 'Custom Static',
    version: '2.0.0',
    authKind: {
      type: 'STATIC_KEY',
      secretField: 'myToken',
    },
    status: 'CONNECTED',
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      revision: '1',
      valuesJson: '{}',
      valuesSchema:
        '{"type":"object","properties":{},"required":[],"additionalProperties":false}',
      defaultsJson: '{}',
      secrets: {
        myToken: {
          description: 'Static Token',
          configured: true,
        },
      },
    },
    desiredEpoch: '1',
    appliedEpoch: '1',
    desiredRevision: '1',
    appliedRevision: '1',
    connectionCheckSupported: false,
    harnessToolNames: [],
    canvasFunctionNames: [],
    applicationError: null,
  }

  const sampleNoAuthDTO = {
    pluginId: 'no-auth',
    name: 'No Auth Plugin',
    version: '1.0.0',
    authKind: null,
    status: null,
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      revision: '1',
      valuesJson: '{}',
      valuesSchema:
        '{"type":"object","properties":{},"required":[],"additionalProperties":false}',
      defaultsJson: '{}',
      secrets: {},
    },
    desiredEpoch: '0',
    appliedEpoch: '0',
    desiredRevision: '1',
    appliedRevision: '1',
    connectionCheckSupported: false,
    harnessToolNames: [],
    canvasFunctionNames: [],
    applicationError: null,
  }

  it('decodes complete PluginDTO accurately for DEEP_LINK, STATIC_KEY, and None', () => {
    const decodedDeep = decodePluginDTO(sampleDeepLinkDTO)
    expect(decodedDeep.pluginId).toBe('mavis')
    expect(decodedDeep.authKind?.type).toBe('DEEP_LINK')
    expect(decodedDeep.configuration.secrets.apiKey.configured).toBe(true)

    const decodedStatic = decodePluginDTO(sampleStaticKeyDTO)
    expect(decodedStatic.pluginId).toBe('custom-static')
    expect(decodedStatic.authKind?.type).toBe('STATIC_KEY')
    expect(decodedDeep.connectionCheckSupported).toBe(true)
    expect(decodedStatic.connectionCheckSupported).toBe(false)

    const decodedNone = decodePluginDTO(sampleNoAuthDTO)
    expect(decodedNone.authKind).toBeNull()
    expect(decodedNone.status).toBeNull()
  })

  it('rejects unknown properties in DTO payloads without leaking sensitive values', () => {
    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        unknownEvilField: 'should-reject',
      }),
    ).toThrowError(/Unknown property at plugin/)

    expect(() =>
      decodePluginAuthPrepareDTO({
        authAttemptId: 'attempt-1',
        loginUrl: 'https://example.com',
        extraSecret: '12345',
      }),
    ).toThrowError(/Unknown property at authPrepare/)
  })

  it('rejects numbers exceeding 64-bit signed Long range', () => {
    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        desiredEpoch: '9223372036854775808', // Long.MAX_VALUE + 1
      }),
    ).toThrowError(/exceeds 64-bit signed integer range/)
  })

  it('strictly validates closed semantics for None, STATIC_KEY and DEEP_LINK', () => {
    // None cannot have status or token metadata
    expect(() =>
      decodePluginDTO({
        ...sampleNoAuthDTO,
        status: 'CONNECTED',
      }),
    ).toThrowError(/status must be null when authKind is null/)

    expect(() =>
      decodePluginDTO({
        ...sampleNoAuthDTO,
        region: 'CN',
      }),
    ).toThrowError(/token\/refresh metadata must be null when authKind is null/)

    // StaticKey cannot have region or expiry
    expect(() =>
      decodePluginDTO({
        ...sampleStaticKeyDTO,
        region: 'CN',
      }),
    ).toThrowError(/token\/refresh metadata must be null for STATIC_KEY/)

    // StaticKey secretField must exist in configuration.secrets
    expect(() =>
      decodePluginDTO({
        ...sampleStaticKeyDTO,
        authKind: { type: 'STATIC_KEY', secretField: 'missingField' },
      }),
    ).toThrowError(/must contain the declared static key field/)

    // DeepLink region must be in regionCandidates
    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        region: 'INVALID_REGION',
      }),
    ).toThrowError(/region must be one of regionCandidates/)
  })

  it('decodes PluginList and AuthPrepare correctly', () => {
    const list = decodePluginList([sampleDeepLinkDTO, sampleStaticKeyDTO])
    expect(list.length).toBe(2)

    const prepare = decodePluginAuthPrepareDTO({
      authAttemptId: 'nonce-123',
      loginUrl: 'https://login.example.com',
    })
    expect(prepare.authAttemptId).toBe('nonce-123')
  })

  it('accepts HTTP epoch seconds, preserves static rejection errors, and rejects invalid time values', () => {
    const decoded = decodePluginDTO({
      ...sampleDeepLinkDTO,
      expiresAt: 1777574730,
      nextRefreshAt: 1777571130.125,
      lastRefreshedAt: 0,
    })
    expect(decoded.expiresAt).toBe(1777574730)
    expect(decoded.nextRefreshAt).toBe(1777571130.125)
    expect(decoded.lastRefreshedAt).toBe(0)
    expect(decodePluginDTO({
      ...sampleStaticKeyDTO,
      status: 'REAUTH_REQUIRED',
      lastRefreshError: 'provider rejected the stored static key',
    }).lastRefreshError).toBe('provider rejected the stored static key')
    for (const expiresAt of [true, {}, Infinity, NaN, 'not-a-time']) {
      expect(() => decodePluginDTO({ ...sampleDeepLinkDTO, expiresAt }))
        .toThrowError(/epoch seconds or an ISO date-time string/)
    }
  })

  it('requires own static secret fields and does not echo unknown provider keys', () => {
    expect(() => decodePluginDTO({
      ...sampleStaticKeyDTO,
      authKind: { type: 'STATIC_KEY', secretField: 'constructor' },
      configuration: { ...sampleStaticKeyDTO.configuration, secrets: {} },
    })).toThrowError(/must contain the declared static key field/)
    const marker = 'remote-secret-marker-9876'
    let thrown: unknown
    try {
      decodePluginDTO({ ...sampleStaticKeyDTO, [marker]: true })
    } catch (error) {
      thrown = error
    }
    expect(thrown).toBeInstanceOf(Error)
    expect(String(thrown)).not.toContain(marker)
  })

})
