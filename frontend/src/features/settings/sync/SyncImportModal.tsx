import { useState } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { isConflictReason } from '@/shared/api/client'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import { configSyncService } from '@/features/settings/config-sync-service'
import {
  configSyncErrorMessage,
  configSyncImportMode,
  configSyncKindLabelKey,
} from '@/features/settings/sync/config-sync-utils'
import type {
  ConfigSyncImportCheckDTO,
  ConfigSyncImportResultDTO,
  ConfigSyncKind,
} from '@/shared/api/generated/contracts'

interface SyncImportModalProps {
  fileName: string
  yaml: string
  preview: ConfigSyncImportCheckDTO
  settingsDirty: boolean
  onClose: () => void
  onImported: () => void
  /** 重新读取权威设置并重建 draft 快照；失败时 reject。 */
  onReloadSettings: () => Promise<void>
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
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<ConfigSyncImportResultDTO | null>(null)

  const [credentialResetConfirmed, setCredentialResetConfirmed] = useState(false)
  const [previewStale, setPreviewStale] = useState(false)
  const credentialResets = preview.pluginCredentialResets
  const needsCredentialReset = credentialResets.length > 0
  const importBlocked = previewStale || (needsCredentialReset && !credentialResetConfirmed)
  const mode = configSyncImportMode(preview)
  const kindLabel = (kind: ConfigSyncKind): string => t(configSyncKindLabelKey(kind))

  /** 导入成功后的重新加载失败只在本弹窗报错：draft 与 baseVersion 保持原样。 */
  const reloadSettings = async () => {
    setError(null)
    try {
      await onReloadSettings()
    } catch {
      setError(t('settings.error.load'))
    }
  }

  const handleImport = async (allowPartial: boolean) => {
    if (pending || importBlocked) return
    setPending(true)
    setError(null)
    try {
      const response = await configSyncService.importConfig(
        yaml, allowPartial, ...(credentialResetConfirmed ? [credentialResets] : []),
      )
      setResult(response)
      if (response.imported.length > 0) {
        onImported()
        // 无未保存草稿时立即采用导入后的权威；有草稿时保留原样，由用户显式重新加载。
        if (!settingsDirty) {
          await reloadSettings()
        }
      }
    } catch (importError) {
      if (isConflictReason(importError, 'PLUGIN_CREDENTIAL_RESET_CONFIRMATION')) {
        setPreviewStale(true)
        setCredentialResetConfirmed(false)
        setError(t('settings.sync.import.credentialPreviewStale'))
      } else {
        setError(configSyncErrorMessage(importError, t('settings.sync.import.failed')))
      }
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
      <Button
        onClick={() => {
          void handleImport(partial)
        }}
        loading={pending}
        disabled={importBlocked}
      >
        {pending ? t('settings.sync.import.importing') : t(labelKey)}
      </Button>
    )
  }

  return (
    <Dialog
      className="settings-sync-modal modal-touch-target"
      title={t('settings.sync.import.title')}
      pending={pending}
      onClose={onClose}
    >
        <div className="modal-body settings-sync-modal-body">
          {result ? (
            <ImportResult
              result={result}
              kindLabel={kindLabel}
              settingsDirty={settingsDirty}
              onReloadSettings={() => {
                void reloadSettings()
              }}
            />
          ) : (
            <>
              <p className="settings-hint">{t('settings.sync.import.previewHint')}</p>
              <p className="settings-sync-file-name">
                {t('settings.sync.import.fileName', { name: fileName })}
              </p>
              <ImportPreview preview={preview} mode={mode} kindLabel={kindLabel} />
              {needsCredentialReset ? (
                <section className="settings-sync-result-section" data-state="partial">
                  <h3>{t('settings.sync.import.credentialResetHeading')}</h3>
                  <ul className="settings-sync-result-list">
                    {credentialResets.map((id) => <li key={id}>{id}</li>)}
                  </ul>
                  <Checkbox
                    checked={credentialResetConfirmed}
                    disabled={pending || previewStale || mode === 'empty'}
                    onChange={setCredentialResetConfirmed}
                    label={t('settings.sync.import.confirmCredentialReset')}
                  />
                </section>
              ) : null}
            </>
          )}
          {error ? (
            <div className="settings-error-banner" role="alert">
              {error}
            </div>
          ) : null}
        </div>
        <div className="modal-footer">
          {result ? (
            <Button onClick={onClose}>{t('shared.close')}</Button>
          ) : (
            <>
              <Button variant="secondary" onClick={onClose} disabled={pending}>
                {t('shared.cancel')}
              </Button>
              {executeButton()}
            </>
          )}
        </div>
    </Dialog>
  )
}

function ImportPreview({
  preview,
  mode,
  kindLabel,
}: {
  preview: ConfigSyncImportCheckDTO
  mode: ReturnType<typeof configSyncImportMode>
  kindLabel: (kind: ConfigSyncKind) => string
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
                {entry.kind ? `${kindLabel(entry.kind)}: ` : ''}
                {entry.name} — {entry.reason}
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
  kindLabel: (kind: ConfigSyncKind) => string
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
  result: ConfigSyncImportResultDTO
  kindLabel: (kind: ConfigSyncKind) => string
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
                {entry.kind ? `${kindLabel(entry.kind)}: ` : ''}
                {entry.name} — {entry.reason}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {settingsDirty ? (
        <div className="settings-sync-result-dirty" role="status">
          <span>{t('settings.sync.import.dirtyHint')}</span>
          <Button variant="secondary" onClick={onReloadSettings}>
            {t('settings.sync.import.reloadSettings')}
          </Button>
        </div>
      ) : null}
    </div>
  )
}
