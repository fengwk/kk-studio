import { describe, expect, it } from 'vitest'
import { decodePluginConnectionCheckDTO } from '@/shared/api/ai-plugin-codec'
import {
  CONFIG_SYNC_KIND_VALUES,
  PLUGIN_CONNECTION_AUTHENTICATION_VALUES,
  PLUGIN_CONNECTION_BROWSER_SESSION_VALUES,
  PLUGIN_CONNECTION_REACHABILITY_VALUES,
} from '@/shared/api/generated/stable-contracts'

/** 合法连接检查 payload 基线；只覆写待验证维度。 */
const CONNECTION_CHECK = {
  pluginId: 'beta-deep-connected',
  appliedEpoch: '14',
  appliedRevision: '8',
  checkedAt: 1772366400,
  reachability: 'REACHABLE',
  authentication: 'ACCEPTED',
  browserSession: 'UNKNOWN',
} as const

describe('generated stable contracts', () => {
  // ConfigSyncKind 的 wire 值必须是小驼峰集合名（@JsonValue），绝不是 Java 枚举 name()。
  it('pins ConfigSyncKind to the small-camel wire values', () => {
    expect(CONFIG_SYNC_KIND_VALUES).toEqual([
      'providers',
      'models',
      'agents',
      'skillPackages',
      'environments',
      'mcpServers',
      'settings',
      'plugins',
    ])
  })

  it('generates non-empty, duplicate-free enum wire arrays', () => {
    for (const values of [
      PLUGIN_CONNECTION_REACHABILITY_VALUES,
      PLUGIN_CONNECTION_AUTHENTICATION_VALUES,
      PLUGIN_CONNECTION_BROWSER_SESSION_VALUES,
    ]) {
      expect(values.length).toBeGreaterThan(0)
      expect(new Set(values).size).toBe(values.length)
    }
  })

  // 严格解码器直接复用生成的 enum 数组：每个生成值都被接受，且没有手抄的第二份字面量。
  it('accepts exactly the generated connection dimensions', () => {
    for (const reachability of PLUGIN_CONNECTION_REACHABILITY_VALUES) {
      expect(decodePluginConnectionCheckDTO({ ...CONNECTION_CHECK, reachability }).reachability)
        .toBe(reachability)
    }
    for (const authentication of PLUGIN_CONNECTION_AUTHENTICATION_VALUES) {
      expect(decodePluginConnectionCheckDTO({ ...CONNECTION_CHECK, authentication }).authentication)
        .toBe(authentication)
    }
    for (const browserSession of PLUGIN_CONNECTION_BROWSER_SESSION_VALUES) {
      expect(decodePluginConnectionCheckDTO({ ...CONNECTION_CHECK, browserSession }).browserSession)
        .toBe(browserSession)
    }
  })

  it('rejects connection dimensions outside the generated contract', () => {
    expect(() =>
      decodePluginConnectionCheckDTO({ ...CONNECTION_CHECK, reachability: 'HEALTHY' }),
    ).toThrowError(/must be one of/)
    expect(() =>
      decodePluginConnectionCheckDTO({ ...CONNECTION_CHECK, authentication: 'LOGGED_IN' }),
    ).toThrowError(/must be one of/)
    expect(() =>
      decodePluginConnectionCheckDTO({ ...CONNECTION_CHECK, browserSession: 'SIGNED_IN' }),
    ).toThrowError(/must be one of/)
  })
})
