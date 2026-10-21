import { describe, expect, it } from 'vitest'
import type { PluginConnectionCheckResult, PluginDTO } from '@/shared/api/contracts/ai-plugin'
import {
  pluginConnectionCheckExpected,
  pluginConnectionCheckIsStale,
  type PluginConnectionCheckRun,
} from '@/features/extensions/plugin-connection-check'

function plugin(overrides: Partial<PluginDTO> = {}): PluginDTO {
  return {
    pluginId: 'opencli',
    name: 'OpenCLI',
    version: '1.0.0',
    authKind: null,
    status: null,
    region: null,
    expiresAt: null,
    nextRefreshAt: null,
    lastRefreshedAt: null,
    lastRefreshError: null,
    configuration: {
      revision: '8',
      valuesJson: '{}',
      valuesSchema: '{"type":"object","properties":{}}',
      defaultsJson: '{}',
      secrets: {},
    },
    desiredRevision: '8',
    appliedRevision: '8',
    connectionCheckSupported: true,
    harnessToolNames: [],
    canvasFunctionNames: [],
    applicationError: null,
    ...overrides,
  }
}

function result(overrides: Partial<PluginConnectionCheckResult> = {}): PluginConnectionCheckResult {
  return {
    pluginId: 'opencli',
    appliedRevision: '8',
    checkedAt: 1772366400,
    reachability: 'REACHABLE',
    authentication: 'ACCEPTED',
    browserSession: 'UNKNOWN',
    ...overrides,
  }
}

function doneRun(overrides: Partial<PluginConnectionCheckRun> = {}): PluginConnectionCheckRun {
  return {
    pluginId: 'opencli',
    expectedRevision: '8',
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
      expectedRevision: '8',
    })
  })

  it('refuses the entry when the instance does not declare the capability', () => {
    expect(pluginConnectionCheckExpected(plugin({ connectionCheckSupported: false }))).toBeNull()
  })

  it('never submits null coordinates for a plugin that is not applied', () => {
    expect(pluginConnectionCheckExpected(plugin({ appliedRevision: null }))).toBeNull()
  })

  it('never submits a zero applied revision', () => {
    // 已应用修订恒为正：0 表示本节点还没有已应用配置，不能作为检查坐标。
    expect(
      pluginConnectionCheckExpected(
        plugin({
          appliedRevision: '0',
          desiredRevision: '0',
          configuration: { ...plugin().configuration, revision: '0' },
        }),
      ),
    ).toBeNull()
  })

  it('refuses the entry while the local applied coordinates are behind desired', () => {
    expect(pluginConnectionCheckExpected(plugin({ desiredRevision: '9' }))).toBeNull()
  })
})

describe('pluginConnectionCheckIsStale', () => {
  it('keeps a result current only while it matches the coordinates the caller currently sees', () => {
    expect(pluginConnectionCheckIsStale(doneRun(), pluginConnectionCheckExpected(plugin()))).toBe(false)
  })

  it('marks a late result stale once the applied coordinates changed', () => {
    const revisionChanged = pluginConnectionCheckExpected(
      plugin({ appliedRevision: '9', desiredRevision: '9' }),
    )
    expect(pluginConnectionCheckIsStale(doneRun(), revisionChanged)).toBe(true)
  })

  it('marks a result stale when the plugin is no longer checkable or has disappeared', () => {
    expect(pluginConnectionCheckIsStale(doneRun(), null)).toBe(true)
    // 列表换版后本节点又变成待应用：结果不再对应当前配置。
    const pendingAgain = pluginConnectionCheckExpected(plugin({ desiredRevision: '9' }))
    expect(pendingAgain).toBeNull()
    expect(pluginConnectionCheckIsStale(doneRun(), pendingAgain)).toBe(true)
  })

  it('treats only completed runs as results', () => {
    for (const phase of ['pending', 'mismatch', 'conflict', 'failed'] as const) {
      expect(pluginConnectionCheckIsStale(doneRun({ phase, result: null }), null)).toBe(false)
    }
  })
})
