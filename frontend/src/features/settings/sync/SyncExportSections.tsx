import {
  configSyncKindLabelKey,
  configSyncRefKey,
  type ConfigSyncKindGroup,
} from '@/features/settings/sync/config-sync-utils'
import { useI18n } from '@/shared/i18n'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import type { ConfigSyncItem, ConfigSyncKind } from '@/shared/api/generated/contracts'

/** 种类级全选只改直接选择；被依赖锁定的条目单独呈现。 */
function KindToggle({
  kind,
  label,
  items,
  directKeys,
  onToggle,
}: {
  kind: ConfigSyncKind
  label: string
  items: ConfigSyncItem[]
  directKeys: ReadonlySet<string>
  onToggle: (kind: ConfigSyncKind) => void
}) {
  const kindItems = items.filter((item) => item.kind === kind)
  const directCount = kindItems.filter((item) => directKeys.has(configSyncRefKey(item))).length
  const allDirect = kindItems.length > 0 && directCount === kindItems.length

  return (
    <Checkbox
      className="settings-sync-kind-toggle"
      checked={allDirect}
      indeterminate={directCount > 0 && !allDirect}
      onChange={() => onToggle(kind)}
      aria-label={label}
    >
      <span>{label}</span>
    </Checkbox>
  )
}

/** 单个种类的条目清单：依赖带入的条目勾选但禁用，并标出“依赖”。 */
function KindItemList({
  kind,
  items,
  scope,
  directKeys,
  onToggleItem,
}: {
  kind: ConfigSyncKind
  items: ConfigSyncItem[]
  scope: ReadonlySet<string>
  directKeys: ReadonlySet<string>
  onToggleItem: (item: ConfigSyncItem) => void
}) {
  const { t } = useI18n()
  const kindLabel = t(configSyncKindLabelKey(kind))
  return (
    <ul className="settings-sync-items">
      {items
        .filter((item) => item.kind === kind)
        .map((item) => {
          const key = configSyncRefKey(item)
          const inScope = scope.has(key)
          const direct = directKeys.has(key)
          return (
            <li className="settings-sync-item" key={key}>
              <Checkbox
                className="settings-sync-item-label"
                checked={inScope}
                disabled={inScope && !direct}
                onChange={() => onToggleItem(item)}
                aria-label={`${kindLabel}: ${item.name}`}
              >
                <span className="settings-sync-item-name">{item.name}</span>
                {inScope && !direct ? (
                  <span className="settings-sync-dependency">
                    {t('settings.sync.export.dependency')}
                  </span>
                ) : null}
              </Checkbox>
            </li>
          )
        })}
    </ul>
  )
}

/**
 * 一个配置分组：单种类分组把种类勾选放在分组标题位，
 * 多种类分组每种类各有一行勾选头。没有库存条目的分组整组不渲染。
 */
export function SyncExportGroupSection({
  group,
  items,
  scope,
  directKeys,
  onToggleKind,
  onToggleItem,
}: {
  group: ConfigSyncKindGroup
  items: ConfigSyncItem[]
  scope: ReadonlySet<string>
  directKeys: ReadonlySet<string>
  onToggleKind: (kind: ConfigSyncKind) => void
  onToggleItem: (item: ConfigSyncItem) => void
}) {
  const { t } = useI18n()
  const groupKindKeys = group.kindKeys.filter((kind) => items.some((item) => item.kind === kind))
  if (groupKindKeys.length === 0) {
    return null
  }
  const multiKindGroup = group.kindKeys.length > 1
  const singleKind = multiKindGroup ? null : group.kindKeys[0]!

  return (
    <section className="settings-sync-group">
      <div className="settings-sync-group-head">
        {singleKind ? (
          <KindToggle
            kind={singleKind}
            label={t(configSyncKindLabelKey(singleKind))}
            items={items}
            directKeys={directKeys}
            onToggle={onToggleKind}
          />
        ) : (
          <h3 className="settings-sync-group-title">{t(group.labelKey)}</h3>
        )}
      </div>
      {groupKindKeys.map((kind) => (
        <div className="settings-sync-kind" key={kind}>
          {multiKindGroup ? (
            <div className="settings-sync-kind-head">
              <KindToggle
                kind={kind}
                label={t(configSyncKindLabelKey(kind))}
                items={items}
                directKeys={directKeys}
                onToggle={onToggleKind}
              />
            </div>
          ) : null}
          <KindItemList
            kind={kind}
            items={items}
            scope={scope}
            directKeys={directKeys}
            onToggleItem={onToggleItem}
          />
        </div>
      ))}
    </section>
  )
}

/** 导出闭包摘要：种类数与条目数，以及按种类的条目计数。 */
export function SyncExportScopeSummary({
  kindCounts,
  itemCount,
}: {
  kindCounts: Array<{ kind: ConfigSyncKind; count: number }>
  itemCount: number
}) {
  const { t } = useI18n()
  return (
    <div className="settings-sync-scope">
      <p className="settings-sync-scope-summary" role="status">
        {t('settings.sync.export.scopeSummary', {
          kinds: kindCounts.length,
          items: itemCount,
        })}
      </p>
      <ul className="settings-sync-scope-list">
        {kindCounts.map(({ kind, count }) => (
          <li key={kind} data-kind={kind}>
            {t('settings.sync.export.kindCount', {
              kind: t(configSyncKindLabelKey(kind)),
              count,
            })}
          </li>
        ))}
      </ul>
    </div>
  )
}
