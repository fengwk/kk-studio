import { useRef, useState } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { useI18n } from '@/shared/i18n'
import { configSyncService } from '@/shared/api/config-sync-service'
import type {
  ConfigSyncImportCheckDTO,
  ConfigSyncImportResponseDTO,
  ConfigSyncKind,
} from '@/shared/api/contracts/config-sync'
import {
  CONFIG_SYNC_KIND_ORDER,
  configSyncErrorMessage,
  configSyncImportMode,
  configSyncKindLabelKey,
} from '@/features/settings/sync/config-sync-utils'
import { useModalDismiss } from '@/features/settings/sync/use-modal-dismiss'

interface SyncImportModalProps {
  fileName: string
  yaml: string
  preview: ConfigSyncImportCheckDTO
  settingsDirty: boolean
  onClose: () => void
  onImported: () => void
  onReloadSettings: () => void
}

/**
 * 导入弹窗：先展示预检查计划（将新增/将覆盖/将跳过），再按可用项执行导入。
 * 存在跳过项时必须由用户显式选择「仅导入可用配置」，没有可用项则不提供执行按钮；
 * 失败只报错，YAML 不渲染。
 */
export function SyncImportModal({
  fileName,
  yaml,
  preview,
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

  const mode = configSyncImportMode(preview)
  const kindLabel = (kind: string): string =>
    CONFIG_SYNC_KIND_ORDER.includes(kind as ConfigSyncKind)
      ? t(configSyncKindLabelKey(kind as ConfigSyncKind))
      : kind

  const handleImport = async (allowPartial: boolean) => {
    setPending(true)
    setError(null)
    try {
      const response = await configSyncService.importConfig(yaml, allowPartial)
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

  const executeButton = () => {
    if (mode === 'empty') {
      return null
    }
    // 单一执行按钮：部分可用时改变文案与 allowPartial，不复制两套按钮。
    const partial = mode === 'partial'
    const labelKey = partial
      ? 'settings.sync.import.confirmPartial'
      : 'settings.sync.import.confirm'
    return (
      <button
        type="button"
        className="btn-primary"
        onClick={() => {
          void handleImport(partial)
        }}
        disabled={pending}
      >
        {pending ? t('settings.sync.import.importing') : t(labelKey)}
      </button>
    )
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
              <p className="settings-hint">{t('settings.sync.import.previewHint')}</p>
              <p className="settings-sync-file-name">
                {t('settings.sync.import.fileName', { name: fileName })}
              </p>
              <ImportPreview preview={preview} mode={mode} kindLabel={kindLabel} />
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
              {executeButton()}
            </>
          )}
        </div>
      </div>
    </ModalBackdrop>
  )
}

function ImportPreview({
  preview,
  mode,
  kindLabel,
}: {
  preview: ConfigSyncImportCheckDTO
  mode: ReturnType<typeof configSyncImportMode>
  kindLabel: (kind: string) => string
}) {
  const { t } = useI18n()

  return (
    <div className="settings-sync-preview" data-mode={mode}>
      {mode === 'empty' ? (
        <p className="settings-hint" role="status">
          {t('settings.sync.import.nothingUsable')}
        </p>
      ) : (
        <>
          <RefList
            heading={t('settings.sync.import.createdHeading')}
            state="created"
            refs={preview.created}
            kindLabel={kindLabel}
          />
          <RefList
            heading={t('settings.sync.import.updatedHeading')}
            state="updated"
            refs={preview.updated}
            kindLabel={kindLabel}
          />
        </>
      )}
      {preview.skipped.length > 0 ? (
        <div className="settings-sync-result-section" data-state="partial">
          <h3>{t('settings.sync.import.previewSkippedHeading')}</h3>
          <ul className="settings-sync-result-list">
            {preview.skipped.map((entry, index) => (
              <li key={`${entry.kind}:${entry.name}:${index}`}>
                {kindLabel(entry.kind)}: {entry.name} — {entry.reason}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}

function RefList({
  heading,
  state,
  refs,
  kindLabel,
}: {
  heading: string
  state: 'created' | 'updated'
  refs: ConfigSyncImportCheckDTO['created']
  kindLabel: (kind: string) => string
}) {
  if (refs.length === 0) {
    return null
  }
  return (
    <div className="settings-sync-result-section" data-state={state}>
      <h3>{heading}</h3>
      <ul className="settings-sync-result-list">
        {refs.map((ref) => (
          <li key={`${ref.kind}:${ref.name}`}>
            {kindLabel(ref.kind)}: {ref.name}
          </li>
        ))}
      </ul>
    </div>
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
