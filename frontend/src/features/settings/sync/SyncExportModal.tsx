import { useMemo, useState } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import { configSyncService } from '@/features/settings/config-sync-service'
import {
  CONFIG_SYNC_GROUPS,
  configSyncErrorMessage,
  configSyncRefKey,
  configSyncScopeKindCounts,
  configSyncScopeRefs,
  computeExportScope,
} from '@/features/settings/sync/config-sync-utils'
import {
  CONFIG_SYNC_EXPORT_FILENAME,
  downloadConfigYaml,
} from '@/features/settings/sync/download-config-yaml'
import {
  SyncExportGroupSection,
  SyncExportScopeSummary,
} from '@/features/settings/sync/SyncExportSections'
import type { ConfigSyncItem, ConfigSyncKind } from '@/shared/api/generated/contracts'

interface SyncExportModalProps {
  items: ConfigSyncItem[]
  onClose: () => void
  onExported: (filename: string) => void
}

/** 导出弹窗：按库存勾选直接导出项，依赖仅用于展示闭包，由后端按权威配置补齐。 */
export function SyncExportModal({ items, onClose, onExported }: SyncExportModalProps) {
  const { t } = useI18n()
  const [directKeys, setDirectKeys] = useState<Set<string>>(
    () => new Set(items.map(configSyncRefKey)),
  )
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const scope = useMemo(() => computeExportScope(items, directKeys), [directKeys, items])
  const scopeRefs = useMemo(() => configSyncScopeRefs(items, scope), [items, scope])
  const directRefs = useMemo(() => configSyncScopeRefs(items, directKeys), [directKeys, items])
  const kindCounts = useMemo(() => configSyncScopeKindCounts(items, scope), [items, scope])
  const itemCount = scopeRefs.length
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
    <Dialog
      className="settings-sync-modal modal-touch-target"
      title={t('settings.sync.export.title')}
      pending={pending}
      onClose={onClose}
    >
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
          {CONFIG_SYNC_GROUPS.map((group) => (
            <SyncExportGroupSection
              key={group.key}
              group={group}
              items={items}
              scope={scope}
              directKeys={directKeys}
              onToggleKind={toggleKind}
              onToggleItem={toggleItem}
            />
          ))}
        </div>

        <SyncExportScopeSummary kindCounts={kindCounts} itemCount={itemCount} />
        {empty ? (
          <p className="settings-hint danger" role="status">
            {t('settings.sync.export.empty')}
          </p>
        ) : null}
      </div>
      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose} disabled={pending}>
          {t('shared.cancel')}
        </Button>
        <Button
          onClick={() => {
            void handleExport()
          }}
          disabled={empty}
          loading={pending}
        >
          {pending ? t('settings.sync.export.exporting') : t('settings.sync.export.confirm')}
        </Button>
      </div>
    </Dialog>
  )
}
