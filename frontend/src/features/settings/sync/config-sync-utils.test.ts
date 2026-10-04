import { describe, expect, it } from 'vitest'
import type { ConfigSyncItem } from '@/shared/api/contracts/config-sync'
import {
  CONFIG_SYNC_GROUPS,
  configSyncRefKey,
  configSyncScopeKindCounts,
  configSyncScopeRefs,
  computeExportScope,
} from '@/features/settings/sync/config-sync-utils'

function item(
  kind: ConfigSyncItem['kind'],
  name: string,
  dependencies: ConfigSyncItem['dependencies'] = [],
): ConfigSyncItem {
  return { kind, name, dependencies }
}

const INVENTORY: ConfigSyncItem[] = [
  item('agents', 'reviewer', [
    { kind: 'models', name: 'openai/gpt' },
    { kind: 'providers', name: 'openai' },
    { kind: 'skillPackages', name: 'core-tools' },
  ]),
  item('models', 'openai/gpt', [{ kind: 'providers', name: 'openai' }]),
  item('providers', 'openai'),
  item('skillPackages', 'core-tools'),
  item('environments', 'dev-env'),
  item('mcpServers', 'fetch'),
  item('settings', 'settings'),
]

describe('sync selection grouping', () => {
  // Agent/模型/提供商必须同组，其余种类各自成组，避免依赖链被割裂展示。
  it('keeps agents/models/providers in one group and other kinds separate', () => {
    expect(CONFIG_SYNC_GROUPS[0]!.kindKeys).toEqual(['agents', 'models', 'providers'])
    expect(CONFIG_SYNC_GROUPS.slice(1).map((group) => group.kindKeys)).toEqual([
      ['skillPackages'],
      ['environments'],
      ['mcpServers'],
      ['settings'],
    ])
  })
})

describe('computeExportScope', () => {
  // 直接选择一项时，其全部传递依赖必须进入闭包且只出现一次。
  it('adds transitive dependencies exactly once', () => {
    const scope = computeExportScope(INVENTORY, new Set([configSyncRefKey(INVENTORY[0]!)]))
    expect([...scope].sort()).toEqual(
      ['agents:reviewer', 'models:openai/gpt', 'providers:openai', 'skillPackages:core-tools'].sort(),
    )
  })

  // 互相引用的 Agent 依赖必须终止，不能无限展开。
  it('terminates on mutually referencing items', () => {
    const cyclic: ConfigSyncItem[] = [
      item('agents', 'a', [{ kind: 'agents', name: 'b' }]),
      item('agents', 'b', [{ kind: 'agents', name: 'a' }]),
    ]
    const scope = computeExportScope(cyclic, new Set(['agents:a']))
    expect([...scope].sort()).toEqual(['agents:a', 'agents:b'])
  })

  it('returns an empty scope for an empty selection', () => {
    expect(computeExportScope(INVENTORY, new Set()).size).toBe(0)
  })

  // 请求引用按库存顺序输出，跳过库存中不存在的依赖（由后端补齐）。
  it('emits scope refs in inventory order and skips unknown dependencies', () => {
    const withUnknown: ConfigSyncItem[] = [
      item('agents', 'reviewer', [{ kind: 'providers', name: 'ghost' }]),
      item('providers', 'openai'),
    ]
    const scope = computeExportScope(withUnknown, new Set(['agents:reviewer']))
    expect(configSyncScopeRefs(withUnknown, scope)).toEqual([{ kind: 'agents', name: 'reviewer' }])
  })

  it('aggregates kind counts for the effective scope only', () => {
    const scope = computeExportScope(INVENTORY, new Set(['models:openai/gpt']))
    expect(configSyncScopeKindCounts(INVENTORY, scope)).toEqual([
      { kind: 'models', count: 1 },
      { kind: 'providers', count: 1 },
    ])
  })
})
