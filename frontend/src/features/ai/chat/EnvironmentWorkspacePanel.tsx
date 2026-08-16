import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { CornerUpLeft, FolderOpen, RefreshCw, RotateCcw } from 'lucide-react'
import { ThreadInteractionPanel } from '@/features/ai/runtime/thread-panel/ThreadInteractionPanel'
import { filterReadyEnvironments } from '@/features/ai/environment/environment-utils'
import { environmentService } from '@/shared/api/environment-service'
import type {
  EnvironmentBindingDTO,
  LiveEnvironmentDTO,
} from '@/shared/api/contracts/ai-environment'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/** “无环境”选项的固定 id（None）。 */
const NONE_ID = ''

/**
 * 内联 Environment Workspace 目录 picker（单一可复用组件）。
 *
 * 两个模式：
 * - 列表模式（未绑定）：展示 READY Environment 列表（含“无环境”）；选择某个
 *   Environment 后进入目录模式（同名时从当前 binding path 开始，否则从 root
 *   `'.'` 开始）；选择“无环境”直接提交 null。
 * - 目录模式（已绑定或已选择）：单层目录浏览（进入直属子目录 / 上一级 / 刷新 /
 *   返回 Environment 列表 / 确认“使用当前 Workspace”）。
 *
 * 目录 UI 只依赖安全 wire path（{@code EnvironmentDirectoryDTO.path/parentPath/entries[].path}）；
 * {@code displayPath} 已冻结为当前 path 的最后一段（root 为 {@code '.'}），绝不当绝对路径使用。
 * Escape/关闭只调用 onClose，绝不调用 onSelect。
 *
 * 键盘约定：Arrow/Enter 导航只挂在 listbox（{@code <ul role="listbox">}）作用域上；action
 * buttons（Up/Refresh/Back/Confirm）保留各自的 Enter/Space 语义，不会被 section 级 handler 劫持。
 * 目录模式额外支持 Backspace 回退上一级，以及 Ctrl/Cmd+Enter：有高亮子目录时选中该目录，
 * 空目录则确认当前路径。
 */
