import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react'
import { FolderOpen, PanelRightClose, Terminal, X } from 'lucide-react'
import { OpenDirectoryModal } from '@/features/environment/ide/OpenDirectoryModal'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import type { IdeViewState } from '@/features/environment/ide/IdePanelProvider'
import './ide-overlay-panel.css'

export interface IdeCloseResult {
  ok: boolean
  dirty: boolean
  message?: string
}

export interface IdeOverlayPanelProps {
  isOpen: boolean
  selectedEnvironmentId: string | null
  openedEnvironmentIds: string[]
  views: Record<string, IdeViewState>
  environments: IdeEnvironmentInfo[]
  environmentsLoading: boolean
  environmentsError: boolean
  identityReady: boolean
  storageUnavailable: boolean
  onSelectEnvironment: (environmentId: string) => void
  onCollapse: () => void
  onCloseEnvironment: (environmentId: string, discard: boolean) => Promise<IdeCloseResult>
  onDismissCloseError: (environmentId: string) => void
  onChangeDirectory: (
    environmentId: string,
    directory: string,
  ) => Promise<{ ok: boolean; message?: string }>
  onOpenTerminal: (environmentId: string) => void
  onIframeLoaded: (environmentId: string, entryUrl: string) => void
  onOpenNewView: (environmentId: string) => void
  onRetryClaim: (environmentId: string) => void
  onReloadEnvironments: () => void
}

const COMMAND_MESSAGES: Record<string, TranslationKey> = {
  'view-not-ready': 'ai.ide.viewNotReady',
  'terminal-result-unknown': 'ai.ide.commandResultUnknown',
  'terminal-failed': 'ai.ide.terminalFailed',
  'directory-result-unknown': 'ai.ide.commandResultUnknown',
  'directory-failed': 'ai.ide.changeDirectoryFailed',
  'binding-changed': 'ai.ide.bindingChanged',
  'close-result-unknown': 'ai.ide.commandResultUnknown',
  'close-failed': 'ai.ide.error',
}

/**
 * tab 全局 IDE 面板：只渲染当前选中 view 的可见性，其他已打开 iframe 保持挂载。
 * 面板自身不发起 claim/关闭之外的隐式动作；所有环境/目录/终端操作都由用户入口触发。
 */
