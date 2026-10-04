import type {
  ConfigSyncItem,
  ConfigSyncKind,
  ConfigSyncRef,
} from '@/shared/api/contracts/config-sync'

/** 库存种类展示顺序：与契约的七类一致，Agent 在模型/提供商之前便于依赖自解释。 */
export const CONFIG_SYNC_KIND_ORDER: ConfigSyncKind[] = [
  'agents',
  'models',
  'providers',
  'skillPackages',
  'environments',
  'mcpServers',
  'settings',
]

export interface ConfigSyncKindGroup {
  key: string
  kindKeys: ConfigSyncKind[]
  labelKey: string
}

/**
 * 导出弹窗分组：Agent/模型/提供商同组展示（三者互为依赖链），其余种类各自成组。
 */
export const CONFIG_SYNC_GROUPS: ConfigSyncKindGroup[] = [
  {
    key: 'agentsModels',
    kindKeys: ['agents', 'models', 'providers'],
    labelKey: 'settings.sync.group.agentsModels',
  },
  { key: 'skillPackages', kindKeys: ['skillPackages'], labelKey: 'settings.sync.kind.skillPackages' },
  { key: 'environments', kindKeys: ['environments'], labelKey: 'settings.sync.kind.environments' },
  { key: 'mcpServers', kindKeys: ['mcpServers'], labelKey: 'settings.sync.kind.mcpServers' },
  { key: 'settings', kindKeys: ['settings'], labelKey: 'settings.sync.kind.settings' },
]

export function configSyncKindLabelKey(kind: ConfigSyncKind): string {
  return `settings.sync.kind.${kind}`
}

/** 引用在库存/选择集合中的稳定键：种类与业务名组合，避免同名跨种类冲突。 */
export function configSyncRefKey(ref: ConfigSyncRef): string {
  return `${ref.kind}:${ref.name}`
}

export function indexConfigSyncItems(
  items: ConfigSyncItem[],
): Map<string, ConfigSyncItem> {
  return new Map(items.map((item) => [configSyncRefKey(item), item]))
}

/**
 * 计算实际导出闭包：从直接选择出发，沿库存依赖做去重 BFS。
 * visited 集同时充当结果集，保证互相引用的 Agent 等环路必然终止。
 */
export function computeExportScope(
  items: ConfigSyncItem[],
  directlySelected: ReadonlySet<string>,
): Set<string> {
  const byKey = indexConfigSyncItems(items)
  const scope = new Set<string>()
  const queue = [...directlySelected]
  while (queue.length > 0) {
    const key = queue.shift()!
    if (scope.has(key)) {
      continue
    }
    scope.add(key)
    const item = byKey.get(key)
    if (item == null) {
      continue
    }
    for (const dependency of item.dependencies) {
      const dependencyKey = configSyncRefKey(dependency)
      if (!scope.has(dependencyKey)) {
        queue.push(dependencyKey)
      }
    }
  }
  return scope
}

/** 按库存顺序把给定引用集合整理成请求引用；不在此集合内的依赖由后端按权威配置补齐。 */
export function configSyncScopeRefs(
  items: ConfigSyncItem[],
  scope: ReadonlySet<string>,
): ConfigSyncRef[] {
  return items
    .filter((item) => scope.has(configSyncRefKey(item)))
    .map((item) => ({ kind: item.kind, name: item.name }))
}

/** 统一提取错误文案：后端返回的可读消息优先，否则使用本地兜底，绝不把失败当作成功。 */
export function configSyncErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}

/** 按种类统计闭包条目数，仅返回有内容的种类，用于展示实际导出范围。 */
export function configSyncScopeKindCounts(
  items: ConfigSyncItem[],
  scope: ReadonlySet<string>,
): Array<{ kind: ConfigSyncKind; count: number }> {
  return CONFIG_SYNC_KIND_ORDER
    .map((kind) => ({
      kind,
      count: items.filter(
        (item) => item.kind === kind && scope.has(configSyncRefKey(item)),
      ).length,
    }))
    .filter((entry) => entry.count > 0)
}
