import { describe, expect, it } from 'vitest'
import {
  decodePluginAuthKindDTO,
  decodePluginAuthPrepareDTO,
  decodePluginConfigurationDTO,
  decodePluginDTO,
  decodePluginList,
} from './ai-plugin-codec'
import { ApiError } from '@/shared/api/client'

describe('ai-plugin-codec', () => {
  const sampleDeepLinkDTO = {
    pluginId: 'minimax-mavis',
    name: 'MiniMax Mavis',
    version: '1.0.0',
    authKind: {
      type: 'DEEP_LINK',
      regionCandidates: ['CN', 'US'],
    },
    status: 'CONNECTED',
    region: 'CN',
    expiresAt: '2026-10-01T00:00:00Z',
    nextRefreshAt: '2026-09-25T00:00:00Z',
    lastRefreshedAt: '2026-09-20T00:00:00Z',
    lastRefreshError: null,
    configuration: {
      revision: '7',
      valuesJson: '{"enabled":true}',
      valuesSchema: '{"type":"object","properties":{"enabled":{"type":"boolean"}}}',
      defaultsJson: '{"enabled":true}',
      secrets: {
        apiKey: {
          description: 'MiniMax API Key',
          configured: true,
        },
      },
    },
    desiredEpoch: '4',
    appliedEpoch: '4',
    desiredRevision: '7',
    appliedRevision: '7',
    harnessToolNames: ['search', 'image'],
    canvasFunctionNames: ['draw'],
    applicationError: null,
  }

  const sampleStaticKeyDTO = {
    pluginId: 'static-plugin',
    name: 'Static Key Plugin',
    version: '2.1.0',
    authKind: {
      type: 'STATIC_KEY',
      secretField: 'secretToken',
    },
    status: 'NOT_CONNECTED',
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      revision: '0',
      valuesJson: '{}',
      valuesSchema: '{"type":"object","properties":{}}',
      defaultsJson: '{}',
      secrets: {
        secretToken: {
          description: 'Access Secret',
          configured: false,
        },
      },
    },
    desiredEpoch: '1',
    appliedEpoch: null,
    desiredRevision: '0',
    appliedRevision: null,
    harnessToolNames: [],
    canvasFunctionNames: [],
    applicationError: 'configuration is saved but this node has not applied it yet',
  }

  const sampleNoAuthDTO = {
    pluginId: 'no-auth-plugin',
    name: 'No Auth Plugin',
    version: '0.9.0',
    authKind: null,
    status: null,
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      revision: null,
      valuesJson: '{"timeout":30}',
      valuesSchema: '{"type":"object","properties":{"timeout":{"type":"integer"}}}',
      defaultsJson: '{"timeout":30}',
      secrets: {},
    },
    desiredEpoch: '0',
    appliedEpoch: '0',
    desiredRevision: '0',
    appliedRevision: '0',
    harnessToolNames: ['noop'],
    canvasFunctionNames: [],
    applicationError: null,
  }

  it('decodes deep link plugin successfully', () => {
    const decoded = decodePluginDTO(sampleDeepLinkDTO)
    expect(decoded.pluginId).toBe('minimax-mavis')
    expect(decoded.authKind?.type).toBe('DEEP_LINK')
    if (decoded.authKind?.type === 'DEEP_LINK') {
      expect(decoded.authKind.regionCandidates).toEqual(['CN', 'US'])
    }
    expect(decoded.status).toBe('CONNECTED')
    expect(decoded.desiredEpoch).toBe('4')
    expect(decoded.configuration.secrets.apiKey.configured).toBe(true)
    expect(decoded.harnessToolNames).toEqual(['search', 'image'])
  })

  it('decodes static key plugin successfully with pending application error', () => {
    const decoded = decodePluginDTO(sampleStaticKeyDTO)
    expect(decoded.pluginId).toBe('static-plugin')
    expect(decoded.authKind?.type).toBe('STATIC_KEY')
    if (decoded.authKind?.type === 'STATIC_KEY') {
      expect(decoded.authKind.secretField).toBe('secretToken')
    }
    expect(decoded.appliedEpoch).toBeNull()
    expect(decoded.applicationError).toContain('saved but this node has not applied')
  })

  it('decodes plugin with no auth capability successfully', () => {
    const decoded = decodePluginDTO(sampleNoAuthDTO)
    expect(decoded.authKind).toBeNull()
    expect(decoded.status).toBeNull()
    expect(decoded.configuration.revision).toBeNull()
    expect(decoded.desiredEpoch).toBe('0')
  })

  it('decodes plugin list', () => {
    const list = decodePluginList([sampleDeepLinkDTO, sampleStaticKeyDTO, sampleNoAuthDTO])
    expect(list).toHaveLength(3)
    expect(list[0].pluginId).toBe('minimax-mavis')
    expect(list[1].pluginId).toBe('static-plugin')
    expect(list[2].pluginId).toBe('no-auth-plugin')
  })

  it('decodes auth prepare response', () => {
    const prepare = decodePluginAuthPrepareDTO({
      authAttemptId: 'attempt-xyz-123',
      loginUrl: 'https://example.com/oauth/login',
    })
    expect(prepare.authAttemptId).toBe('attempt-xyz-123')
    expect(prepare.loginUrl).toBe('https://example.com/oauth/login')
  })

  it('rejects invalid canonical decimal strings', () => {
    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        desiredEpoch: '-1',
      }),
    ).toThrow(ApiError)

    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        desiredEpoch: '01',
      }),
    ).toThrow(ApiError)

    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        desiredEpoch: 4, // numbers are not allowed on wire coordinate strings
      }),
    ).toThrow(ApiError)
  })

  it('rejects invalid authKind unions and missing fields', () => {
    expect(() =>
      decodePluginAuthKindDTO({
        type: 'OAUTH2',
      }),
    ).toThrow(ApiError)

    expect(() =>
      decodePluginAuthKindDTO({
        type: 'DEEP_LINK',
        regionCandidates: [], // empty candidates forbidden
      }),
    ).toThrow(ApiError)

    expect(() =>
      decodePluginAuthKindDTO({
        type: 'STATIC_KEY',
        secretField: '',
      }),
    ).toThrow(ApiError)
  })

  it('rejects malformed configuration structure', () => {
    expect(() =>
      decodePluginConfigurationDTO({
        revision: '1',
        valuesJson: 'not a full object',
        valuesSchema: '{}',
        defaultsJson: '{}',
        secrets: 'invalid', // should be object
      }),
    ).toThrow(ApiError)
  })

  it('rejects null status when authKind is present', () => {
    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        status: null,
      }),
    ).toThrow(ApiError)
  })

  it('rejects invalid status string', () => {
    expect(() =>
      decodePluginDTO({
        ...sampleDeepLinkDTO,
        status: 'UNKNOWN_STATUS',
      }),
    ).toThrow(ApiError)
  })
})