export function EnvironmentWorkspacePanel({
  environments,
  current,
  pending = false,
  onSelect,
  onClose,
}: {
  environments: LiveEnvironmentDTO[]
  /** 当前 binding；null 表示未绑定。 */
  current: EnvironmentBindingDTO | null
  /** 外层 first-send/mutation 进行中：禁用全部选择/确认操作。 */
  pending?: boolean
  /** 提交完整 binding（{name, workspacePath}）或 null（无环境）。 */
  onSelect: (binding: EnvironmentBindingDTO | null) => void | Promise<void>
  onClose: () => void
}) {
  const { t } = useI18n()
  const [browse, setBrowse] = useState<{ name: string; path: string } | null>(null)
  const initializedRef = useRef(false)
  const listRef = useRef<HTMLUListElement>(null)
  const directoryRef = useRef<HTMLUListElement>(null)
  const [activeId, setActiveId] = useState<string | null>(null)

  // 初次打开：已有 binding 时直接浏览该 environment/path；未绑定时留在列表模式。
  useEffect(() => {
    if (initializedRef.current) {
      return
    }
    initializedRef.current = true
    if (current != null) {
      setBrowse({ name: current.name, path: current.workspacePath })
    }
  }, [current])

  const directoryQuery = useQuery({
    queryKey: queryKeys.environments.directory(browse?.name ?? '', browse?.path ?? '.'),
    queryFn: () => environmentService.listDirectories(browse!.name, browse!.path),
    enabled: browse != null,
    retry: false,
  })
  const data =
    browse != null && directoryQuery.data?.path === browse.path
      ? directoryQuery.data
      : undefined
  const invalidResponse =
    browse != null
    && directoryQuery.data != null
    && directoryQuery.data.path !== browse.path
  const error = directoryQuery.error

  const readyEnvironments = useMemo(
    () => filterReadyEnvironments(environments),
    [environments],
  )
  const listItems = useMemo(
    () => [
      { id: NONE_ID, title: t('ai.chat.noneEnvironment') },
      ...readyEnvironments.map((environment) => ({
        id: environment.name,
        title: environment.name,
      })),
    ],
    [readyEnvironments, t],
  )
  const directoryTitle = browse != null
    ? t('ai.chat.workspace.directoryTitle', { name: browse.name })
    : t('ai.chat.selectEnvironment')
  const busy = pending || directoryQuery.isFetching

  // 列表模式：active 必须确定——当前 binding name（若在列表中）否则“无环境”。
  useEffect(() => {
    if (browse != null) {
      return
    }
    setActiveId((currentActive) => {
      if (currentActive != null && listItems.some((item) => item.id === currentActive)) {
        return currentActive
      }
      return current != null && listItems.some((item) => item.id === current.name)
        ? current.name
        : NONE_ID
    })
  }, [browse, current, listItems])

  // 目录模式：数据就绪后 active 指向第一个条目；数据变化时重置。
  useEffect(() => {
    if (browse == null) {
      return
    }
    setActiveId((currentActive) => {
      if (currentActive != null && data?.entries.some((entry) => entry.path === currentActive)) {
        return currentActive
      }
      return data?.entries[0]?.path ?? null
    })
  }, [browse, data])

  // 切换模式/目录后聚焦对应 listbox，保证键盘可达。
  useEffect(() => {
    if (browse == null) {
      listRef.current?.focus({ preventScroll: true })
      return
    }
    directoryRef.current?.focus({ preventScroll: true })
  }, [browse])

  useEffect(() => {
    const container = browse == null ? listRef.current : directoryRef.current
    const active = container?.querySelector<HTMLElement>('[aria-selected="true"]')
    active?.scrollIntoView({ block: 'nearest' })
  }, [activeId, browse])

  function selectEnvironment(environmentName: string | null) {
    if (pending) {
      return
    }
    if (environmentName == null) {
      void onSelect(null)
      return
    }
    // 同名时从当前 binding path 继续，否则从 root '.' 开始目录浏览。
    const startPath =
      current?.name === environmentName && current.workspacePath.trim()
        ? current.workspacePath
        : '.'
    setBrowse({ name: environmentName, path: startPath })
  }

  function enterDirectory(path: string) {
    if (busy || browse == null || !path.trim()) {
      return
    }
    setBrowse({ name: browse.name, path })
  }

  /**
   * canonical 相对路径的 lexical 父路径：'a/b' -> 'a'，'a' -> '.'，'.'/' 空 -> null（已是 root）。
   * 不依赖目录查询结果，因此 404/409/其他错误下仍可按安全 wire path 向上恢复。
   */
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
    if (busy || browse == null) {
      return
    }
    const parent = parentPathOf(browse.path)
    if (parent != null) {
      setBrowse({ name: browse.name, path: parent })
    }
  }

  function refresh() {
    if (busy || browse == null) {
      return
    }
    void directoryQuery.refetch()
  }

  function backToEnvironments() {
    if (pending) {
      return
    }
    setActiveId(
      current != null && readyEnvironments.some((environment) => environment.name === current.name)
        ? current.name
        : NONE_ID,
    )
    setBrowse(null)
  }

  /** 只有成功加载且当前 path 有效的目录才允许确认；missing/error 时禁用。 */
  function canConfirm(): boolean {
    return browse != null
      && data != null
      && error == null
      && !invalidResponse
      && !busy
      && Boolean(browse.path.trim())
  }

  function confirmDirectory() {
    if (!canConfirm() || browse == null || data == null) {
      return
    }
    // 只有响应 path 与请求 path 精确一致时才会到达这里。
    void onSelect({ name: browse.name, workspacePath: data.path })
  }

  /** Ctrl/Cmd+Enter：高亮子目录则选中该路径；空目录或无高亮则确认当前路径。 */
  function confirmActiveOrCurrent() {
    if (!canConfirm() || browse == null || data == null) {
      return
    }
    if (data.entries.length === 0) {
      confirmDirectory()
      return
    }
    if (activeId != null && data.entries.some((entry) => entry.path === activeId)) {
      void onSelect({ name: browse.name, workspacePath: activeId })
      return
    }
    confirmDirectory()
  }

  function isConfirmShortcut(event: React.KeyboardEvent<HTMLElement>): boolean {
    return event.key === 'Enter' && (event.ctrlKey || event.metaKey)
  }

  /** section 级处理 Escape、目录模式 Backspace / Ctrl+Enter；Arrow/Enter 仍由 listbox 处理。 */
  function handleSectionKeyDown(event: React.KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
      return
    }
    if (browse == null) {
      return
    }
    if (event.key === 'Backspace') {
      event.preventDefault()
      event.stopPropagation()
      goUp()
      return
    }
    if (isConfirmShortcut(event)) {
      event.preventDefault()
      event.stopPropagation()
      confirmActiveOrCurrent()
    }
  }

  function handleListKeyDown(event: React.KeyboardEvent<HTMLUListElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      event.stopPropagation()
      moveListActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      event.stopPropagation()
      selectActiveEnvironment()
    }
  }

  function handleDirectoryKeyDown(event: React.KeyboardEvent<HTMLUListElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      event.stopPropagation()
      moveDirectoryActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (isConfirmShortcut(event)) {
      event.preventDefault()
      event.stopPropagation()
      confirmActiveOrCurrent()
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      event.stopPropagation()
      if (activeId != null && data?.entries.some((entry) => entry.path === activeId)) {
        enterDirectory(activeId)
      }
      return
    }
    if (event.key === 'Backspace') {
      event.preventDefault()
      event.stopPropagation()
      goUp()
    }
  }

  function moveListActive(delta: number) {
    if (listItems.length === 0) {
      return
    }
    if (activeId == null) {
      // 从未选中的 null 出发：ArrowDown 落在首项（“无环境”），ArrowUp 环绕到末项。
      setActiveId(delta > 0 ? listItems[0]!.id : listItems[listItems.length - 1]!.id)
      return
    }
    const currentIndex = Math.max(0, listItems.findIndex((item) => item.id === activeId))
    const nextIndex = (currentIndex + delta + listItems.length) % listItems.length
    setActiveId(listItems[nextIndex]?.id ?? null)
  }

  function selectActiveEnvironment() {
    if (pending || activeId == null) {
      return
    }
    selectEnvironment(activeId === NONE_ID ? null : activeId)
  }

  function moveDirectoryActive(delta: number) {
    if (data == null || data.entries.length === 0) {
      return
    }
    if (activeId == null) {
      setActiveId(delta > 0 ? data.entries[0]!.path : data.entries[data.entries.length - 1]!.path)
      return
    }
    const currentIndex = Math.max(
      0,
      data.entries.findIndex((entry) => entry.path === activeId),
    )
    const nextIndex = (currentIndex + delta + data.entries.length) % data.entries.length
    setActiveId(data.entries[nextIndex]?.path ?? null)
  }

  return (
    <ThreadInteractionPanel
      title={directoryTitle}
      className="thread-selection-panel environment-workspace-panel"
      bodyClassName="thread-selection-body"
      busy={busy}
      onClose={onClose}
      onKeyDown={handleSectionKeyDown}
    >
      {browse == null ? (
        <ul
          ref={listRef}
          className="thread-selection-list environment-workspace-list"
          role="listbox"
          tabIndex={-1}
          aria-label={t('ai.chat.selection.options', { title: t('ai.chat.selectEnvironment') })}
          onKeyDown={handleListKeyDown}
        >
          {listItems.length === 0 ? (
            <li className="thread-selection-empty">{t('ai.chat.noEnvironments')}</li>
          ) : null}
          {listItems.map((item) => {
            const active = item.id === activeId
            // 未绑定时“无环境”即当前值；已绑定时 current 落在同名校上。
            const currentItem = current == null
              ? item.id === NONE_ID
              : item.id === current.name
            return (
              <li key={item.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={active}
                  aria-current={currentItem ? 'true' : undefined}
                  className={[
                    'thread-selection-item',
                    active ? 'active' : '',
                    currentItem ? 'current' : '',
                  ].filter(Boolean).join(' ')}
                  disabled={pending}
                  onFocus={() => setActiveId(item.id)}
                  onMouseMove={() => setActiveId(item.id)}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => selectEnvironment(item.id === NONE_ID ? null : item.id)}
                >
                  <span className="thread-selection-item-title">
                    <span>{item.title}</span>
                    {currentItem ? <em>{t('ai.chat.selected')}</em> : null}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
      ) : (
        <div className="environment-workspace-body">
          <div className="environment-workspace-meta">
            {/* 展示请求的完整安全 wire path；displayPath 只是末段展示名，绝不当完整当前路径。 */}
            <span className="environment-workspace-path">
              {t('ai.chat.workspace.currentPath', { path: browse.path })}
            </span>
            {error == null && !invalidResponse && data?.gitBranch ? (
              <span className="environment-workspace-branch">
                {t('ai.chat.workspace.gitBranch', { branch: data.gitBranch })}
              </span>
            ) : null}
          </div>
          <ul
            ref={directoryRef}
            className="thread-selection-list environment-workspace-entries"
            role="listbox"
            tabIndex={-1}
            aria-label={t('ai.chat.workspace.entries')}
            onKeyDown={handleDirectoryKeyDown}
          >
            {directoryQuery.isLoading ? (
              <li className="thread-selection-empty">{t('ai.chat.workspace.loading')}</li>
            ) : null}
            {error != null || invalidResponse ? (
              <li className="thread-selection-empty environment-workspace-error" role="alert">
                <span>{t('ai.chat.workspace.loadFailed')}</span>
                {error != null ? (
                  <span>{error instanceof Error ? error.message : String(error)}</span>
                ) : null}
              </li>
            ) : null}
            {!directoryQuery.isLoading
            && error == null
            && !invalidResponse
            && data != null
            && data.entries.length === 0 ? (
              <li className="thread-selection-empty">{t('ai.chat.workspace.empty')}</li>
            ) : null}
            {!directoryQuery.isLoading && error == null && !invalidResponse
              ? data?.entries.map((entry) => {
                const active = entry.path === activeId
                return (
                  <li key={entry.path}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={active}
                      aria-label={t('ai.chat.workspace.enterDirectory', { name: entry.name })}
                      className={['thread-selection-item', active ? 'active' : ''].filter(Boolean).join(' ')}
                      disabled={busy}
                      onFocus={() => setActiveId(entry.path)}
                      onMouseMove={() => setActiveId(entry.path)}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => enterDirectory(entry.path)}
                    >
                      <FolderOpen aria-hidden="true" />
                      <span className="thread-selection-item-title">
                        <span>{entry.name}</span>
                      </span>
                    </button>
                  </li>
                )
              })
              : null}
          </ul>
          {error == null && !invalidResponse && data?.truncated ? (
            <p className="environment-workspace-notice">{t('ai.chat.workspace.truncated')}</p>
          ) : null}
          <div className="environment-workspace-actions">
            <button
              type="button"
              className="thread-selection-action"
              title={t('ai.chat.workspace.upTitle')}
              disabled={busy || browse == null || parentPathOf(browse.path) == null}
              onMouseDown={(event) => event.preventDefault()}
              onClick={goUp}
            >
              <CornerUpLeft aria-hidden="true" />
              {t('ai.chat.workspace.up')}
            </button>
            <button
              type="button"
              className="thread-selection-action"
              disabled={busy}
              onMouseDown={(event) => event.preventDefault()}
              onClick={refresh}
            >
              <RefreshCw aria-hidden="true" />
              {t('ai.chat.workspace.refresh')}
            </button>
            <button
              type="button"
              className="thread-selection-action"
              disabled={pending}
              onMouseDown={(event) => event.preventDefault()}
              onClick={backToEnvironments}
            >
              <RotateCcw aria-hidden="true" />
              {t('ai.chat.workspace.backToEnvironments')}
            </button>
            <button
              type="button"
              className="thread-selection-action primary"
              disabled={!canConfirm()}
              title={canConfirm() ? undefined : t('ai.chat.workspace.confirmDisabled')}
              onMouseDown={(event) => event.preventDefault()}
              onClick={confirmDirectory}
            >
              {t('ai.chat.workspace.confirm')}
            </button>
          </div>
        </div>
      )}
    </ThreadInteractionPanel>
  )
}
