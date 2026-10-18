import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { findNodeAtLocation } from 'jsonc-parser'
import { describe, expect, it } from 'vitest'
import {
  decodePluginAuthPrepareDTO,
  decodePluginConnectionCheckDTO,
  decodePluginList,
} from '@/shared/api/ai-plugin-codec'
import { getNodeSlice, parseStrictJsonObject } from './plugin-config-json'
import { parsePluginValuesSchema } from './plugin-schema'

function httpGoldenData(filename: string): unknown {
  const source = readFileSync(resolve(
    dirname(fileURLToPath(import.meta.url)),
    '../../../../../web/src/test/resources/fun/fengwk/kkstudio/web/plugin',
    filename,
  ), 'utf8')
  const envelope: unknown = JSON.parse(source)
  if (envelope === null || typeof envelope !== 'object' || !('data' in envelope)) {
    throw new Error('HTTP wire golden must contain a data envelope')
  }
  return envelope.data
}

describe('plugin HTTP contract', () => {
  it('decodes the exact Boot HTTP golden shared with the Java MVC contract tests', () => {
    const plugins = decodePluginList(httpGoldenData('plugins-http.json'))
    expect(plugins).toHaveLength(5)
    expect(plugins[0].authKind).toBeNull()
    expect(plugins[0].status).toBeNull()
    expect(plugins[1].authKind?.type).toBe('STATIC_KEY')
    expect(plugins[2].status).toBe('REAUTH_REQUIRED')
    expect(plugins[2].lastRefreshError).toBe('provider rejected the stored static key')
    expect(plugins[3].desiredRevision).toBe('2')
    expect(plugins[3].appliedRevision).toBe('1')
    expect(plugins[3].applicationError).not.toBeNull()
    expect(plugins[4].expiresAt).toBe(1777574730)
    expect(plugins[0].connectionCheckSupported).toBe(false)
    expect(plugins[4].connectionCheckSupported).toBe(true)
    const raw = plugins[4].configuration.valuesJson
    const parsed = parseStrictJsonObject(raw)
    expect(parsed.ok).toBe(true)
    if (parsed.ok) {
      expect(getNodeSlice(raw, findNodeAtLocation(parsed.tree, ['limits', 'maxTokens'])!))
        .toBe('9007199254740993')
      expect(getNodeSlice(raw, findNodeAtLocation(parsed.tree, ['limits', 'temperature'])!))
        .toBe('1.230000')
      expect(getNodeSlice(raw, findNodeAtLocation(parsed.tree, ['weight'])!)).toBe('1E+1000')
    }
    for (const plugin of plugins) {
      const schema = parsePluginValuesSchema(plugin.configuration.valuesSchema)
      expect(schema.ok).toBe(true)
      if (schema.ok) {
        expect(Object.hasOwn(schema.schema.properties, '__proto__')).toBe(true)
        expect(schema.schema.properties['constructor'].kind).toBe('boolean')
      }
    }
    expect(decodePluginAuthPrepareDTO(httpGoldenData('auth-prepare-http.json'))).toEqual({
      authAttemptId: 'attempt-handle-c0ffee-cafe',
      loginUrl: 'https://auth.example.com/deep-link?region=us-east-1',
    })
  })

  // 真实后端 golden 的已应用坐标、检查时刻与三个维度必须逐值解码，且绝不携带远端材料。
  it('decodes the committed connection-check HTTP golden shared with the Java MVC contract test', () => {
    const raw = httpGoldenData('connection-check-http.json')
    expect(decodePluginConnectionCheckDTO(raw)).toEqual({
      pluginId: 'beta-deep-connected',
      appliedRevision: '8',
      checkedAt: 1772366400,
      reachability: 'REACHABLE',
      authentication: 'ACCEPTED',
      browserSession: 'UNKNOWN',
    })
    const serialized = JSON.stringify(raw)
    for (const forbidden of ['http://', 'https://', 'Bearer', 'credential', 'apiKey']) {
      expect(serialized).not.toContain(forbidden)
    }
  })
})