export function IdeOverlayPanel({
  isOpen,
  selectedEnvironmentId,
  openedEnvironmentIds,
  views,
  environments,
  environmentsLoading,
  environmentsError,
  identityReady,
  storageUnavailable,
  onSelectEnvironment,
  onCollapse,
  onCloseEnvironment,
  onDismissCloseError,
  onChangeDirectory,
  onOpenTerminal,
  onIframeLoaded,
  onOpenNewView,
  onRetryClaim,
  onReloadEnvironments,
}: IdeOverlayPanelProps) {
  const { t } = useI18n()
  const [directoryOpen, setDirectoryOpen] = useState(false)
  const [directoryPending, setDirectoryPending] = useState(false)
  const [directoryError, setDirectoryError] = useState<string | null>(null)
  const [dirtyCloseEnvironmentId, setDirtyCloseEnvironmentId] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const lastSelectedRef = useRef(selectedEnvironmentId)

  useEffect(() => {
    if (lastSelectedRef.current === selectedEnvironmentId) {
      return
    }
    lastSelectedRef.current = selectedEnvironmentId
    setDirectoryOpen(false)
    setDirectoryError(null)
    setDirectoryPending(false)
    setDirtyCloseEnvironmentId(null)
  }, [selectedEnvironmentId])

  const selectedView = selectedEnvironmentId == null
    ? null
    : views[selectedEnvironmentId] ?? null
  const selectedEnvironment = selectedEnvironmentId == null
    ? null
    : environments.find((environment) => environment.id === selectedEnvironmentId) ?? null
  const openedViews = useMemo(
    () => openedEnvironmentIds
      .map((environmentId) => views[environmentId])
      .filter((view): view is IdeViewState => view != null),
    [openedEnvironmentIds, views],
  )
  const environmentName = useCallback(
    (environmentId: string) => environments.find((environment) => environment.id === environmentId)?.name
      ?? environmentId,
    [environments],
  )
  const environmentOptions = useMemo(() => {
    const options = environments.map((environment) => ({
      value: environment.id,
      label: environment.ready
        ? environment.name
        : `${environment.name} (${t('ai.ide.offline')})`,
    }))
    if (
      selectedEnvironmentId != null
      && !options.some((option) => option.value === selectedEnvironmentId)
    ) {
      options.push({ value: selectedEnvironmentId, label: selectedEnvironmentId })
    }
    return options
  }, [environments, selectedEnvironmentId, t])

  const commandMessage = useCallback((code: string | undefined, fallback: TranslationKey) => {
    if (code == null) {
      return t(fallback)
    }
    const key = COMMAND_MESSAGES[code]
    return key == null ? code : t(key)
  }, [t])

  const selectedReady = selectedView?.status === 'ready'
  const selectedEpoch = selectedView?.bindingEpoch
  const actionsDisabled = selectedView == null
    || selectedView.claimInFlight === true
    || selectedView.closeInFlight === true
    || !selectedReady
  const closeDisabled = actionsDisabled || selectedEpoch == null

  const handleCloseClick = useCallback(() => {
    if (selectedEnvironmentId == null) {
      return
    }
    void onCloseEnvironment(selectedEnvironmentId, false).then((result) => {
      if (!result.ok && result.dirty) {
        setDirtyCloseEnvironmentId(selectedEnvironmentId)
      }
    })
  }, [onCloseEnvironment, selectedEnvironmentId])

  const handleDiscardClose = useCallback(async () => {
    if (dirtyCloseEnvironmentId == null || discarding) {
      return
    }
    setDiscarding(true)
    try {
      const result = await onCloseEnvironment(dirtyCloseEnvironmentId, true)
      if (!result.ok && result.dirty) {
        return
      }
      setDirtyCloseEnvironmentId(null)
    } finally {
      setDiscarding(false)
    }
  }, [dirtyCloseEnvironmentId, discarding, onCloseEnvironment])

  const handleDirectoryConfirm = useCallback(async (directory: string) => {
    if (selectedEnvironmentId == null || directoryPending) {
      return
    }
    setDirectoryPending(true)
    setDirectoryError(null)
    try {
      const result = await onChangeDirectory(selectedEnvironmentId, directory)
      if (!result.ok) {
        setDirectoryError(commandMessage(result.message, 'ai.ide.changeDirectoryFailed'))
        return
      }
      setDirectoryOpen(false)
    } finally {
      setDirectoryPending(false)
    }
  }, [commandMessage, directoryPending, onChangeDirectory, selectedEnvironmentId])

  const handlePanelKeyDown = useCallback((event: KeyboardEvent<HTMLElement>) => {
    if (
      event.key === 'Escape'
      && !event.defaultPrevented
      && !event.repeat
      && !event.nativeEvent.isComposing
      && event.keyCode !== 229
    ) {
      if (directoryOpen || dirtyCloseEnvironmentId != null) {
        return
      }
      event.preventDefault()
      event.stopPropagation()
      onCollapse()
    }
  }, [directoryOpen, dirtyCloseEnvironmentId, onCollapse])

  const terminalError = selectedView?.terminalError
  const closeError = selectedView?.closeError
  const claimError = selectedView?.errorMessage
  const viewBusy = selectedView?.status === 'pending'
    || selectedView?.status === 'registering'
    || selectedView?.status === 'claiming'
    || selectedView?.status === 'rebinding'
  const viewLayerVisible = isOpen && selectedEnvironmentId !== null
  const noEnvironment = selectedEnvironmentId == null
  const environmentOffline = selectedEnvironment?.ready === false
    || selectedView?.status === 'offline'
  const environmentUnknown = selectedView == null
    && !environmentsLoading
    && !environmentsError
    && selectedEnvironment == null

  return (
    <aside
      className="ide-overlay-panel ide-global-panel"
      data-testid="ide-overlay-panel"
      tabIndex={-1}
      hidden={!isOpen}
      inert={!isOpen ? true : undefined}
      onKeyDown={handlePanelKeyDown}
      aria-label={t('ai.ide.title')}
    >
      <header className="ide-toolbar" data-testid="ide-toolbar">
        <div className="ide-toolbar-info">
          <Select
            compact
            className="ide-environment-selector"
            value={selectedEnvironmentId ?? ''}
            options={environmentOptions}
            placeholder={t('ai.ide.environmentSelector')}
            aria-label={t('ai.ide.environmentSelector')}
            onChange={onSelectEnvironment}
          />
          {selectedView?.directory ? (
            <Button
              variant="ghost"
              size="compact"
              className="ide-toolbar-directory"
              onClick={() => {
                setDirectoryError(null)
                setDirectoryOpen(true)
              }}
              disabled={actionsDisabled}
              aria-label={t('ai.ide.openDirectory')}
              title={`${environmentName(selectedView.environmentId)} · ${selectedView.directory}`}
              data-testid="ide-open-directory-btn"
            >
              <FolderOpen size={14} aria-hidden="true" />
              <span className="ide-toolbar-dir" data-testid="ide-directory-badge">
                {selectedView.directory}
              </span>
            </Button>
          ) : null}
        </div>
        <div className="ide-toolbar-actions">
          {selectedView != null ? (
            <Button
              variant="ghost"
              size="compact"
              className="ide-toolbar-icon"
              onClick={() => {
                if (selectedEnvironmentId != null) {
                  onOpenTerminal(selectedEnvironmentId)
                }
              }}
              disabled={actionsDisabled}
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
            onClick={onCollapse}
            aria-label={t('ai.ide.collapse')}
            title={t('ai.ide.collapse')}
            data-testid="ide-collapse-btn"
          >
            <PanelRightClose size={14} aria-hidden="true" />
          </Button>
          {selectedView != null ? (
            <Button
              variant="ghost"
              size="compact"
              className="ide-toolbar-icon"
              onClick={handleCloseClick}
              loading={selectedView.closeInFlight === true}
              disabled={closeDisabled}
              aria-label={t('ai.ide.close')}
              title={t('ai.ide.close')}
              data-testid="ide-close-btn"
            >
              <X size={14} aria-hidden="true" />
            </Button>
          ) : null}
        </div>
      </header>

      {storageUnavailable ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-storage-unavailable">
          {t('ai.ide.storageUnavailable')}
        </div>
      ) : null}
      {!identityReady ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-reconnecting">
          {t('ai.ide.reconnecting')}
        </div>
      ) : null}
      {environmentOffline ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-offline-banner">
          {t('ai.ide.offline')}
        </div>
      ) : null}
      {selectedView?.status === 'unknown' ? (
        <div className="ide-panel-recovery-banner" role="alert" data-testid="ide-claim-unknown">
          <span>{t('ai.ide.claimUnknown')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onRetryClaim(selectedEnvironmentId)
              }
            }}
            data-testid="ide-retry-claim-btn"
          >
            {t('ai.ide.retryClaim')}
          </Button>
        </div>
      ) : null}
      {selectedView?.status === 'in-use' ? (
        <div className="ide-panel-recovery-banner" role="status" data-testid="ide-view-in-use">
          <span>{t('ai.ide.viewInUse')}</span>
          <div className="ide-panel-recovery-actions">
            <Button
              variant="ghost"
              size="compact"
              onClick={() => {
                if (selectedEnvironmentId != null) {
                  onRetryClaim(selectedEnvironmentId)
                }
              }}
              data-testid="ide-retry-resume-btn"
            >
              {t('ai.ide.retryResume')}
            </Button>
            <Button
              variant="ghost"
              size="compact"
              onClick={() => {
                if (selectedEnvironmentId != null) {
                  onOpenNewView(selectedEnvironmentId)
                }
              }}
              data-testid="ide-open-new-view-btn"
            >
              {t('ai.ide.openNewView')}
            </Button>
          </div>
        </div>
      ) : null}
      {selectedView?.status === 'gone' ? (
        <div className="ide-panel-recovery-banner" role="alert" data-testid="ide-view-gone">
          <span>{t('ai.ide.viewGone')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onOpenNewView(selectedEnvironmentId)
              }
            }}
            data-testid="ide-open-new-view-btn"
          >
            {t('ai.ide.openNewView')}
          </Button>
        </div>
      ) : null}
      {selectedView != null
      && selectedView.entryUrl != null
      && selectedView.claimInFlight ? (
        <div className="ide-panel-warning-banner" role="status" data-testid="ide-rebinding">
          {t('ai.ide.reconnecting')}
        </div>
      ) : null}
      {selectedView?.status === 'error' ? (
        <div className="ide-panel-error-banner" role="alert" data-testid="ide-error-banner">
          {claimError || t('ai.ide.error')}
        </div>
      ) : null}
      {closeError ? (
        <div className="ide-panel-error-banner" role="alert" data-testid="ide-close-error">
          <span>{commandMessage(closeError, 'ai.ide.error')}</span>
          <Button
            variant="ghost"
            size="compact"
            onClick={() => {
              if (selectedEnvironmentId != null) {
                onDismissCloseError(selectedEnvironmentId)
              }
            }}
          >
            {t('ai.ide.dismiss')}
          </Button>
        </div>
      ) : null}
      {terminalError ? (
        <div className="ide-panel-terminal-error" role="alert" data-testid="ide-terminal-error">
          {commandMessage(terminalError, 'ai.ide.terminalFailed')}
        </div>
      ) : null}

      <div className="ide-panel-body" data-testid="ide-panel-body">
        {noEnvironment ? (
          <div className="ide-panel-state is-config" data-testid="ide-select-environment-state">
            <p>{t('ai.ide.missingEnvironment')}</p>
            {environments.length > 0 ? (
              <div className="ide-select-env-row">
                <Select
                  value=""
                  options={environmentOptions}
                  placeholder={t('ai.ide.environmentSelector')}
                  aria-label={t('ai.ide.environmentSelector')}
                  onChange={onSelectEnvironment}
                />
              </div>
            ) : (
              <p className="ide-state-muted">{t('ai.ide.noEnvironments')}</p>
            )}
          </div>
        ) : null}

        {selectedEnvironmentId != null && selectedView == null ? (
          environmentsLoading ? (
            <div className="ide-panel-state" data-testid="ide-opening-state">
              <LoadingSpinner />
              <p>{t('ai.ide.loading')}</p>
            </div>
          ) : environmentsError || environmentUnknown ? (
            <div className="ide-panel-state is-warning" data-testid="ide-offline-state">
              <p>{t('ai.ide.offline')}</p>
              <Button variant="ghost" size="compact" onClick={onReloadEnvironments}>
                {t('shared.retry')}
              </Button>
            </div>
          ) : environmentOffline ? (
            <div className="ide-panel-state is-warning" data-testid="ide-offline-state">
              <p>{t('ai.ide.offline')}</p>
            </div>
          ) : (
            <div className="ide-panel-state" data-testid="ide-opening-state">
              <LoadingSpinner />
              <p>{t('ai.ide.loading')}</p>
            </div>
          )
        ) : null}

        {selectedView != null && selectedView.entryUrl == null ? (
          viewBusy ? (
            <div className="ide-panel-state" data-testid="ide-opening-state">
              <LoadingSpinner />
              <p>{t('ai.ide.loading')}</p>
            </div>
          ) : selectedView.status === 'in-use' ? (
            <div className="ide-panel-state is-warning" data-testid="ide-view-in-use-state">
              <p>{t('ai.ide.viewInUse')}</p>
            </div>
          ) : selectedView.status === 'gone' ? (
            <div className="ide-panel-state is-warning" data-testid="ide-view-gone-state">
              <p>{t('ai.ide.viewGone')}</p>
            </div>
          ) : selectedView.status === 'unknown' ? (
            <div className="ide-panel-state is-error" data-testid="ide-error-state">
              <p>{t('ai.ide.claimUnknown')}</p>
            </div>
          ) : selectedView.status === 'error' ? (
            <div className="ide-panel-state is-error" data-testid="ide-error-state">
              <p>{claimError || t('ai.ide.error')}</p>
            </div>
          ) : environmentOffline ? (
            <div className="ide-panel-state is-warning" data-testid="ide-offline-state">
              <p>{t('ai.ide.offline')}</p>
            </div>
          ) : null
        ) : null}

        <div className="ide-global-view-stack">
          {openedViews.map((view) => (
            <div
              className="ide-global-view"
              key={`${view.environmentId}:${view.viewId}`}
              data-ide-environment={view.environmentId}
              data-testid="ide-view-layer"
              hidden={!viewLayerVisible || selectedEnvironmentId !== view.environmentId}
              inert={selectedEnvironmentId !== view.environmentId ? true : undefined}
            >
              {view.entryUrl != null ? (
                <>
                  {!view.iframeLoaded ? (
                    <div className="ide-panel-state" data-testid="ide-iframe-loading">
                      <LoadingSpinner />
                      <p>{t('ai.ide.loading')}</p>
                    </div>
                  ) : null}
                  <iframe
                    className="ide-panel-iframe"
                    data-testid="ide-iframe"
                    data-ide-environment={view.environmentId}
                    title={`${t('ai.ide.title')} · ${environmentName(view.environmentId)}`}
                    src={view.entryUrl}
                    sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads allow-modals"
                    allow="clipboard-read; clipboard-write"
                    onLoad={() => onIframeLoaded(view.environmentId, view.entryUrl!)}
                  />
                </>
              ) : null}
            </div>
          ))}
        </div>
      </div>

      {directoryOpen && selectedEnvironmentId != null && selectedView?.directory ? (
        <OpenDirectoryModal
          currentDirectory={selectedView.directory}
          pending={directoryPending}
          error={directoryError}
          onConfirm={(directory) => {
            void handleDirectoryConfirm(directory)
          }}
          onClose={() => {
            if (directoryPending) {
              return
            }
            setDirectoryOpen(false)
            setDirectoryError(null)
          }}
        />
      ) : null}

      {dirtyCloseEnvironmentId != null ? (
        <Dialog
          title={t('ai.ide.dirtyDiscardTitle')}
          pending={discarding}
          onClose={() => {
            if (!discarding) {
              setDirtyCloseEnvironmentId(null)
            }
          }}
          role="alertdialog"
        >
          <div className="ide-confirm-dialog-content" data-testid="ide-dirty-close-dialog">
            <p>{t('ai.ide.dirtyDiscardMessage')}</p>
            <div className="ide-confirm-dialog-actions">
              <Button
                variant="ghost"
                size="compact"
                onClick={() => setDirtyCloseEnvironmentId(null)}
                disabled={discarding}
              >
                {t('ai.ide.cancel')}
              </Button>
              <Button
                danger
                size="compact"
                loading={discarding}
                onClick={() => {
                  void handleDiscardClose()
                }}
                data-testid="ide-discard-close-btn"
              >
                {t('ai.ide.discardAndClose')}
              </Button>
            </div>
          </div>
        </Dialog>
      ) : null}
    </aside>
  )
}
