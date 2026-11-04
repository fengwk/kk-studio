import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ChevronLeft,
  ChevronRight,
  CornerLeftUp,
  Download,
  FileImage,
  FileText,
  Folder,
  FolderOpen,
} from 'lucide-react'
import { ApiError, isNotFoundError } from '@/shared/api/client'
import { sessionFileService, type SessionFileOwner } from '@/shared/api/session-file-service'
import type { HarnessSessionFileEntryDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { formatFileSize } from '@/shared/lib/upload-file'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import {
  SESSION_FILE_TEXT_PAGE_LINES,
  formatSessionFileTime,
  isImageFileName,
  parseSessionFileTextWindow,
  parseSizeBytes,
  sessionFilePath,
} from '@/features/chat/session-files/session-file-format'
import './session-files.css'

export interface SessionFilesDialogProps {
  sessionId: string
  owner: SessionFileOwner
  onClose: () => void
}

interface DirectoryLevel {
  /** vfs 目录坐标；null 表示会话根目录。 */
  path: string | null
  /** 面包屑名称；根目录由 i18n 提供。 */
  name: string | null
}

interface TextWindowPosition {
  offset: number
  columnOffset: number
}

const INITIAL_WINDOW: TextWindowPosition = { offset: 1, columnOffset: 1 }

/** 后端只回传去敏后的失败原因；分类文案与原因分行展示，不用括号拼接。 */
function errorDetail(error: unknown): string | null {
  if (error instanceof ApiError) {
    const detail = error.errors?.detail
    if (typeof detail === 'string' && detail.length > 0) {
      return detail
    }
    return error.message.length > 0 ? error.message : null
  }
  return error instanceof Error && error.message.length > 0 ? error.message : null
}

function saveDownload(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = fileName
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** 用 Blob 生成图片预览 URL，并在替换或卸载时释放。 */
function useBlobUrl(blob: Blob | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (blob == null) {
      setUrl(null)
      return
    }
    const next = URL.createObjectURL(blob)
    setUrl(next)
    return () => URL.revokeObjectURL(next)
  }, [blob])
  return url
}

interface FilesErrorProps {
  error: unknown
  label: string
  onRetry?: () => void
  testId: string
}

function FilesError({ error, label, onRetry, testId }: FilesErrorProps) {
  const { t } = useI18n()
  const detail = errorDetail(error)
  return (
    <div className="session-files-error" role="alert" data-testid={testId}>
      <span className="session-files-error-text">
        <span>{label}</span>
        {detail != null ? <span className="session-files-error-detail">{detail}</span> : null}
      </span>
      {onRetry != null ? (
        <Button variant="ghost" size="compact" onClick={onRetry}>
          {t('shared.retry')}
        </Button>
      ) : null}
    </div>
  )
}

/**
 * 会话文件面板：只读浏览当前会话的 `vfs:/sessions/<session>/` 目录，
 * 文本按服务端有界窗口逐页回看，图片经下载字节预览，文件可下载。
 * 编辑由 Agent 的文件工具完成，面板不提供任何写操作。
 */
