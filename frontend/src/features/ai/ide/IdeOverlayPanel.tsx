import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { ApiError } from '@/shared/api/client'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentCardDTO, IdeViewDTO } from '@/shared/api/contracts/ai-environment'
import { OpenDirectoryModal } from '@/features/ai/ide/OpenDirectoryModal'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { useI18n } from '@/shared/i18n'
import '@/features/ai/ide/ide-overlay-panel.css'

export interface ResolvedEnvironmentInfo {
  id: string
  name: string
  ready: boolean
  homeDirectory?: string | null
}

export interface IdeOverlayPanelProps {
  isOpen: boolean
  viewId: string
  environment: ResolvedEnvironmentInfo | null
  availableEnvironments: EnvironmentCardDTO[]
  initialMode?: 'files' | 'shell'
  isNarrow?: boolean
  onCollapse: () => void
  onClose: () => void
  onRestoreFocus?: () => void
  onIdeMountedChange?: (mounted: boolean) => void
}

function isDirtyError(err: unknown): boolean {
  if (!err) return false
  if (err instanceof ApiError) {
    if (err.status === 409) return true
    if (err.code === 'DIRTY' || err.code === 'DIRTY_WORKTREE' || err.errors?.reason === 'DIRTY') return true
  }
  const msg = (err as Error).message?.toLowerCase() ?? ''
  return msg.includes('dirty') || msg.includes('unsaved')
}

