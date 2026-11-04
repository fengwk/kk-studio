import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { ChevronRight, CornerLeftUp, Folder, HelpCircle } from 'lucide-react'
import { ApiError } from '@/shared/api/client'
import { environmentService } from '@/features/environment/environment-service'
import type {
  EnvironmentIdeDirectoryListingDTO,
  EnvironmentIdeDirectoryListingEntry,
} from '@/shared/api/generated/contracts'
import { Button } from '@/shared/ui/controls/Button'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'

export interface OpenDirectoryModalProps {
  environmentId: string
  /** 当前 IDE 视图目录，作为初始选中项；树仍从宿主主目录开始。 */
  currentDirectory: string
  /** 该 Environment 已确认切换成功的最近目录，最新的在前。 */
  recentDirectories: string[]
  pending?: boolean
  error?: string | null
  onConfirm: (directory: string) => void
  onClose: () => void
}

interface DirectoryNodeState {
  status: 'loading' | 'ready' | 'error'
  listing?: EnvironmentIdeDirectoryListingDTO
  error?: unknown
}

/** 后端只回传去敏后的失败原因；单独展示它才能区分权限不足与目录不存在。 */
function directoryErrorDetail(error: unknown): string | null {
  return error instanceof ApiError && error.message.length > 0 ? error.message : null
}

interface DirectoryErrorProps {
  error: unknown
  label: string
  disabled: boolean
  onRetry?: () => void
  testId: string
}

/** 目录错误统一结构：先给出稳定的失败分类，再单独列出后端原因，不用括号拼接说明。 */
function DirectoryError({ error, label, disabled, onRetry, testId }: DirectoryErrorProps) {
  const { t } = useI18n()
  const detail = directoryErrorDetail(error)
  return (
    <div className="ide-directory-error" role="alert" data-testid={testId}>
      <span className="ide-directory-error-text">
        <span>{label}</span>
        {detail != null ? <span className="ide-directory-error-detail">{detail}</span> : null}
      </span>
      {onRetry != null ? (
        <Button variant="ghost" size="compact" disabled={disabled} onClick={onRetry}>
          {t('shared.retry')}
        </Button>
      ) : null}
    </div>
  )
}

interface DirectoryListProps {
  listing: EnvironmentIdeDirectoryListingDTO
  depth: number
  selected: string | null
  nodes: Record<string, DirectoryNodeState>
  disabled: boolean
  onSelect: (path: string) => void
  onToggle: (path: string) => void
  onRetry: (path: string) => void
}

/** 逐层渲染已加载的直接子目录；只有用户展开的目录才会请求下一层。 */
function DirectoryList({
  listing,
  depth,
  selected,
  nodes,
  disabled,
  onSelect,
  onToggle,
  onRetry,
}: DirectoryListProps) {
  const { t } = useI18n()

  return (
    <>
      {listing.children.length === 0 ? (
        <p className="ide-directory-empty" style={{ paddingLeft: depth * 16 + 28 }}>
          {t('ai.ide.directoryEmpty')}
        </p>
      ) : (
        <ul className="ide-directory-list">
          {listing.children.map((entry: EnvironmentIdeDirectoryListingEntry) => {
            const node = nodes[entry.path]
            const expanded = node != null
            return (
              <li key={entry.path}>
                <div
                  className="ide-directory-row"
                  data-selected={selected === entry.path}
                  style={{ paddingLeft: depth * 16 }}
                >
                  <button
                    type="button"
                    className="ide-directory-toggle"
                    aria-expanded={expanded}
                    aria-label={t(
                      expanded ? 'ai.ide.collapseDirectory' : 'ai.ide.expandDirectory',
                      { name: entry.name },
                    )}
                    disabled={disabled}
                    onClick={() => onToggle(entry.path)}
                    data-testid="ide-directory-toggle"
                  >
                    <ChevronRight
                      className="ide-directory-chevron"
                      data-expanded={expanded}
                      size={14}
                      aria-hidden="true"
                    />
                  </button>
                  <button
                    type="button"
                    className="ide-directory-entry"
                    aria-pressed={selected === entry.path}
                    title={entry.path}
                    disabled={disabled}
                    onClick={() => onSelect(entry.path)}
                    data-testid="ide-directory-entry"
                  >
                    <Folder size={14} aria-hidden="true" />
                    <span className="ide-directory-name">{entry.name}</span>
                  </button>
                </div>
                {node?.status === 'loading' ? (
                  <div
                    className="ide-directory-node-state"
                    style={{ paddingLeft: depth * 16 + 28 }}
                    role="status"
                    data-testid="ide-directory-node-loading"
                  >
                    <LoadingSpinner size={12} decorative />
                    <span>{t('ai.ide.loading')}</span>
                  </div>
                ) : null}
                {node?.status === 'error' ? (
                  <div
                    className="ide-directory-node-state is-error"
                    style={{ paddingLeft: depth * 16 + 28 }}
                  >
                    <DirectoryError
                      error={node.error}
                      label={t('ai.ide.directoryLoadFailed')}
                      disabled={disabled}
                      onRetry={() => onRetry(entry.path)}
                      testId="ide-directory-node-error"
                    />
                  </div>
                ) : null}
                {node?.status === 'ready' && node.listing != null ? (
                  <DirectoryList
                    listing={node.listing}
                    depth={depth + 1}
                    selected={selected}
                    nodes={nodes}
                    disabled={disabled}
                    onSelect={onSelect}
                    onToggle={onToggle}
                    onRetry={onRetry}
                  />
                ) : null}
              </li>
            )
          })}
        </ul>
      )}
      {listing.truncated ? (
        <p
          className="ide-directory-truncated"
          style={{ paddingLeft: depth * 16 + 28 }}
          role="status"
          data-testid="ide-directory-truncated"
        >
          {t('ai.ide.directoryTruncated', { count: listing.children.length })}
        </p>
      ) : null}
    </>
  )
}

