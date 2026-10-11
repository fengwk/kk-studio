import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { FolderOpen, PanelRightClose, Terminal, X } from 'lucide-react'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO, IdeViewDTO } from '@/shared/api/contracts/ai-environment'
import { OpenDirectoryModal } from '@/features/ai/ide/OpenDirectoryModal'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { useI18n } from '@/shared/i18n'
import { validateIdeEntryUrl } from '@/features/ai/ide/ide-url'
import { useIdeLifecycle } from '@/features/ai/ide/ide-lifecycle-context'
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
  paneId?: string
  environment: ResolvedEnvironmentInfo | null
  availableEnvironments: EnvironmentCardDTO[]
  action?: IdeCommandAction | null
  isNarrow?: boolean
  onCollapse: () => void
  onClose: () => void
  onRestoreFocus?: () => void
  onIdeMountedChange?: (mounted: boolean) => void
}

/**
 * 安全格式化面向用户的错误文案：
 * 避免泄露服务端内部工作区绝对路径（例如 /var/data/ 或 /home/ 路径）。
 */
function formatSafeError(err: unknown, fallback: string): string {
  if (err instanceof Error) {
    const msg = err.message
    if (msg.includes('/home/') || msg.includes('/var/') || msg.includes('/tmp/')) {
      return fallback
    }
    return msg
  }
  return fallback
}