export function IdeOverlayPanel({
  isOpen,
  viewId,
  environment,
  availableEnvironments,
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

  // StrictMode 与并发防护：single-flight 锁与关闭代数，防止卸载后竞态残留或错误设置状态
  const inFlightRef = useRef(false)
  const closeGenerationRef = useRef(0)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      closeGenerationRef.current += 1
    }
  }, [])

  // 打开 IDE 视图的核心动作：
  // 缺目录时服务端取 HOME 校验；初次 POST 响应校验 entryUrl 非空；
  // 建立 single-flight 请求守卫与代数校验。
  const startOpenView = useCallback(
    async (targetEnv: ResolvedEnvironmentInfo, mode: 'files' | 'shell') => {
      if (inFlightRef.current) {
        return
      }
      if (!targetEnv.ready) {
        setStatus('offline')
        return
      }
      const generation = closeGenerationRef.current
      inFlightRef.current = true
      setStatus('opening')
      setErrorMessage(null)
      try {
        const res = await environmentService.createIdeView(targetEnv.id, { viewId })
        if (closeGenerationRef.current !== generation || !mountedRef.current) {
          // 已关闭或组件已卸载：丢弃结果，不污染状态
          return
        }
        if (!res.entryUrl || typeof res.entryUrl !== 'string') {
          throw new Error('Initial IDE view did not return entry URL')
        }
        setView(res)
        setStatus('ready')
        onIdeMountedChangeRef.current?.(true)
        if (mode === 'shell') {
          void Promise.resolve(environmentService.openIdeViewTerminal(targetEnv.id, viewId)).catch(() => {})
        }
      } catch (err: unknown) {
        if (closeGenerationRef.current !== generation || !mountedRef.current) {
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

  // 当 isOpen 为 true 时，根据当前环境状态决定是启动 view、展示离线还是展示环境选择
  useEffect(() => {
    if (!isOpen) {
      return
    }
    // 已挂载 view 时，重复命令不重载 entryUrl，如果是 shell 则补发 terminal 唤起
    if (view != null) {
      if (initialMode === 'shell') {
        void Promise.resolve(environmentService.openIdeViewTerminal(view.environmentId, viewId)).catch(() => {})
      }
      return
    }

    if (environment != null) {
      if (environment.ready) {
        void startOpenView(environment, initialMode)
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
  }, [isOpen, view, environment, availableEnvironments, initialMode, startOpenView, viewId])

  // 路由退出生命周期：对已确认的 view 发送 DELETE 释放后端资源；
  // 如有未保存更改，服务端与宿主受管工作区保留冲突检查，前端生命周期正常完成收尾。
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

  // 显式关闭：递增代数、清理 view 并通知卸载
  const handleClose = useCallback(async () => {
    closeGenerationRef.current += 1
    const active = view ?? activeViewRef.current
    if (active) {
      try {
        await environmentService.closeIdeView(active.environmentId, viewId)
      } catch {
        // 允许网络失败，不吞掉生命周期
      }
    }
    setView(null)
    setStatus('idle')
    setIsIframeLoaded(false)
    onIdeMountedChangeRef.current?.(false)
    onClose()
    onRestoreFocus?.()
  }, [view, viewId, onClose, onRestoreFocus])

  // 新建终端
  const handleNewTerminal = useCallback(async () => {
    if (!view) return
    try {
      await environmentService.openIdeViewTerminal(view.environmentId, viewId)
    } catch {
      // 终端请求失败保留界面
    }
  }, [view, viewId])

  // 目录更改：PUT 请求，dirty 错误明确要求先保存/放弃；
  // PUT 结果 entryUrl 可能为 null，保留已有 entryUrl，不重新加载 iframe
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
        if (isDirtyError(err)) {
          setDirectoryError(t('ai.ide.dirtyError'))
        } else {
          setDirectoryError(err instanceof Error ? err.message : t('ai.ide.changeDirectoryFailed'))
        }
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

    // 若已有正在运行的 view，切换环境需要先确认并清理旧环境，避免脏 iframe 混乱
    if (view != null && view.environmentId !== card.id) {
      setPendingSwitchEnv(resolved)
      setConfirmSwitchEnvOpen(true)
      return
    }

    void startOpenView(resolved, initialMode)
  }

  const handleApplyPendingSwitchEnv = async () => {
    if (!pendingSwitchEnv) return
    setConfirmSwitchEnvOpen(false)
    if (view) {
      try {
        await environmentService.closeIdeView(view.environmentId, viewId)
      } catch {
        // ignore
      }
    }
    setView(null)
    setIsIframeLoaded(false)
    const target = pendingSwitchEnv
    setPendingSwitchEnv(null)
    void startOpenView(target, initialMode)
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
          {view ? (
            <>
              <Button
                variant="ghost"
                size="compact"
                data-testid="ide-open-directory-btn"
                onClick={() => {
                  setDirectoryError(null)
                  setIsDirectoryModalOpen(true)
                }}
              >
                {t('ai.ide.openDirectory')}
              </Button>
              <Button
                variant="ghost"
                size="compact"
                data-testid="ide-new-terminal-btn"
                onClick={() => void handleNewTerminal()}
              >
                {t('ai.ide.newTerminal')}
              </Button>
            </>
          ) : null}
          <Button
            variant="ghost"
            size="compact"
            data-testid="ide-collapse-btn"
            onClick={handleCollapse}
          >
            {t('ai.ide.collapse')}
          </Button>
          <Button
            variant="ghost"
            size="compact"
            data-testid="ide-close-btn"
            onClick={() => void handleClose()}
          >
            {t('ai.ide.close')}
          </Button>
        </div>
      </header>

      <div className="ide-panel-body" data-testid="ide-panel-body">
        {status === 'select-environment' && view == null ? (
          <div className="ide-panel-state" data-testid="ide-select-environment-state">
            <h3>{t('ai.ide.selectEnvironment')}</h3>
            <p>{t('ai.ide.missingEnvironment')}</p>
            {availableEnvironments.length === 0 ? (
              <p className="ide-panel-empty">{t('ai.ide.noEnvironments')}</p>
            ) : (
              <div className="ide-env-selector">
                <Select
                  value={selectedEnvId}
                  options={availableEnvironments.map((env) => ({
                    value: env.id,
                    label: `${env.name}${env.ready ? '' : ' (offline)'}`,
                  }))}
                  onChange={setSelectedEnvId}
                />
                <Button onClick={handleConfirmSelectEnvironment}>
                  {t('ai.ide.confirm')}
                </Button>
              </div>
            )}
          </div>
        ) : null}

        {status === 'offline' && view == null ? (
          <div className="ide-panel-state" data-testid="ide-offline-state">
            <p>
              {environment?.name
                ? t('ai.ide.environmentNotReady', { name: environment.name })
                : t('ai.ide.offline')}
            </p>
            {availableEnvironments.length > 1 ? (
              <Button variant="ghost" size="compact" onClick={() => setStatus('select-environment')}>
                {t('ai.ide.selectEnvironment')}
              </Button>
            ) : null}
          </div>
        ) : null}

        {status === 'error' && view == null ? (
          <div className="ide-panel-state error" data-testid="ide-error-state">
            <p>{errorMessage || t('ai.ide.error')}</p>
            <Button
              variant="ghost"
              size="compact"
              onClick={() => {
                if (environment) {
                  void startOpenView(environment, initialMode)
                } else {
                  setStatus('select-environment')
                }
              }}
            >
              {t('shared.conflict.retry')}
            </Button>
          </div>
        ) : null}

        {status === 'opening' && view == null ? (
          <div className="ide-panel-state" data-testid="ide-loading-state">
            <LoadingSpinner size="medium" />
            <p>{t('ai.ide.loading')}</p>
          </div>
        ) : null}

        {view != null && view.entryUrl ? (
          <>
            {!isIframeLoaded ? (
              <div className="ide-panel-state" data-testid="ide-iframe-loading">
                <LoadingSpinner size="medium" />
                <p>{t('ai.ide.loading')}</p>
              </div>
            ) : null}
            <iframe
              key={view.viewId}
              src={view.entryUrl}
              title={t('ai.ide.title')}
              className="ide-panel-iframe"
              data-testid="ide-iframe"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads allow-modals"
              allow="clipboard-read; clipboard-write"
              onLoad={() => setIsIframeLoaded(true)}
            />
          </>
        ) : null}
      </div>

      {isDirectoryModalOpen && view != null ? (
        <OpenDirectoryModal
          currentDirectory={view.directory}
          pending={directoryPending}
          error={directoryError}
          onConfirm={(dir) => void handleChangeDirectory(dir)}
          onClose={() => {
            setDirectoryError(null)
            setIsDirectoryModalOpen(false)
          }}
        />
      ) : null}

      {confirmSwitchEnvOpen && pendingSwitchEnv ? (
        <div className="ide-switch-confirm-overlay" role="dialog" aria-modal="true" data-testid="ide-switch-env-modal">
          <div className="ide-switch-confirm-card">
            <h4>{t('ai.ide.selectEnvironment')}</h4>
            <p>{t('ai.ide.missingEnvironment')}</p>
            <div className="ide-switch-confirm-actions">
              <Button
                variant="ghost"
                onClick={() => {
                  setConfirmSwitchEnvOpen(false)
                  setPendingSwitchEnv(null)
                }}
              >
                {t('ai.ide.cancel')}
              </Button>
              <Button onClick={() => void handleApplyPendingSwitchEnv()}>
                {t('ai.ide.confirm')}
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </aside>
  )
}