/**
 * 宿主目录选择弹窗：从主目录开始逐级向上到根，展开时才加载直接子目录。
 * 每次换根都会递增代际，关闭、切换环境或重新换根后到达的响应一律不写入当前树。
 */
export function OpenDirectoryModal({
  environmentId,
  currentDirectory,
  recentDirectories,
  pending = false,
  error = null,
  onConfirm,
  onClose,
}: OpenDirectoryModalProps) {
  const { t } = useI18n()
  const [root, setRoot] = useState<EnvironmentIdeDirectoryListingDTO | null>(null)
  const [rootLoading, setRootLoading] = useState(true)
  const [rootError, setRootError] = useState<unknown>(null)
  const [nodes, setNodes] = useState<Record<string, DirectoryNodeState>>({})
  const [selected, setSelected] = useState<string | null>(currentDirectory || null)
  const [helpOpen, setHelpOpen] = useState(false)
  const helpId = `ide-directory-help-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
  const generationRef = useRef(0)
  const nodeTokensRef = useRef(new Map<string, number>())
  const signalRef = useRef<AbortSignal | undefined>(undefined)
  const pendingLoadRef = useRef<{ path: string | null; preferred: string | null }>({
    path: null,
    preferred: currentDirectory || null,
  })

  const loadRoot = useCallback(async (path: string | null, preferred: string | null) => {
    const generation = generationRef.current + 1
    generationRef.current = generation
    pendingLoadRef.current = { path, preferred }
    setRootLoading(true)
    setRootError(null)
    try {
      const listing = await environmentService.listIdeDirectories(
        environmentId,
        path,
        signalRef.current,
      )
      if (generationRef.current !== generation) {
        return
      }
      nodeTokensRef.current = new Map()
      setNodes({})
      setRoot(listing)
      setSelected(preferred ?? listing.path)
    } catch (loadError) {
      if (generationRef.current !== generation) {
        return
      }
      setRootError(loadError)
    } finally {
      if (generationRef.current === generation) {
        setRootLoading(false)
      }
    }
  }, [environmentId])

  const loadDirectory = useCallback(async (path: string) => {
    const generation = generationRef.current
    const token = (nodeTokensRef.current.get(path) ?? 0) + 1
    nodeTokensRef.current.set(path, token)
    setNodes((current) => ({ ...current, [path]: { status: 'loading' } }))
    try {
      const listing = await environmentService.listIdeDirectories(
        environmentId,
        path,
        signalRef.current,
      )
      if (generationRef.current !== generation || nodeTokensRef.current.get(path) !== token) {
        return
      }
      setNodes((current) => ({ ...current, [path]: { status: 'ready', listing } }))
    } catch (loadError) {
      if (generationRef.current !== generation || nodeTokensRef.current.get(path) !== token) {
        return
      }
      setNodes((current) => ({ ...current, [path]: { status: 'error', error: loadError } }))
    }
  }, [environmentId])

  useEffect(() => {
    const controller = new AbortController()
    signalRef.current = controller.signal
    void loadRoot(null, pendingLoadRef.current.preferred)
    return () => {
      controller.abort()
      signalRef.current = undefined
      generationRef.current += 1
    }
  }, [loadRoot])

  const directed = pending || rootLoading

  function toggleDirectory(path: string) {
    if (nodes[path] == null) {
      void loadDirectory(path)
      return
    }
    nodeTokensRef.current.set(path, (nodeTokensRef.current.get(path) ?? 0) + 1)
    setNodes((current) => {
      const remaining = { ...current }
      delete remaining[path]
      return remaining
    })
  }

  return (
    <Dialog
      className="ide-directory-modal"
      title={t('ai.ide.openDirectoryTitle')}
      pending={pending}
      onClose={onClose}
      initialFocusSelector="[data-testid='ide-directory-tree']"
    >
      <div className="modal-body ide-directory-body">
        <div className="ide-directory-toolbar">
          <Button
            variant="ghost"
            size="compact"
            className="ide-directory-up"
            disabled={directed || root?.parentPath == null}
            title={root?.parentPath ?? undefined}
            onClick={() => {
              if (root?.parentPath != null) {
                void loadRoot(root.parentPath, null)
              }
            }}
            data-testid="ide-directory-up"
          >
            <CornerLeftUp size={14} aria-hidden="true" />
            <span>{t('ai.ide.parentDirectory')}</span>
          </Button>
          {root != null ? (
            <span
              className="ide-directory-location"
              title={root.path}
              data-testid="ide-directory-location"
            >
              {root.path}
            </span>
          ) : null}
          {rootLoading ? <LoadingSpinner size={12} decorative /> : null}
          <button
            type="button"
            className="ide-directory-help"
            aria-expanded={helpOpen}
            aria-controls={helpId}
            aria-label={t('ai.ide.directoryHelpLabel')}
            title={t('ai.ide.directoryHelpLabel')}
            onClick={() => setHelpOpen((current) => !current)}
            data-testid="ide-directory-help"
          >
            <HelpCircle size={14} aria-hidden="true" />
          </button>
        </div>

        {helpOpen ? (
          <p className="ide-directory-hint" id={helpId} role="note">
            {t('ai.ide.directoryHelp')}
          </p>
        ) : null}

        {recentDirectories.length > 0 ? (
          <section className="ide-directory-recent">
            <h3 className="ide-directory-section-title">{t('ai.ide.recentDirectories')}</h3>
            <ul className="ide-directory-recent-list">
              {recentDirectories.map((path) => (
                <li key={path}>
                  <Button
                    variant="ghost"
                    size="compact"
                    className="ide-directory-recent-item"
                    title={path}
                    disabled={directed}
                    onClick={() => { void loadRoot(path, path) }}
                    data-testid="ide-recent-directory"
                  >
                    {path}
                  </Button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {rootError != null ? (
          <DirectoryError
            error={rootError}
            label={t('ai.ide.directoryLoadFailed')}
            disabled={pending}
            onRetry={() => {
              void loadRoot(pendingLoadRef.current.path, pendingLoadRef.current.preferred)
            }}
            testId="ide-directory-load-error"
          />
        ) : null}

        <section
          className="ide-directory-tree"
          tabIndex={-1}
          aria-label={t('ai.ide.directoryTree')}
          data-testid="ide-directory-tree"
        >
          {root == null ? (
            rootLoading ? (
              <div className="ide-directory-node-state" role="status">
                <LoadingSpinner size={12} decorative />
                <span>{t('ai.ide.loading')}</span>
              </div>
            ) : null
          ) : (
            <DirectoryList
              listing={root}
              depth={0}
              selected={selected}
              nodes={nodes}
              disabled={directed}
              onSelect={setSelected}
              onToggle={toggleDirectory}
              onRetry={(path) => { void loadDirectory(path) }}
            />
          )}
        </section>

        {error ? (
          <div className="ide-directory-error" role="alert" data-testid="ide-directory-error">
            <span className="ide-directory-error-text">{error}</span>
          </div>
        ) : null}
      </div>
      <div className="modal-footer">
        <span className="ide-directory-selection" title={selected ?? undefined}>
          {selected != null ? `${t('ai.ide.selectedDirectory')} ${selected}` : null}
        </span>
        <Button variant="ghost" disabled={pending} onClick={onClose}>
          {t('ai.ide.cancel')}
        </Button>
        <Button
          disabled={directed || selected == null}
          data-testid="ide-directory-confirm"
          onClick={() => {
            if (selected != null) {
              onConfirm(selected)
            }
          }}
        >
          {t('ai.ide.confirm')}
        </Button>
      </div>
    </Dialog>
  )
}
