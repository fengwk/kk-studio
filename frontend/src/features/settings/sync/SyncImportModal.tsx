import { useRef, useState } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { useI18n } from '@/shared/i18n'
import { configSyncService } from '@/shared/api/config-sync-service'
import type {
  ConfigSyncImportResponseDTO,
  ConfigSyncKind,
} from '@/shared/api/contracts/config-sync'
import {
  CONFIG_SYNC_KIND_ORDER,
  configSyncErrorMessage,
  configSyncKindLabelKey,
} from '@/features/settings/sync/config-sync-utils'
import { useModalDismiss } from '@/features/settings/sync/use-modal-dismiss'

interface SyncImportModalProps {
  fileName: string
  yaml: string
  settingsDirty: boolean
  onClose: () => void
  onImported: () => void
  onReloadSettings: () => void
}

/** 导入弹窗：确认后提交 YAML 并展示已导入/跳过结果；失败只报错，YAML 不渲染。 */
export function SyncImportModal({
  fileName,
  yaml,
  settingsDirty,
  onClose,
  onImported,
  onReloadSettings,
}: SyncImportModalProps) {
  const { t } = useI18n()
  const cardRef = useRef<HTMLDivElement>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ConfigSyncImportResponseDTO | null>(null)

  useModalDismiss(cardRef, pending, onClose)

  const kindLabel = (kind: string): string =>
    CONFIG_SYNC_KIND_ORDER.includes(kind as ConfigSyncKind)
      ? t(configSyncKindLabelKey(kind as ConfigSyncKind))
      : kind

  const handleImport = async () => {
    setPending(true)
    setError(null)
    try {
      const response = await configSyncService.importConfig(yaml)
      setResult(response)
      if (response.imported.length > 0) {
        onImported()
      }
    } catch (importError) {
      setError(configSyncErrorMessage(importError, t('settings.sync.import.failed')))
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
        aria-label={t('settings.sync.import.title')}
        tabIndex={-1}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader
          title={t('settings.sync.import.title')}
          onClose={onClose}
          closeDisabled={pending}
        />
        <div className="modal-body settings-sync-modal-body">
          {result ? (
            <ImportResult
              result={result}
              kindLabel={kindLabel}
              settingsDirty={settingsDirty}
              onReloadSettings={onReloadSettings}
            />
          ) : (
            <>
              <p className="settings-hint">{t('settings.sync.import.confirmText')}</p>
              <p className="settings-sync-file-name">
                {t('settings.sync.import.fileName', { name: fileName })}
              </p>
              {error ? (
                <div className="settings-error-banner" role="alert">
                  {error}
                </div>
              ) : null}
            </>
          )}
        </div>
        <div className="modal-footer">
          {result ? (
            <button type="button" className="btn-primary" onClick={onClose}>
              {t('shared.close')}
            </button>
          ) : (
            <>
              <button type="button" className="ghost-btn" onClick={onClose} disabled={pending}>
                {t('shared.cancel')}
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={() => {
                  void handleImport()
                }}
                disabled={pending}
              >
                {pending ? t('settings.sync.import.importing') : t('settings.sync.import.confirm')}
              </button>
            </>
          )}
        </div>
      </div>
    </ModalBackdrop>
  )
}

function ImportResult({
  result,
  kindLabel,
  settingsDirty,
  onReloadSettings,
}: {
  result: ConfigSyncImportResponseDTO
  kindLabel: (kind: string) => string
  settingsDirty: boolean
  onReloadSettings: () => void
}) {
  const { t } = useI18n()
  const partial = result.skipped.length > 0

  return (
    <div className="settings-sync-result" data-state={partial ? 'partial' : 'success'}>
      <div className="settings-sync-result-section">
        <h3>{t('settings.sync.import.importedHeading')}</h3>
        {result.imported.length > 0 ? (
          <ul className="settings-sync-result-list">
            {result.imported.map((ref) => (
              <li key={`${ref.kind}:${ref.name}`}>
                {kindLabel(ref.kind)}: {ref.name}
              </li>
            ))}
          </ul>
        ) : (
          <p className="settings-hint">{t('settings.sync.import.nothingImported')}</p>
        )}
      </div>
      {result.skipped.length > 0 ? (
        <div className="settings-sync-result-section" data-state="partial">
          <h3>{t('settings.sync.import.skippedHeading')}</h3>
          <ul className="settings-sync-result-list">
            {result.skipped.map((entry, index) => (
              <li key={`${entry.kind}:${entry.name}:${index}`}>
                {kindLabel(entry.kind)}: {entry.name} — {entry.reason}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {settingsDirty ? (
        <div className="settings-sync-result-dirty" role="status">
          <span>{t('settings.sync.import.dirtyHint')}</span>
          <button type="button" className="settings-button" onClick={onReloadSettings}>
            {t('settings.sync.import.reloadSettings')}
          </button>
        </div>
      ) : null}
    </div>
  )
}
