import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO, IdeViewDTO } from '@/shared/api/contracts/ai-environment'
import { OpenDirectoryModal } from '@/features/ai/ide/OpenDirectoryModal'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { useI18n } from '@/shared/i18n'
import { validateIdeEntryUrl } from '@/features/ai/ide/ide-url'
import '@/features/ai/ide/ide-overlay-panel.css'

export interface ResolvedEnvironmentInfo {
  id: string
  name: string
  ready: boolean
  homeDirectory?: string | null
}

export interface IdeCommandAction {
  id: number
  mode: 'files' | 'shell'
}

export interface IdeOverlayPanelProps {
  isOpen: boolean
  viewId: string
  environment: ResolvedEnvironmentInfo | null
  availableEnvironments: EnvironmentCardDTO[]
  action?: IdeCommandAction | null
  initialMode?: 'files' | 'shell'
  isNarrow?: boolean
  onCollapse: () => void
  onClose: () => void
  onRestoreFocus?: () => void
  onIdeMountedChange?: (mounted: boolean) => void
}

export function IdeOverlayPanel({
  isOpen,
  viewId,
  environment,
  availableEnvironments,
  action,
  initialMode = 'files',
  isNarrow = false,
  onCollapse,
  onClose,
  onRestoreFocus,
  onIdeMountedChange,
}: IdeOverlayPanelProps) {
  const { t } = useI18n()
  const [view, setView] = useState<IdeViewDTO | null>(null)
  const [status, setStatus] = useState<'idle' | 'opening' | 'ready' | 'error' | 'offline' | 'select-environment'>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [terminalError, setTerminalError] = useState<string | null>(null)
  const [selectedEnvId, setSelectedEnvId] = useState<string>('')
  const [isDirectoryModalOpen, setIsDirectoryModalOpen] = useState(false)
  const [directoryPending, setDirectoryPending] = useState(false)
  const [directoryError, setDirectoryError] = useState<string | null>(null)
  const [isIframeLoaded, setIsIframeLoaded] = useState(false)
  const [confirmSwitchEnvOpen, setConfirmSwitchEnvOpen] = useState(false)
  const [pendingSwitchEnv, setPendingSwitchEnv] = useState<ResolvedEnvironmentInfo | null>(null)

  const activeViewRef = useRef<IdeViewDTO | null>(null)
  useEffect(() => {
    activeViewRef.current = view
  }, [view])

  const onIdeMountedChangeRef = useRef(onIdeMountedChange)
  useEffect(() => {
    onIdeMountedChangeRef.current = onIdeMountedChange
  }, [onIdeMountedChange])

  // 并发控制与生命周期：
  // inFlightRef 防止同一组件重复发起并发创建；
  // activeSessionIdRef 仅在用户显式关闭或切换环境时递增，支持 React StrictMode 双重挂载
  const inFlightRef = useRef(false)
  const activeSessionIdRef = useRef(0)
  const mountedRef = useRef(true)
  const lastHandledActionIdRef = useRef(0)
  const pendingShellActionRef = useRef<number | null>(null)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  // 浏览器原生 beforeunload 守卫：存在活跃视图时提示用户可能丢失未保存修改
  useEffect(() => {
    if (view == null) return
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => {
      window.removeEventListener('beforeunload', handleBeforeUnload)
    }
  }, [view])

  // 打开 IDE 视图的核心动作：
  // 缺目录时服务端取 HOME 校验；初次 POST 响应校验 entryUrl 安全契约；
  // 单飞锁与会话保护，若真实卸载或会话改变则关闭新建视图防止泄漏。
  const startOpenView = useCallback(
    async (targetEnv: ResolvedEnvironmentInfo) => {
      if (inFlightRef.current) {
        return
      }
      if (!targetEnv.ready) {
        setStatus('offline')
        return
      }
      const sessionId = activeSessionIdRef.current
      inFlightRef.current = true
      setStatus('opening')
      setErrorMessage(null)
      try {
        const res = await environmentService.createIdeView(targetEnv.id, { viewId })
        // 会话已显式关闭或切换：关闭新创建的视图，避免后端资源泄漏
        if (activeSessionIdRef.current !== sessionId) {
          void Promise.resolve(environmentService.closeIdeView(targetEnv.id, res.viewId)).catch(() => {})
          return
        }
        // 组件已真实卸载（非 StrictMode 期间重挂载）：关闭新建视图
        if (!mountedRef.current) {
          void Promise.resolve(environmentService.closeIdeView(targetEnv.id, res.viewId)).catch(() => {})
          return
        }
        validateIdeEntryUrl(res.entryUrl)
        setView(res)
        setStatus('ready')
        onIdeMountedChangeRef.current?.(true)
      } catch (err: unknown) {
        if (activeSessionIdRef.current !== sessionId || !mountedRef.current) {
          return
        }
        setStatus('error')
        setErrorMessage(err instanceof Error ? err.message : t('ai.ide.error'))
      } finally {
        inFlightRef.current = false
      }
    },
    [viewId, t],
  )

  // iframe 加载完成处理：通知 loaded，并消费 pending 的 shell terminal 动作
  const handleIframeLoaded = useCallback(() => {
    setIsIframeLoaded(true)
    const active = activeViewRef.current
    if (active && pendingShellActionRef.current != null) {
      pendingShellActionRef.current = null
      void Promise.resolve(environmentService.openIdeViewTerminal(active.environmentId, viewId)).catch((err) => {
        setTerminalError(err instanceof Error ? err.message : t('ai.ide.terminalFailed'))
      })
    }
  }, [viewId, t])

  // 处理显式命令动作序列（来自 AgentPane onOpenIde）
  useEffect(() => {
    if (!action || action.id <= lastHandledActionIdRef.current) {
      return
    }
    lastHandledActionIdRef.current = action.id

    if (action.mode === 'shell') {
      if (view != null && isIframeLoaded) {
        // 视图已存在且 iframe 已就绪：唤起终端
        void Promise.resolve(environmentService.openIdeViewTerminal(view.environmentId, viewId)).catch((err) => {
          setTerminalError(err instanceof Error ? err.message : t('ai.ide.terminalFailed'))
        })
      } else {
        // 首次打开或 iframe 尚未就绪：等待 iframe 真正 load 后由 handleIframeLoaded 消费
        pendingShellActionRef.current = action.id
        if (view == null) {
          if (environment?.ready) {
            void startOpenView(environment)
          } else if (environment != null && !environment.ready) {
            setStatus('offline')
          } else {
            setStatus('select-environment')
            const firstReady = availableEnvironments.find((e) => e.ready) ?? availableEnvironments[0]
            if (firstReady) {
              setSelectedEnvId(firstReady.id)
            }
          }
        }
      }
    } else if (action.mode === 'files') {
      if (view == null) {
        if (environment?.ready) {
          void startOpenView(environment)
        } else if (environment != null && !environment.ready) {
          setStatus('offline')
        } else {
          setStatus('select-environment')
          const firstReady = availableEnvironments.find((e) => e.ready) ?? availableEnvironments[0]
          if (firstReady) {
            setSelectedEnvId(firstReady.id)
          }
        }
      }
    }
  }, [action, view, isIframeLoaded, environment, availableEnvironments, viewId, startOpenView, t])

  // 无显式 action 时的兜底打开与环境选择状态处理
  useEffect(() => {
    if (!isOpen) {
      return
    }
    if (view != null) {
      return
    }
    if (action != null && action.id > 0) {
      return
    }

    if (environment != null) {
      if (environment.ready) {
        if (initialMode === 'shell') {
          pendingShellActionRef.current = -1
        }
        void startOpenView(environment)
      } else {
        setStatus('offline')
      }
    } else {
      setStatus('select-environment')
      const firstReady = availableEnvironments.find((e) => e.ready) ?? availableEnvironments[0]
      if (firstReady) {
        setSelectedEnvId(firstReady.id)
      }
    }
  }, [isOpen, view, environment, availableEnvironments, action, initialMode, startOpenView])

  // 组件完全卸载时释放活跃 view
  useEffect(() => {
    return () => {
      const active = activeViewRef.current
      if (active) {
        void Promise.resolve(environmentService.closeIdeView(active.environmentId, active.viewId)).catch(() => {})
      }
    }
  }, [])

  // 收起：隐藏并保留 DOM、iframe 与进程
  const handleCollapse = useCallback(() => {
    onCollapse()
    onRestoreFocus?.()
  }, [onCollapse, onRestoreFocus])

  // 显式关闭：释放后端 view；失败时保留界面与错误，不伪造成功清理
  const handleClose = useCallback(async () => {
    const active = activeViewRef.current
    if (active) {
      try {
        await environmentService.closeIdeView(active.environmentId, viewId)
      } catch (err: unknown) {
        setErrorMessage(err instanceof Error ? err.message : t('ai.ide.error'))
        return
      }
    }
    activeSessionIdRef.current += 1
    setView(null)
    setStatus('idle')
    setIsIframeLoaded(false)
    setTerminalError(null)
    onIdeMountedChangeRef.current?.(false)
    onClose()
    onRestoreFocus?.()
  }, [viewId, onClose, onRestoreFocus, t])

  // 新建终端
  const handleNewTerminal = useCallback(async () => {
    if (!view) return
    setTerminalError(null)
    try {
      await environmentService.openIdeViewTerminal(view.environmentId, viewId)
    } catch (err: unknown) {
      setTerminalError(err instanceof Error ? err.message : t('ai.ide.terminalFailed'))
    }
  }, [view, viewId, t])

  // 目录更改：PUT 请求；PUT 结果 entryUrl 可能为 null，保留已有 entryUrl 不重新加载 iframe
  const handleChangeDirectory = useCallback(
    async (newDirectory: string) => {
      if (!view) return
      setDirectoryPending(true)
      setDirectoryError(null)
      try {
        const res = await environmentService.changeIdeViewDirectory(view.environmentId, viewId, {
          directory: newDirectory,
        })
        setView((prev) => (prev ? { ...res, entryUrl: res.entryUrl ?? prev.entryUrl } : res))
        setIsDirectoryModalOpen(false)
      } catch (err: unknown) {
        setDirectoryError(err instanceof Error ? err.message : t('ai.ide.changeDirectoryFailed'))
      } finally {
        setDirectoryPending(false)
      }
    },
    [view, viewId, t],
  )

  // 面板内 Escape 键恢复 composer 焦点，不抢 iframe 内编辑键
  const handlePanelKeyDown = useCallback(
    (event: KeyboardEvent<HTMLElement>) => {
      if (
        event.key === 'Escape'
        && !event.defaultPrevented
        && !event.repeat
        && !event.nativeEvent.isComposing
        && event.keyCode !== 229
      ) {
        if (isDirectoryModalOpen || confirmSwitchEnvOpen) {
          return
        }
        event.preventDefault()
        event.stopPropagation()
        handleCollapse()
      }
    },
    [isDirectoryModalOpen, confirmSwitchEnvOpen, handleCollapse],
  )

  // 无 environment 时在面板内手动选择环境并启动
  const handleConfirmSelectEnvironment = () => {
    const card = availableEnvironments.find((e) => e.id === selectedEnvId)
    if (!card) return
    const resolved: ResolvedEnvironmentInfo = {
      id: card.id,
      name: card.name,
      ready: card.ready,
      homeDirectory: card.homeDirectory,
    }

    if (view != null && view.environmentId !== card.id) {
      setPendingSwitchEnv(resolved)
      setConfirmSwitchEnvOpen(true)
      return
    }

    void startOpenView(resolved)
  }

  const handleApplyPendingSwitchEnv = async () => {
    if (!pendingSwitchEnv) return
    const target = pendingSwitchEnv
    if (view) {
      try {
        await environmentService.closeIdeView(view.environmentId, viewId)
      } catch (err: unknown) {
        setErrorMessage(err instanceof Error ? err.message : t('ai.ide.error'))
        setConfirmSwitchEnvOpen(false)
        return
      }
    }
    setConfirmSwitchEnvOpen(false)
    setPendingSwitchEnv(null)
    activeSessionIdRef.current += 1
    setView(null)
    setIsIframeLoaded(false)
    setTerminalError(null)
    void startOpenView(target)
  }

  // 即使已收起（!isOpen），只要 view 已挂载过，也要保留在 DOM 中（hidden + inert）
  const shouldRender = isOpen || view != null
  if (!shouldRender) {
    return null
  }

  const currentEnvName = environment?.name
    ?? availableEnvironments.find((e) => e.id === view?.environmentId || e.id === selectedEnvId)?.name
    ?? ''

  return (
    <aside
      className="ide-overlay-panel"
      data-testid="ide-overlay-panel"
      data-layout={isNarrow ? 'narrow' : 'wide'}
      tabIndex={-1}
      hidden={!isOpen}
      inert={!isOpen ? true : undefined}
      onKeyDown={handlePanelKeyDown}
      aria-label={t('ai.ide.title')}
    >
      <header className="ide-toolbar" data-testid="ide-toolbar">
        <div className="ide-toolbar-info">
          {currentEnvName ? (
            <span className="ide-toolbar-env" data-testid="ide-env-badge" title={currentEnvName}>
              {currentEnvName}
            </span>
          ) : null}
          {view?.directory ? (
            <span className="ide-toolbar-dir" data-testid="ide-directory-badge" title={view.directory}>
              {view.directory}
            </span>
          ) : null}
        </div>
        <div className="ide-toolbar-actions">
          {view != null ? (
            <>
              <Button
                variant="ghost"
                size="compact"
                onClick={() => setIsDirectoryModalOpen(true)}
                data-testid="ide-open-directory-btn"
              >
                {t('ai.ide.openDirectory')}
              </Button>
              <Button
                variant="ghost"
                size="compact"
                onClick={handleNewTerminal}
                data-testid="ide-new-terminal-btn"
              >
                {t('ai.ide.newTerminal')}
              </Button>
            </>
          ) : null}
          <Button
            variant="ghost"
            size="compact"
            onClick={handleCollapse}
            data-testid="ide-collapse-btn"
          >
            {t('ai.ide.collapse')}
          </Button>
          <Button
            variant="ghost"
            size="compact"
            onClick={handleClose}
            data-testid="ide-close-btn"
          >
            {t('ai.ide.close')}
          </Button>
        </div>
      </header>

      {terminalError ? (
        <div className="ide-panel-terminal-error" data-testid="ide-terminal-error">
          {terminalError}
        </div>
      ) : null}

      {errorMessage ? (
        <div className="ide-panel-error-banner" data-testid="ide-error-banner">
          {errorMessage}
        </div>
      ) : null}

      <div className="ide-panel-body" data-testid="ide-panel-body">
        {status === 'opening' && view == null ? (
          <div className="ide-panel-state" data-testid="ide-opening-state">
            <LoadingSpinner />
            <p>{t('ai.ide.loading')}</p>
          </div>
        ) : null}

        {status === 'offline' && view == null ? (
          <div className="ide-panel-state is-warning" data-testid="ide-offline-state">
            <p>
              {environment?.name
                ? t('ai.ide.environmentNotReady', { name: environment.name })
                : t('ai.ide.offline')}
            </p>
          </div>
        ) : null}

        {status === 'error' && view == null ? (
          <div className="ide-panel-state is-error" data-testid="ide-error-state">
            <p>{errorMessage ?? t('ai.ide.error')}</p>
          </div>
        ) : null}

        {status === 'select-environment' && view == null ? (
          <div className="ide-panel-state is-config" data-testid="ide-select-environment-state">
            <p>{t('ai.ide.missingEnvironment')}</p>
            {availableEnvironments.length > 0 ? (
              <div className="ide-select-env-row">
                <Select
                  value={selectedEnvId}
                  onChange={(val) => setSelectedEnvId(val)}
                  size="compact"
                  options={availableEnvironments.map((env) => ({
                    value: env.id,
                    label: `${env.name}${env.ready ? '' : ` (${t('ai.ide.offline')})`}`,
                  }))}
                />
                <Button variant="primary" size="compact" onClick={handleConfirmSelectEnvironment}>
                  {t('ai.ide.confirm')}
                </Button>
              </div>
            ) : (
              <p className="ide-state-muted">{t('ai.ide.noEnvironments')}</p>
            )}
          </div>
        ) : null}

        {view != null ? (
          <>
            {!isIframeLoaded ? (
              <div className="ide-panel-state" data-testid="ide-iframe-loading">
                <LoadingSpinner />
                <p>{t('ai.ide.loading')}</p>
              </div>
            ) : null}
            <iframe
              className="ide-panel-iframe"
              data-testid="ide-iframe"
              title={t('ai.ide.title')}
              src={view.entryUrl ?? undefined}
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads allow-modals"
              allow="clipboard-read; clipboard-write"
              onLoad={handleIframeLoaded}
            />
          </>
        ) : null}
      </div>

      {isDirectoryModalOpen && view ? (
        <OpenDirectoryModal
          currentDirectory={view.directory ?? ''}
          pending={directoryPending}
          error={directoryError}
          onConfirm={handleChangeDirectory}
          onClose={() => {
            setIsDirectoryModalOpen(false)
            setDirectoryError(null)
          }}
        />
      ) : null}

      {confirmSwitchEnvOpen && pendingSwitchEnv ? (
        <div className="ide-switch-confirm-overlay" data-testid="ide-switch-confirm-overlay">
          <div className="ide-switch-confirm-card">
            <h4>{t('ai.ide.selectEnvironment')}</h4>
            <p>{t('ai.ide.dirtyWarning', undefined, '切换环境将关闭当前 IDE 会话，未保存的修改可能会丢失。确认切换？')}</p>
            <div className="ide-switch-confirm-actions">
              <Button
                variant="ghost"
                size="compact"
                onClick={() => {
                  setConfirmSwitchEnvOpen(false)
                  setPendingSwitchEnv(null)
                }}
              >
                {t('ai.ide.cancel')}
              </Button>
              <Button variant="primary" size="compact" onClick={handleApplyPendingSwitchEnv}>
                {t('ai.ide.confirm')}
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </aside>
  )
}
