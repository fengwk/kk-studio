import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import type { PluginDTO } from '@/shared/api/generated/contracts'
import { parsePluginValuesSchema } from './plugin-schema'

function httpGoldenData(filename: string): unknown {
  const source = readFileSync(resolve(
    dirname(fileURLToPath(import.meta.url)),
    '../../../../web/src/test/resources/fun/fengwk/kkstudio/web/plugin',
    filename,
  ), 'utf8')
  return JSON.parse(source) as unknown
}

describe('plugin HTTP contract', () => {
  it('decodes the exact Boot HTTP golden shared with the Java MVC contract tests', () => {
    const plugins = httpGoldenData('plugins-http.json') as PluginDTO[]
    expect(plugins).toHaveLength(5)
    expect(plugins[0].authKind ?? null).toBeNull()
    expect(plugins[0].status ?? null).toBeNull()
    expect(plugins[1].authKind?.type).toBe('STATIC_KEY')
    expect(plugins[2].status).toBe('REAUTH_REQUIRED')
    expect(plugins[2].lastRefreshError).toBe('provider rejected the stored static key')
    expect(plugins[3].desiredVersion).toBe('2')
    expect(plugins[3].appliedVersion).toBe('1')
    expect(plugins[3].applicationError).not.toBeNull()
    expect(typeof plugins[4].expiresAt).toBe('number')
    expect(plugins[0].connectionCheckSupported).toBe(false)
    expect(plugins[4].connectionCheckSupported).toBe(true)
    const values = plugins[4].configuration.values as {
      limits: { maxTokens: number; temperature: number }
      mode: string
      retries: number[]
      weight: number
      __proto__: string
      constructor: boolean
    }
    expect(values.limits.maxTokens).toBe(9007199254740992)
    expect(values.limits.temperature).toBe(1.23)
    expect(values.weight).toBe(Infinity)
    expect(values.__proto__).toBe('own-key-probe')
    expect(values.constructor).toBe(false)
    expect(values.mode).toBe('BALANCED')
    expect(values.retries).toEqual([1, 2, 3])
    for (const plugin of plugins) {
      const schema = parsePluginValuesSchema(plugin.configuration.valuesSchema)
      expect(schema.ok).toBe(true)
      if (schema.ok) {
        expect(Object.hasOwn(schema.schema.properties, '__proto__')).toBe(true)
        expect(schema.schema.properties['constructor'].kind).toBe('boolean')
      }
    }
    expect(httpGoldenData('auth-prepare-http.json')).toEqual({
      authAttemptId: 'attempt-handle-c0ffee-cafe',
      loginUrl: 'https://auth.example.com/deep-link?region=us-east-1',
    })
  })

  // 真实后端 golden 的已应用坐标与三个维度必须逐值解码，检查时刻是整数毫秒，且绝不携带远端材料。
  it('decodes the committed connection-check HTTP golden shared with the Java MVC contract test', () => {
    const raw = httpGoldenData('connection-check-http.json') as {
      checkedAt: number
    } & Record<string, unknown>
    expect(raw).toMatchObject({
      pluginId: 'beta-deep-connected',
      appliedVersion: '8',
      reachability: 'REACHABLE',
      authentication: 'ACCEPTED',
      browserSession: 'UNKNOWN',
    })
    expect(Number.isInteger(raw.checkedAt)).toBe(true)
    const serialized = JSON.stringify(raw)
    for (const forbidden of ['http://', 'https://', 'Bearer', 'credential', 'apiKey']) {
      expect(serialized).not.toContain(forbidden)
    }
  })
})
