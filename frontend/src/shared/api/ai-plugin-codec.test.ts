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
    ).toThrowError(/Unknown property "unknownEvilField"/)

    expect(() =>
      decodePluginAuthPrepareDTO({
        authAttemptId: 'attempt-1',
        loginUrl: 'https://example.com',
        extraSecret: '12345',
      }),
    ).toThrowError(/Unknown property "extraSecret"/)
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
    ).toThrowError(/must contain static key field "missingField"/)

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
})
