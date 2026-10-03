import { useEffect, useMemo, useRef, useState } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { useI18n } from '@/shared/i18n'
import { configSyncService } from '@/shared/api/config-sync-service'
import type { ConfigSyncItem, ConfigSyncKind } from '@/shared/api/contracts/config-sync'
import {
  CONFIG_SYNC_GROUPS,
  configSyncErrorMessage,
  configSyncKindLabelKey,
  configSyncRefKey,
  configSyncScopeKindCounts,
  configSyncScopeRefs,
  computeExportScope,
} from '@/features/settings/sync/config-sync-utils'
import {
  CONFIG_SYNC_EXPORT_FILENAME,
  downloadConfigYaml,
} from '@/features/settings/sync/download-config-yaml'
import { useModalDismiss } from '@/features/settings/sync/use-modal-dismiss'

interface SyncExportModalProps {
  items: ConfigSyncItem[]
  onClose: () => void
  onExported: (filename: string) => void
}

/** 导出弹窗：按库存勾选直接导出项，依赖仅用于展示闭包，由后端按权威配置补齐。 */
export function SyncExportModal({ items, onClose, onExported }: SyncExportModalProps) {
  const { t } = useI18n()
  const cardRef = useRef<HTMLDivElement>(null)
  const [directKeys, setDirectKeys] = useState<Set<string>>(
    () => new Set(items.map(configSyncRefKey)),
  )
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useModalDismiss(cardRef, pending, onClose)

  const scope = useMemo(() => computeExportScope(items, directKeys), [directKeys, items])
  const scopeRefs = useMemo(() => configSyncScopeRefs(items, scope), [items, scope])
  const directRefs = useMemo(() => configSyncScopeRefs(items, directKeys), [directKeys, items])
  const kindCounts = useMemo(() => configSyncScopeKindCounts(items, scope), [items, scope])
  const empty = directRefs.length === 0

  const toggleItem = (item: ConfigSyncItem) => {
    const key = configSyncRefKey(item)
    setDirectKeys((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }

  const toggleKind = (kind: ConfigSyncKind) => {
    const kindItems = items.filter((item) => item.kind === kind)
    const allDirect =
      kindItems.length > 0 && kindItems.every((item) => directKeys.has(configSyncRefKey(item)))
    setDirectKeys((prev) => {
      const next = new Set(prev)
      for (const item of kindItems) {
        const key = configSyncRefKey(item)
        if (allDirect) {
          next.delete(key)
        } else {
          next.add(key)
        }
      }
      return next
    })
  }

  const handleExport = async () => {
    setPending(true)
    setError(null)
    try {
      const response = await configSyncService.exportConfig(directRefs)
      downloadConfigYaml(response.yaml, CONFIG_SYNC_EXPORT_FILENAME)
      onExported(CONFIG_SYNC_EXPORT_FILENAME)
    } catch (exportError) {
      setError(configSyncErrorMessage(exportError, t('settings.sync.export.failed')))
    } finally {
      setPending(false)
    }
  }

  return (
    <ModalBackdrop onClose={pending ? () => undefined : onClose}>
      <div
        ref={cardRef}
        className="modal-card settings-sync-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('settings.sync.export.title')}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader
          title={t('settings.sync.export.title')}
          onClose={onClose}
          closeDisabled={pending}
        />
        <div className="modal-body settings-sync-modal-body">
          <p className="settings-hint">{t('settings.sync.export.description')}</p>
          <p className="settings-hint">{t('settings.sync.credentialsNotice')}</p>
          <p className="settings-hint">{t('settings.sync.export.dependencyHint')}</p>

          {error ? (
            <div className="settings-error-banner" role="alert">
              {error}
            </div>
          ) : null}

          <div className="settings-sync-groups">
            {CONFIG_SYNC_GROUPS.map((group) => {
              const groupKindKeys = group.kindKeys.filter((kind) =>
                items.some((item) => item.kind === kind),
              )
              if (groupKindKeys.length === 0) {
                return null
              }
              const multiKindGroup = group.kindKeys.length > 1
              const singleKind = multiKindGroup ? null : group.kindKeys[0]!
              return (
                <section className="settings-sync-group" key={group.key}>
                  <div className="settings-sync-group-head">
                    {singleKind ? (
                      <KindToggle
                        kind={singleKind}
                        label={t(configSyncKindLabelKey(singleKind))}
                        items={items}
                        directKeys={directKeys}
                        onToggle={toggleKind}
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
                            onToggle={toggleKind}
                          />
                        </div>
                      ) : null}
                      <ul className="settings-sync-items">
                        {items
                          .filter((item) => item.kind === kind)
                          .map((item) => {
                            const key = configSyncRefKey(item)
                            const inScope = scope.has(key)
                            const direct = directKeys.has(key)
                            const kindLabel = t(configSyncKindLabelKey(kind))
                            return (
                              <li className="settings-sync-item" key={key}>
                                <label className="settings-sync-item-label">
                                  <input
                                    type="checkbox"
                                    checked={inScope}
                                    disabled={inScope && !direct}
                                    onChange={() => toggleItem(item)}
                                    aria-label={`${kindLabel}: ${item.name}`}
                                  />
                                  <span className="settings-sync-item-name">{item.name}</span>
                                  {inScope && !direct ? (
                                    <span className="settings-sync-dependency">
                                      {t('settings.sync.export.dependency')}
                                    </span>
                                  ) : null}
                                </label>
                              </li>
                            )
                          })}
                      </ul>
                    </div>
                  ))}
                </section>
              )
            })}
          </div>

          <div className="settings-sync-scope">
            <p className="settings-sync-scope-summary" role="status">
              {t('settings.sync.export.scopeSummary', {
                kinds: kindCounts.length,
                items: scopeRefs.length,
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
            {empty ? (
              <p className="settings-hint danger" role="status">
                {t('settings.sync.export.empty')}
              </p>
            ) : null}
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="ghost-btn" onClick={onClose} disabled={pending}>
            {t('shared.cancel')}
          </button>
          <button
            type="button"
            className="btn-primary"
            onClick={() => {
              void handleExport()
            }}
            disabled={pending || empty}
          >
            {pending ? t('settings.sync.export.exporting') : t('settings.sync.export.confirm')}
          </button>
        </div>
      </div>
    </ModalBackdrop>
  )
}

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
  const inputRef = useRef<HTMLInputElement>(null)
  const kindItems = items.filter((item) => item.kind === kind)
  const directCount = kindItems.filter((item) => directKeys.has(configSyncRefKey(item))).length
  const allDirect = kindItems.length > 0 && directCount === kindItems.length

  useEffect(() => {
    if (inputRef.current) {
      inputRef.current.indeterminate = directCount > 0 && !allDirect
    }
  }, [allDirect, directCount])

  return (
    <label className="settings-sync-kind-toggle">
      <input
        ref={inputRef}
        type="checkbox"
        checked={allDirect}
        onChange={() => onToggle(kind)}
        aria-label={label}
      />
      <span>{label}</span>
    </label>
  )
}