export function SessionFilesDialog({ sessionId, owner, onClose }: SessionFilesDialogProps) {
  const { t } = useI18n()
  const [levels, setLevels] = useState<DirectoryLevel[]>([{ path: null, name: null }])
  const [selected, setSelected] = useState<HarnessSessionFileEntryDTO | null>(null)
  const [windows, setWindows] = useState<TextWindowPosition[]>([INITIAL_WINDOW])
  const [downloadingPath, setDownloadingPath] = useState<string | null>(null)
  const [downloadError, setDownloadError] = useState<unknown>(null)
  const level = levels[levels.length - 1]!
  const windowPosition = windows[windows.length - 1]!
  const selectedIsImage = selected != null && isImageFileName(selected.name)

  const listQuery = useQuery({
    queryKey: ['session-files', sessionId, owner.kind, owner.id, level.path],
    queryFn: () => sessionFileService.listSessionFiles(sessionId, owner, level.path ?? undefined),
  })
  const contentQuery = useQuery({
    queryKey: [
      'session-files', sessionId, owner.kind, owner.id, 'content',
      selected?.path, windowPosition.offset, windowPosition.columnOffset,
    ],
    queryFn: () => sessionFileService.readSessionFileContent(
      sessionId,
      owner,
      sessionFilePath(sessionId, selected!.path),
      {
        offset: windowPosition.offset,
        columnOffset: windowPosition.columnOffset,
        limit: SESSION_FILE_TEXT_PAGE_LINES,
      },
    ),
    enabled: selected != null && !selectedIsImage,
  })
  const imageQuery = useQuery({
    queryKey: ['session-files', sessionId, owner.kind, owner.id, 'image', selected?.path],
    queryFn: ({ signal }) => sessionFileService.downloadSessionFile(
      sessionId,
      owner,
      sessionFilePath(sessionId, selected!.path),
      signal,
    ),
    enabled: selected != null && selectedIsImage,
  })
  const imageUrl = useBlobUrl(imageQuery.data?.blob)
  const parsedWindow = contentQuery.data == null
    ? null
    : parseSessionFileTextWindow(contentQuery.data.text)

  function goUp(): void {
    if (levels.length <= 1) {
      return
    }
    setLevels((current) => current.slice(0, -1))
    setSelected(null)
    setDownloadError(null)
  }

  function enterDirectory(entry: HarnessSessionFileEntryDTO): void {
    setLevels((current) => [...current, {
      path: sessionFilePath(sessionId, entry.path),
      name: entry.name.replace(/\/+$/, ''),
    }])
    setSelected(null)
    setDownloadError(null)
  }

  function selectFile(entry: HarnessSessionFileEntryDTO): void {
    setSelected(entry)
    setWindows([INITIAL_WINDOW])
    setDownloadError(null)
  }

  async function download(entry: HarnessSessionFileEntryDTO): Promise<void> {
    if (downloadingPath != null) {
      return
    }
    setDownloadingPath(entry.path)
    setDownloadError(null)
    try {
      const result = await sessionFileService.downloadSessionFile(
        sessionId,
        owner,
        sessionFilePath(sessionId, entry.path),
      )
      saveDownload(result.blob, result.fileName)
    } catch (error) {
      setDownloadError(error)
    } finally {
      setDownloadingPath(null)
    }
  }

  const entries = listQuery.data?.entries ?? []
  const locationLabel = levels.length === 1
    ? t('ai.sessionFiles.root')
    : levels.slice(1).map((item) => item.name).join(' / ')
  const currentVfsPath = level.path ?? sessionFilePath(sessionId, '')
  const selectedSize = parseSizeBytes(selected?.sizeBytes)
  const selectedUpdatedAt = formatSessionFileTime(selected?.updatedAt)
  const selectedMeta = [
    selectedSize == null ? null : formatFileSize(selectedSize),
    selectedUpdatedAt == null ? null : t('ai.sessionFiles.updatedAt', { time: selectedUpdatedAt }),
  ].filter((value): value is string => value != null).join(' · ')

  return (
    <Dialog
      className="session-files-modal modal-touch-target"
      title={t('ai.sessionFiles.title')}
      closeLabel={t('shared.close')}
      onClose={onClose}
    >
      <div className="modal-body session-files-body">
        <section
          className="session-files-browser"
          aria-label={t('ai.sessionFiles.listLabel')}
          tabIndex={-1}
          data-autofocus
        >
          <div className="session-files-toolbar">
            <Button
              variant="ghost"
              size="compact"
              onClick={goUp}
              disabled={levels.length <= 1}
              data-testid="session-files-up"
            >
              <CornerLeftUp size={14} aria-hidden="true" />
              <span>{t('ai.sessionFiles.up')}</span>
            </Button>
            <span
              className="session-files-location"
              title={currentVfsPath}
              data-testid="session-files-location"
            >
              <FolderOpen size={14} aria-hidden="true" />
              <span>{locationLabel}</span>
            </span>
            {listQuery.isFetching ? <LoadingSpinner size={12} decorative /> : null}
          </div>

          {listQuery.isLoading ? (
            <div className="session-files-state" role="status">
              <LoadingSpinner size={14} decorative />
              <span>{t('ai.sessionFiles.loading')}</span>
            </div>
          ) : null}
          {listQuery.error != null ? (
            <FilesError
              error={listQuery.error}
              label={isNotFoundError(listQuery.error)
                ? t('ai.sessionFiles.notFound')
                : t('ai.sessionFiles.listFailed')}
              onRetry={() => { void listQuery.refetch() }}
              testId="session-files-list-error"
            />
          ) : null}
          {!listQuery.isLoading && listQuery.error == null && entries.length === 0 ? (
            <StateBlock title={t('ai.sessionFiles.empty')} />
          ) : null}
          {entries.length > 0 ? (
            <ul className="session-files-list" data-testid="session-files-list">
              {entries.map((entry) => {
                const size = parseSizeBytes(entry.sizeBytes)
                return (
                  <li key={entry.path} className="session-files-row" data-selected={selected?.path === entry.path}>
                    <button
                      type="button"
                      className="session-files-entry"
                      title={entry.path}
                      aria-pressed={entry.directory ? undefined : selected?.path === entry.path}
                      data-testid={entry.directory ? 'session-file-directory' : 'session-file-entry'}
                      onClick={() => {
                        if (entry.directory) {
                          enterDirectory(entry)
                        } else {
                          selectFile(entry)
                        }
                      }}
                    >
                      {entry.directory
                        ? <Folder size={14} aria-hidden="true" />
                        : isImageFileName(entry.name)
                          ? <FileImage size={14} aria-hidden="true" />
                          : <FileText size={14} aria-hidden="true" />}
                      <span className="session-files-name">{entry.name}</span>
                      {!entry.directory && size != null ? (
                        <span className="session-files-size">{formatFileSize(size)}</span>
                      ) : null}
                    </button>
                    {entry.directory ? (
                      <ChevronRight className="session-files-enter" size={14} aria-hidden="true" />
                    ) : (
                      <IconButton
                        label={t('ai.sessionFiles.downloadNamed', { name: entry.name })}
                        size="compact"
                        disabled={downloadingPath != null}
                        onClick={() => { void download(entry) }}
                      >
                        <Download size={14} aria-hidden="true" />
                      </IconButton>
                    )}
                  </li>
                )
              })}
            </ul>
          ) : null}
        </section>

        <section className="session-files-preview" aria-label={t('ai.sessionFiles.previewLabel')}>
          {selected == null ? (
            <p className="session-files-hint">{t('ai.sessionFiles.selectHint')}</p>
          ) : (
            <>
              <header className="session-files-preview-header">
                <div className="session-files-preview-identity">
                  <strong title={selected.path}>{selected.name}</strong>
                  {selectedMeta.length > 0 ? (
                    <span className="session-files-meta">{selectedMeta}</span>
                  ) : null}
                </div>
                <Button
                  variant="ghost"
                  size="compact"
                  loading={downloadingPath === selected.path}
                  disabled={downloadingPath != null}
                  onClick={() => { void download(selected) }}
                >
                  <Download size={14} aria-hidden="true" />
                  <span>{t('ai.sessionFiles.download')}</span>
                </Button>
              </header>

              {downloadError != null ? (
                <FilesError
                  error={downloadError}
                  label={t('ai.sessionFiles.downloadFailed')}
                  testId="session-files-download-error"
                />
              ) : null}

              {selectedIsImage ? (
                imageQuery.isLoading ? (
                  <div className="session-files-state" role="status">
                    <LoadingSpinner size={14} decorative />
                    <span>{t('ai.sessionFiles.loadingPreview')}</span>
                  </div>
                ) : imageQuery.error != null ? (
                  <FilesError
                    error={imageQuery.error}
                    label={isNotFoundError(imageQuery.error)
                      ? t('ai.sessionFiles.notFound')
                      : t('ai.sessionFiles.previewFailed')}
                    onRetry={() => { void imageQuery.refetch() }}
                    testId="session-files-preview-error"
                  />
                ) : imageUrl != null ? (
                  <div className="session-files-image-frame">
                    <img className="session-files-image" src={imageUrl} alt={selected.name} />
                  </div>
                ) : null
              ) : contentQuery.isLoading ? (
                <div className="session-files-state" role="status">
                  <LoadingSpinner size={14} decorative />
                  <span>{t('ai.sessionFiles.loadingPreview')}</span>
                </div>
              ) : contentQuery.error != null ? (
                <FilesError
                  error={contentQuery.error}
                  label={contentQuery.error instanceof ApiError && contentQuery.error.status === 422
                    ? t('ai.sessionFiles.unreadable')
                    : isNotFoundError(contentQuery.error)
                      ? t('ai.sessionFiles.notFound')
                      : t('ai.sessionFiles.previewFailed')}
                  onRetry={() => { void contentQuery.refetch() }}
                  testId="session-files-preview-error"
                />
              ) : parsedWindow != null ? (
                <>
                  <pre className="session-files-text" tabIndex={0} data-testid="session-file-text">
                    {parsedWindow.body}
                  </pre>
                  <div className="session-files-pager">
                    <Button
                      variant="ghost"
                      size="compact"
                      disabled={windows.length <= 1}
                      onClick={() => setWindows((current) => current.slice(0, -1))}
                    >
                      <ChevronLeft size={14} aria-hidden="true" />
                      <span>{t('ai.sessionFiles.previousWindow')}</span>
                    </Button>
                    <span className="session-files-range" data-testid="session-files-range">
                      {parsedWindow.range == null
                        ? t('ai.sessionFiles.emptyFile')
                        : t('ai.sessionFiles.windowRange', { range: parsedWindow.range })}
                      {parsedWindow.truncated ? (
                        <span className="session-files-truncated">{t('ai.sessionFiles.truncated')}</span>
                      ) : null}
                    </span>
                    <Button
                      variant="ghost"
                      size="compact"
                      disabled={parsedWindow.next == null}
                      onClick={() => {
                        const next = parsedWindow.next
                        if (next != null) {
                          setWindows((current) => [...current, next])
                        }
                      }}
                    >
                      <span>{t('ai.sessionFiles.nextWindow')}</span>
                      <ChevronRight size={14} aria-hidden="true" />
                    </Button>
                  </div>
                </>
              ) : null}
            </>
          )}
        </section>
      </div>
      <div className="modal-footer">
        <span className="session-files-readonly">{t('ai.sessionFiles.readOnly')}</span>
        <Button variant="ghost" onClick={onClose} data-testid="session-files-close">
          {t('shared.close')}
        </Button>
      </div>
    </Dialog>
  )
}
