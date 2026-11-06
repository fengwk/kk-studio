import { describe, expect, it } from 'vitest'
import {
  pluginConnectionCheckExpected,
  pluginConnectionCheckIsStale,
  type PluginConnectionCheckRun,
} from '@/features/extensions/plugin-connection-check'
import type { PluginConnectionCheckDTO, PluginDTO } from '@/shared/api/generated/contracts'

function plugin(overrides: Partial<PluginDTO> = {}): PluginDTO {
  return {
    pluginId: 'opencli',
    name: 'OpenCLI',
    pluginVersion: '1.0.0',
    authKind: null,
    status: null,
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      version: '8',
      valuesJson: '{}',
      valuesSchema: { type: 'object', properties: {}, required: [], additionalProperties: false },
      defaults: {},
      secrets: {},
    },
    appliedVersion: '8',
    connectionCheckSupported: true,
    harnessToolNames: [],
    canvasFunctionNames: [],
    applicationError: null,
    ...overrides,
  }
}

function result(overrides: Partial<PluginConnectionCheckDTO> = {}): PluginConnectionCheckDTO {
  return {
    pluginId: 'opencli',
    appliedVersion: '8',
    checkedAt: 1772366400000,
    reachability: 'REACHABLE',
    authentication: 'ACCEPTED',
    browserSession: 'UNKNOWN',
    ...overrides,
  }
}

function doneRun(overrides: Partial<PluginConnectionCheckRun> = {}): PluginConnectionCheckRun {
  return {
    pluginId: 'opencli',
    expectedVersion: '8',
    phase: 'done',
    result: result(),
    errorMessage: null,
    ...overrides,
  }
}

describe('pluginConnectionCheckExpected', () => {
  // 只提交用户当前所见的已应用坐标；能力必须来自实例 metadata。
  it('returns the applied coordinates only when the instance declares the capability and is applied', () => {
    expect(pluginConnectionCheckExpected(plugin())).toEqual({
      expectedVersion: '8',
    })
  })

  it('refuses the entry when the instance does not declare the capability', () => {
    expect(pluginConnectionCheckExpected(plugin({ connectionCheckSupported: false }))).toBeNull()
  })

  it('never submits null coordinates for a plugin that is not applied', () => {
    expect(pluginConnectionCheckExpected(plugin({ appliedVersion: null }))).toBeNull()
  })

  it('never submits a zero applied version', () => {
    // 已应用版本恒为正：0 表示本节点还没有已应用配置，不能作为检查坐标。
    expect(
      pluginConnectionCheckExpected(
        plugin({
          appliedVersion: '0',
          configuration: { ...plugin().configuration, version: '0' },
        }),
      ),
    ).toBeNull()
  })

  it('refuses the entry while the local applied coordinates are behind the configuration version', () => {
    expect(
      pluginConnectionCheckExpected(
        plugin({ configuration: { ...plugin().configuration, version: '9' } }),
      ),
    ).toBeNull()
  })
})

describe('pluginConnectionCheckIsStale', () => {
  it('keeps a result current only while it matches the coordinates the caller currently sees', () => {
    expect(pluginConnectionCheckIsStale(doneRun(), pluginConnectionCheckExpected(plugin()))).toBe(false)
  })

  it('marks a late result stale once the applied coordinates changed', () => {
    const versionChanged = pluginConnectionCheckExpected(
      plugin({
        appliedVersion: '9',
        configuration: { ...plugin().configuration, version: '9' },
      }),
    )
    expect(pluginConnectionCheckIsStale(doneRun(), versionChanged)).toBe(true)
  })

  it('marks a result stale when the plugin is no longer checkable or has disappeared', () => {
    expect(pluginConnectionCheckIsStale(doneRun(), null)).toBe(true)
    // 列表换版后本节点又变成待应用：结果不再对应当前配置。
    const pendingAgain = pluginConnectionCheckExpected(
      plugin({ configuration: { ...plugin().configuration, version: '9' } }),
    )
    expect(pendingAgain).toBeNull()
    expect(pluginConnectionCheckIsStale(doneRun(), pendingAgain)).toBe(true)
  })

  it('treats only completed runs as results', () => {
    for (const phase of ['pending', 'mismatch', 'conflict', 'failed'] as const) {
      expect(pluginConnectionCheckIsStale(doneRun({ phase, result: null }), null)).toBe(false)
    }
  })
})
