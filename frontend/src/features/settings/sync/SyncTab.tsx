import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type ChangeEvent } from 'react'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { configSyncService } from '@/shared/api/config-sync-service'
import { SyncExportModal } from '@/features/settings/sync/SyncExportModal'
import { SyncImportModal } from '@/features/settings/sync/SyncImportModal'

interface SyncTabProps {
  reloadSettings: () => void
  settingsDirty: boolean
}

interface PendingImport {
  fileName: string
  yaml: string
}

/** 设置同步页签：只提供导入、导出两个操作。 */
export function SyncTab({ reloadSettings, settingsDirty }: SyncTabProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const inventoryQuery = useQuery({
    queryKey: queryKeys.configSync.inventory,
    queryFn: () => configSyncService.getInventory(),
  })

  const [exportOpen, setExportOpen] = useState(false)
  const [pendingImport, setPendingImport] = useState<PendingImport | null>(null)
  const [reading, setReading] = useState(false)
  const [fileError, setFileError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const readingRef = useRef(false)

  const items = inventoryQuery.data?.items ?? []
  const canExport = inventoryQuery.isSuccess && items.length > 0

  const openFilePicker = () => {
    setFileError(null)
    fileInputRef.current?.click()
  }

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null
    // 立即清空 input：再次选择同一文件也会触发 change。
    event.target.value = ''
    // 上一次读取未结束前忽略新选择，避免旧读取结果覆盖新文件。
    if (file == null || readingRef.current) {
      return
    }
    if (!isYamlFile(file.name)) {
      setFileError(t('settings.sync.import.invalidFile'))
      return
    }
    readingRef.current = true
    setReading(true)
    try {
      const yaml = await file.text()
      setFileError(null)
      setStatus(null)
      setPendingImport({ fileName: file.name, yaml })
    } catch {
      setFileError(t('settings.sync.import.readFailed'))
    } finally {
      readingRef.current = false
      setReading(false)
    }
  }

  const closeImport = () => {
    // 关闭即释放文件内容，不把 YAML 留在页面状态里。
    setPendingImport(null)
  }

  const handleImported = () => {
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.providers.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.models.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.agents.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.skills.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.environments.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.mcpServers.all }),
      queryClient.invalidateQueries({ queryKey: queryKeys.configSync.inventory }),
    ])
    // 存在未保存 draft 时保持原样，由用户在结果中显式重新加载设置。
    if (!settingsDirty) {
      reloadSettings()
    }
  }

  const handleExported = (filename: string) => {
    setExportOpen(false)
    setStatus(t('settings.sync.export.done', { filename }))
  }

  return (
    <div className="settings-sync">
      <p className="settings-hint">{t('settings.sync.description')}</p>

      {fileError ? (
        <div className="settings-error-banner" role="alert">
          {fileError}
        </div>
      ) : null}
      {status ? (
        <p className="settings-sync-status" role="status">
          {status}
        </p>
      ) : null}

      <div className="settings-sync-actions">
        <button type="button" className="settings-button" onClick={openFilePicker} disabled={reading}>
          {t('settings.sync.import')}
        </button>
        <button
          type="button"
          className="settings-button primary"
          onClick={() => setExportOpen(true)}
          disabled={!canExport}
        >
          {t('settings.sync.export')}
        </button>
        <input
          ref={fileInputRef}
          className="settings-sync-file-input"
          type="file"
          accept=".yaml,.yml"
          disabled={reading}
          onChange={(event) => {
            void handleFileChange(event)
          }}
        />
      </div>

      {inventoryQuery.isLoading ? (
        <div className="state-block" role="status">
          {t('settings.sync.loading')}
        </div>
      ) : null}
      {inventoryQuery.isError ? (
        <div className="state-block danger" role="alert">
          <p>{t('settings.sync.loadFailed')}</p>
          <button
            type="button"
            className="settings-button"
            onClick={() => {
              void inventoryQuery.refetch()
            }}
          >
            {t('settings.sync.retry')}
          </button>
        </div>
      ) : null}

      {exportOpen ? (
        <SyncExportModal
          items={items}
          onClose={() => setExportOpen(false)}
          onExported={handleExported}
        />
      ) : null}
      {pendingImport ? (
        <SyncImportModal
          fileName={pendingImport.fileName}
          yaml={pendingImport.yaml}
          settingsDirty={settingsDirty}
          onClose={closeImport}
          onImported={handleImported}
          onReloadSettings={reloadSettings}
        />
      ) : null}
    </div>
  )
}

function isYamlFile(fileName: string): boolean {
  const lower = fileName.toLowerCase()
  return lower.endsWith('.yaml') || lower.endsWith('.yml')
}
