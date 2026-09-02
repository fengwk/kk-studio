import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { CornerUpLeft, FolderOpen, RefreshCw, X } from 'lucide-react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/**
 * 内联 Environment Workspace 目录 picker。
 *
 * 由当前选中 Agent 绑定的单一 Environment Card + 当前 workspacePath 驱动：
 * - 如果 Agent 未绑定 Environment，提示无法浏览工作区；
 * - 如果已绑定，以 Card UUID (environment.id) 浏览 Environment Root 下的单层目录；
 * - 提交/清除仅针对 workspacePath（string | null），绝不在 wire 传输 Environment 身份。
 */
export function EnvironmentWorkspacePanel({
  environment,
  current,
  pending = false,
  onSelect,
  onClose,
}: {
  /** 当前 Agent 绑定的 Environment Card（无环境时为 null/undefined）。 */
  environment?: EnvironmentCardDTO | null
  /** 当前 workspacePath；null 表示未绑定工作目录。 */
  current: string | null
  /** 外层 first-send/mutation 进行中：禁用全部选择/确认操作。 */
  pending?: boolean
  /** 提交 workspacePath 或 null（清除工作目录）。 */
  onSelect: (workspacePath: string | null) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  const [browsePath, setBrowsePath] = useState<string>(current && current.trim() ? current.trim() : '.')
  const directoryRef = useRef<HTMLUListElement>(null)
  const [activePath, setActivePath] = useState<string | null>(null)

  const environmentId = environment?.id ?? ''

  const directoryQuery = useQuery({
    queryKey: queryKeys.environments.directory(environmentId, browsePath),
    queryFn: () => environmentService.listDirectories(environmentId, browsePath),
    enabled: Boolean(environmentId),
    retry: false,
  })

  const data =
    directoryQuery.data?.path === browsePath
      ? directoryQuery.data
      : undefined

  const error = directoryQuery.error
  const busy = pending || directoryQuery.isFetching

  // 数据就绪后 active 指向第一个条目；数据变化时重置。
  useEffect(() => {
    setActivePath((currentActive) => {
      if (currentActive != null && data?.entries.some((entry) => entry.path === currentActive)) {
        return currentActive
      }
      return data?.entries[0]?.path ?? null
    })
  }, [data])

  useEffect(() => {
    directoryRef.current?.focus({ preventScroll: true })
  }, [browsePath])

  useEffect(() => {
    const active = directoryRef.current?.querySelector<HTMLElement>('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [activePath])

  function parentPathOf(path: string): string | null {
    if (path === '.' || path === '') {
      return null
    }
    const slash = path.lastIndexOf('/')
    if (slash <= 0) {
      return '.'
    }
    return path.slice(0, slash)
  }

  function goUp() {
    if (busy) return
    const parent = parentPathOf(browsePath)
    if (parent != null) {
      setBrowsePath(parent)
    }
  }

  function refresh() {
    if (busy) return
    void directoryQuery.refetch()
  }

  function selectCurrentPath() {
    if (busy || !environment) return
    void onSelect(browsePath)
  }

  function clearWorkspace() {
    if (busy) return
    void onSelect(null)
  }

  function enterDirectory(path: string) {
    if (busy || !path.trim()) return
    setBrowsePath(path)
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLUListElement>) {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      if (!data?.entries.length) return
      const currentIndex = data.entries.findIndex((e) => e.path === activePath)
      const nextIndex = currentIndex < 0 || currentIndex >= data.entries.length - 1 ? 0 : currentIndex + 1
      setActivePath(data.entries[nextIndex]?.path ?? null)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (!data?.entries.length) return
      const currentIndex = data.entries.findIndex((e) => e.path === activePath)
      const nextIndex = currentIndex <= 0 ? data.entries.length - 1 : currentIndex - 1
      setActivePath(data.entries[nextIndex]?.path ?? null)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      if (event.ctrlKey || event.metaKey) {
        if (activePath) {
          void onSelect(activePath)
        } else {
          selectCurrentPath()
        }
      } else if (activePath) {
        enterDirectory(activePath)
      }
    } else if (event.key === 'Backspace') {
      event.preventDefault()
      goUp()
    }
  }

  if (!environment) {
    return (
      <ThreadInteractionPanel title={t('ai.chat.environment')} onClose={onClose}>
        <div className="env-picker-panel">
          <div className="inline-hint" style={{ padding: '16px 0' }}>
            {t('ai.chat.noEnvironmentBound')}
          </div>
          {current != null && (
            <div className="env-picker-foot" style={{ marginTop: 12 }}>
              <button
                type="button"
                className="ghost-btn danger"
                onClick={clearWorkspace}
                disabled={pending}
              >
                {t('ai.chat.clearWorkspacePath')}
              </button>
            </div>
          )}
        </div>
      </ThreadInteractionPanel>
    )
  }

  const parent = parentPathOf(browsePath)
  const isRoot = browsePath === '.'

  return (
    <ThreadInteractionPanel
      title={t('ai.chat.workspace.directoryTitle', { name: environment.name })}
      onClose={onClose}
    >
      <div className="env-picker-panel">
        <div className="env-picker-nav">
          <div className="env-picker-path" title={browsePath}>
            <span className="env-picker-path-root">{environment.name}:</span>
            <span className="env-picker-path-text">{isRoot ? '/' : `/${browsePath}`}</span>
          </div>
          <div className="env-picker-actions">
            <button
              type="button"
              className="ghost-btn icon-only"
              title={t('ai.chat.workspace.up')}
              aria-label={t('ai.chat.workspace.up')}
              onClick={goUp}
              disabled={busy || parent == null}
            >
              <CornerUpLeft size={16} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="ghost-btn icon-only"
              title={t('ai.chat.workspace.refresh')}
              aria-label={t('ai.chat.workspace.refresh')}
              onClick={refresh}
              disabled={busy}
            >
              <RefreshCw size={16} className={directoryQuery.isFetching ? 'spin' : ''} aria-hidden="true" />
            </button>
          </div>
        </div>

        {directoryQuery.isLoading && (
          <div className="env-picker-status" role="status">
            {t('ai.chat.workspace.loading')}
          </div>
        )}

        {error && (
          <div className="env-picker-error" role="alert">
            <p>{error instanceof Error ? error.message : String(error)}</p>
            {parent != null && (
              <button type="button" className="ghost-btn" onClick={goUp}>
                {t('ai.chat.workspace.up')}
              </button>
            )}
          </div>
        )}

        {!directoryQuery.isLoading && !error && (
          <ul
            ref={directoryRef}
            className="env-picker-list"
            role="listbox"
            tabIndex={0}
            aria-label={t('ai.chat.workspace.entries')}
            onKeyDown={handleKeyDown}
          >
            {data?.entries.length === 0 ? (
              <li className="env-picker-empty">{t('ai.chat.workspace.empty')}</li>
            ) : (
              data?.entries.map((entry) => {
                const isSelected = entry.path === activePath
                return (
                  <li
                    key={entry.path}
                    role="option"
                    aria-selected={isSelected}
                    className={`env-picker-item${isSelected ? ' is-active' : ''}`}
                    onClick={() => enterDirectory(entry.path)}
                    onMouseEnter={() => setActivePath(entry.path)}
                  >
                    <FolderOpen size={16} className="env-picker-item-icon" aria-hidden="true" />
                    <span className="env-picker-item-name">{entry.name}</span>
                  </li>
                )
              })
            )}
          </ul>
        )}

        <div className="env-picker-foot">
          {current != null && (
            <button
              type="button"
              className="ghost-btn"
              onClick={clearWorkspace}
              disabled={busy}
            >
              <X size={14} aria-hidden="true" style={{ marginRight: 4 }} />
              {t('ai.chat.clearWorkspacePath')}
            </button>
          )}
          <button
            type="button"
            className="btn-primary"
            onClick={selectCurrentPath}
            disabled={busy || Boolean(error)}
          >
            {t('ai.chat.workspace.confirm')}
          </button>
        </div>
      </div>
    </ThreadInteractionPanel>
  )
}