export function IdeOverlayPanel({
  isOpen,
  viewId,
  paneId,
  environment,
  availableEnvironments,
  action,
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
  const [confirmCloseOpen, setConfirmCloseOpen] = useState(false)

  const { registerIdeView } = useIdeLifecycle()
  const activeViewRef = useRef<IdeViewDTO | null>(null)
  useEffect(() => {
    activeViewRef.current = view
  }, [view])

  const onIdeMountedChangeRef = useRef(onIdeMountedChange)
  useEffect(() => {
    onIdeMountedChangeRef.current = onIdeMountedChange
  }, [onIdeMountedChange])

  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  // 操作 Epoch 与并发防线：
  // operationEpochRef 覆盖创建、切换目录、关闭与切换环境；
  // 任何关闭或切换环境立即递增 epoch，使晚到达的 PUT 目录更新不会复活已关闭的 iframe。
  const operationEpochRef = useRef(0)
  const openInFlightRef = useRef(false)
  const openingPromiseRef = useRef<Promise<void> | null>(null)
  const closeInFlightRef = useRef(false)
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
  // 检查当前 epoch；初次响应校验 entryUrl，若校验失败必须立即向后端发送 closeIdeView 清理。
  const startOpenView = useCallback(
    async (targetEnv: ResolvedEnvironmentInfo) => {
      if (openInFlightRef.current) {
        return
      }
      if (!targetEnv.ready) {
        setStatus('offline')
        return
      }
      const epoch = operationEpochRef.current
      openInFlightRef.current = true
      setStatus('opening')
      setErrorMessage(null)
      onIdeMountedChangeRef.current?.(true)
      const promise = (async () => {
        try {
          const res = await environmentService.createIdeView(targetEnv.id, { viewId })
          // 同步写入 activeViewRef.current，防止在 React 批量更新提交前路由离开导致孤儿视图
          activeViewRef.current = res

          // 如果在 opening 期间发生了关闭、切换环境或组件真正卸载：清理新建视图
          if (operationEpochRef.current !== epoch || !mountedRef.current) {
            try {
              await environmentService.closeIdeView(targetEnv.id, res.viewId)
              activeViewRef.current = null
            } catch (err: unknown) {
              setErrorMessage(formatSafeError(err, t('ai.ide.dirtyCloseWarning')))
            }
            return
          }

          // 校验 entryUrl 安全契约；校验失败必须主动释放已在后端创建的视图
          try {
            validateIdeEntryUrl(res.entryUrl)
          } catch (valErr) {
            try {
              await environmentService.closeIdeView(targetEnv.id, res.viewId)
              activeViewRef.current = null
            } catch (cleanErr: unknown) {
              setErrorMessage(formatSafeError(cleanErr, t('ai.ide.dirtyCloseWarning')))
              throw cleanErr
            }
            throw valErr
          }

          setView(res)
          setStatus('ready')
          onIdeMountedChangeRef.current?.(true)
        } catch (err: unknown) {
          if (operationEpochRef.current !== epoch || !mountedRef.current) {
            return
          }
          setStatus('error')
          onIdeMountedChangeRef.current?.(false)
          setErrorMessage(formatSafeError(err, t('ai.ide.error')))
        } finally {
          openInFlightRef.current = false
        }
      })()
      openingPromiseRef.current = promise
      await promise
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
        setTerminalError(formatSafeError(err, t('ai.ide.terminalFailed')))
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
          setTerminalError(formatSafeError(err, t('ai.ide.terminalFailed')))
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

  // 统一切断与关闭逻辑：供全局路由拦截与本地关闭调用。
  // 成功关闭返回 true，失败（如后端报 DIRTY 错误）返回 false 并保留 DOM 与错误提示
  const requestClose = useCallback(async (): Promise<boolean> => {
    // 若正在处于 opening 流程，等待其完成后再执行关闭
    if (openInFlightRef.current && openingPromiseRef.current) {
      try {
        await openingPromiseRef.current
      } catch {
        // 创建已失败且无活跃视图时成功返回
        if (!activeViewRef.current) {
          return true
        }
      }
    }

    if (closeInFlightRef.current) {
      return false
    }
    closeInFlightRef.current = true
    operationEpochRef.current += 1
    const active = activeViewRef.current
    if (active) {
      try {
        await environmentService.closeIdeView(active.environmentId, viewId)
        activeViewRef.current = null
      } catch (err: unknown) {
        closeInFlightRef.current = false
        // 关闭失败：保留 activeViewRef 与活跃状态，不关闭面板，显示错误
        setErrorMessage(formatSafeError(err, t('ai.ide.dirtyCloseWarning')))
        return false
      }
    }
    closeInFlightRef.current = false
    activeViewRef.current = null
    setView(null)
    setStatus('idle')
    setIsIframeLoaded(false)
    setTerminalError(null)
    onIdeMountedChangeRef.current?.(false)
    onCloseRef.current?.()
    return true
  }, [viewId, t])

  const executeClose = useCallback(async () => {
    const success = await requestClose()
    if (success) {
      onRestoreFocus?.()
    }
  }, [requestClose, onRestoreFocus])

  // 全局注册 IDE 生命周期：从 opening 阶段起就处于 active 状态，
  // 保护在 opening 期间以及 ready 期间不受路由跳转和缩容 unmount 的意外影响。
  // 若关闭发生异常（activeViewRef 仍存在），仍保持 active 状态。
  useEffect(() => {
    const isActive = status === 'opening' || status === 'ready' || activeViewRef.current != null
    return registerIdeView({
      viewId,
      paneId,
      active: isActive,
      requestClose,
    })
  }, [viewId, paneId, status, requestClose, registerIdeView])

  // 点击关闭按钮：如果已挂载活跃视图，弹出确认对话框提示未保存修改；
  // 用户取消则不发生任何 DELETE 或清理
  const handleCloseClick = useCallback(() => {
    if (view != null) {
      setConfirmCloseOpen(true)
    } else {
      void executeClose()
    }
  }, [view, executeClose])

  // 打开当前视图的终端
  const handleOpenTerminal = useCallback(async () => {
    if (!view) return
    setTerminalError(null)
    try {
      await environmentService.openIdeViewTerminal(view.environmentId, viewId)
    } catch (err: unknown) {
      setTerminalError(formatSafeError(err, t('ai.ide.terminalFailed')))
    }
  }, [view, viewId, t])

  // 目录更改：PUT 请求；使用 epoch 校验，晚到达的响应绝不能复活已关闭的 iframe
  const handleChangeDirectory = useCallback(
    async (newDirectory: string) => {
      if (!view) return
      const epoch = operationEpochRef.current
      setDirectoryPending(true)
      setDirectoryError(null)
      try {
        const res = await environmentService.changeIdeViewDirectory(view.environmentId, viewId, {
          directory: newDirectory,
        })
        if (operationEpochRef.current !== epoch || !mountedRef.current) {
          return
        }
        setView((prev) => (prev ? { ...res, entryUrl: res.entryUrl ?? prev.entryUrl } : res))
        setIsDirectoryModalOpen(false)
      } catch (err: unknown) {
        if (operationEpochRef.current !== epoch || !mountedRef.current) {
          return
        }
        setDirectoryError(formatSafeError(err, t('ai.ide.changeDirectoryFailed')))
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
        if (isDirectoryModalOpen || confirmSwitchEnvOpen || confirmCloseOpen) {
          return
        }
        event.preventDefault()
        event.stopPropagation()
        handleCollapse()
      }
    },
    [isDirectoryModalOpen, confirmSwitchEnvOpen, confirmCloseOpen, handleCollapse],
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
    operationEpochRef.current += 1
    if (view) {
      try {
        await environmentService.closeIdeView(view.environmentId, viewId)
      } catch (err: unknown) {
        setErrorMessage(formatSafeError(err, t('ai.ide.dirtyCloseWarning')))
        setConfirmSwitchEnvOpen(false)
        return
      }
    }
    setConfirmSwitchEnvOpen(false)
    setPendingSwitchEnv(null)
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

  const currentEnvId = view?.environmentId ?? environment?.id ?? selectedEnvId
  const currentEnvName = availableEnvironments.find((e) => e.id === currentEnvId)?.name
    ?? (environment?.id === currentEnvId ? environment.name : undefined)
    ?? ''
  const locationTitle = [currentEnvName, view?.directory].filter(Boolean).join(' · ')

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
          {view?.directory ? (
            <Button
              variant="ghost"
              size="compact"
              className="ide-toolbar-directory"
              onClick={() => setIsDirectoryModalOpen(true)}
              aria-label={t('ai.ide.openDirectory')}
              title={locationTitle}
              data-testid="ide-open-directory-btn"
            >
              <FolderOpen size={14} aria-hidden="true" />
              <span className="ide-toolbar-dir" data-testid="ide-directory-badge">
                {view.directory}
              </span>
            </Button>
          ) : (
            <span className="ide-toolbar-title" title={currentEnvName || undefined}>
              {t('ai.ide.title')}
            </span>
          )}
        </div>
        <div className="ide-toolbar-actions">
          {view != null ? (
            <Button
              variant="ghost"
              size="compact"
              className="ide-toolbar-icon"
              onClick={handleOpenTerminal}
              aria-label={t('ai.ide.openTerminal')}
              title={t('ai.ide.openTerminal')}
              data-testid="ide-terminal-btn"
            >
              <Terminal size={14} aria-hidden="true" />
            </Button>
          ) : null}
          <Button
            variant="ghost"
            size="compact"
            className="ide-toolbar-icon"
            onClick={handleCollapse}
            aria-label={t('ai.ide.collapse')}
            title={t('ai.ide.collapse')}
            data-testid="ide-collapse-btn"
          >
            <PanelRightClose size={14} aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="compact"
            className="ide-toolbar-icon"
            onClick={handleCloseClick}
            aria-label={t('ai.ide.close')}
            title={t('ai.ide.close')}
            data-testid="ide-close-btn"
          >
            <X size={14} aria-hidden="true" />
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

      {confirmCloseOpen ? (
        <Dialog
          title={t('ai.ide.closeConfirmTitle')}
          onClose={() => setConfirmCloseOpen(false)}
          role="alertdialog"
        >
          <div className="ide-confirm-dialog-content">
            <p>{t('ai.ide.closeConfirmMessage')}</p>
            <div className="ide-confirm-dialog-actions">
              <Button variant="ghost" size="compact" onClick={() => setConfirmCloseOpen(false)}>
                {t('ai.ide.cancel')}
              </Button>
              <Button
                danger
                size="compact"
                onClick={() => {
                  setConfirmCloseOpen(false)
                  void executeClose()
                }}
                data-testid="ide-confirm-close-btn"
              >
                {t('ai.ide.confirm')}
              </Button>
            </div>
          </div>
        </Dialog>
      ) : null}

      {confirmSwitchEnvOpen && pendingSwitchEnv ? (
        <Dialog
          title={t('ai.ide.selectEnvironment')}
          onClose={() => {
            setConfirmSwitchEnvOpen(false)
            setPendingSwitchEnv(null)
          }}
          role="alertdialog"
        >
          <div className="ide-confirm-dialog-content">
            <p>{t('ai.ide.dirtyNotice')}</p>
            <div className="ide-confirm-dialog-actions">
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
              <Button
                danger
                size="compact"
                onClick={handleApplyPendingSwitchEnv}
                data-testid="ide-confirm-switch-btn"
              >
                {t('ai.ide.confirm')}
              </Button>
            </div>
          </div>
        </Dialog>
      ) : null}
    </aside>
  )
}
