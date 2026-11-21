import { useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowLeft, PanelRightClose, X } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import type { ApplicationEventConnectionState } from '@/shared/app-events/connection'
import type { IdeEnvironmentInfo } from '@/features/environment/ide/ide-panel-context'
import { isIdeDirectory } from '@/features/environment/ide/ide-tab-state'
import { isSameBinding, type IdeViewState } from '@/features/environment/ide/ide-view-state'
import type { IdeBrowserSessionStatus } from '@/features/environment/ide/ide-browser-session'
import { IdePanelStatusBanners } from '@/features/environment/ide/IdePanelStatusBanners'
import './ide-overlay-panel.css'

export interface IdeCloseResult {
  ok: boolean
  /** 强制关闭前需要用户确认；不代表已确认存在未保存修改。 */
  requiresForceConfirmation: boolean
  message?: string
}

export interface IdeOverlayPanelProps {
  isOpen: boolean
  selectedEnvironmentId: string | null
  selectedViewId: string | null
  openedEnvironmentIds: string[]
  viewsByEnvironment: Record<string, string[]>
  views: Record<string, IdeViewState>
  environments: IdeEnvironmentInfo[]
  sessionStatus: IdeBrowserSessionStatus
  storageUnavailable: boolean
  recoveryError?: boolean
  connectionState: ApplicationEventConnectionState
  onSelectEnvironment: (environmentId: string) => void
  onSelectView: (viewId: string) => void
  onCollapse: () => void
  onCloseEnvironment: (viewId: string, discard: boolean) => Promise<IdeCloseResult>
  onDismissCloseError: (viewId: string) => void
  onChangeDirectory: (viewId: string, directory: string) => Promise<{ ok: boolean; message?: string }>
  onIframeLoaded: (viewId: string, entryUrl: string) => void
  onOpenNewView: (environmentId: string) => void
  onRetryClaim: (viewId: string) => void
}

const COMMAND_MESSAGES: Record<string, TranslationKey> = {
  'view-not-ready': 'ai.ide.viewNotReady',
  'terminal-result-unknown': 'ai.ide.commandResultUnknown',
  'terminal-failed': 'ai.ide.terminalFailed',
  'directory-result-unknown': 'ai.ide.commandResultUnknown',
  'directory-failed': 'ai.ide.changeDirectoryFailed',
  'binding-changed': 'ai.ide.bindingChanged',
  'close-result-unknown': 'ai.ide.commandResultUnknown',
  'close-control-unavailable': 'ai.ide.closeControlUnavailable',
  'close-failed': 'ai.ide.error',
}

/** 收起和切换标签只改变可见性，不卸载已有的 Workbench。 */
export function IdeOverlayPanel({
  isOpen,
  selectedEnvironmentId,
  selectedViewId,
  openedEnvironmentIds,
  viewsByEnvironment,
  views,
  environments,
  sessionStatus,
  storageUnavailable,
  recoveryError = false,
  connectionState,
  onSelectEnvironment,
  onSelectView,
  onCollapse,
  onCloseEnvironment,
  onDismissCloseError,
  onChangeDirectory,
  onIframeLoaded,
  onOpenNewView,
  onRetryClaim,
}: IdeOverlayPanelProps) {
  const { t } = useI18n()
  const [forceClose, setForceClose] = useState<{
    binding: IdeViewState
    reason: 'unknown' | 'control-unavailable'
  } | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const [directoryError, setDirectoryError] = useState<{ binding: IdeViewState; message?: string } | null>(null)
  const activeFrameRef = useRef<HTMLIFrameElement>(null)
  const viewsRef = useRef(views)
  const selection = useMemo(() => ({ isOpen, selectedEnvironmentId, selectedViewId }), [
    isOpen, selectedEnvironmentId, selectedViewId,
  ])
  const selectionRef = useRef(selection)
  const pendingCommands = useRef(new Set<string>())

  useLayoutEffect(() => {
    viewsRef.current = views
    selectionRef.current = selection
  }, [views, selection])

  useLayoutEffect(() => {
    setForceClose(null)
    setDirectoryError(null)
  }, [selection])

  const selectedView = selectedViewId == null ? null : views[selectedViewId] ?? null
  const selectedEnvironment = environments.find(({ id }) => id === selectedEnvironmentId)
  const openedViews = openedEnvironmentIds.flatMap((id) => (
    (viewsByEnvironment[id] ?? []).map((viewId) => views[viewId]).filter(Boolean)
  ))
  const environmentOptions = environments.map(({ id, name, ready }) => ({
    value: id,
    label: ready ? name : `${name} · ${t('ai.ide.offlineShort')}`,
  }))
  if (selectedEnvironmentId != null && selectedEnvironment == null) {
    environmentOptions.push({ value: selectedEnvironmentId, label: selectedEnvironmentId })
  }

  function commandMessage(code: string | undefined, fallback: TranslationKey) {
    if (code == null) {
      return t(fallback)
    }
    const key = COMMAND_MESSAGES[code]
    return key == null ? code : t(key)
  }

  const forceCloseViewId = isOpen && forceClose != null
    && forceClose.binding.environmentId === selectedEnvironmentId
    && views[forceClose.binding.viewId]?.status === 'ready'
    && isSameBinding(views[forceClose.binding.viewId], forceClose.binding) ? forceClose.binding.viewId : null

  async function handleClose(view: IdeViewState, discard = false) {
    const { viewId } = view
    if (pendingCommands.current.has(viewId)) {
      return
    }
    pendingCommands.current.add(viewId)
    if (discard) {
      setDiscarding(true)
    }
    try {
      const result = await onCloseEnvironment(viewId, discard)
      if (selectionRef.current !== selection) {
        return
      }
      if (!result.ok && result.requiresForceConfirmation && view.bindingEpoch != null
        && viewsRef.current[viewId]?.status === 'ready' && isSameBinding(viewsRef.current[viewId], view)) {
        setForceClose({
          binding: view,
          reason: result.message === 'close-control-unavailable' ? 'control-unavailable' : 'unknown',
        })
      } else if (discard) {
        setForceClose(null)
      }
    } finally {
      pendingCommands.current.delete(viewId)
      if (discard) {
        setDiscarding(false)
      }
    }
  }

  useLayoutEffect(() => {
    if (!isOpen || selectedView?.status !== 'ready' || selectedView.bindingEpoch == null
      || selectedView.environmentId !== selectedEnvironmentId || selectedView.closeInFlight
      || selectedView.entryUrl == null || forceCloseViewId != null) {
      return
    }
    const binding = selectedView
    function onMessage(event: MessageEvent) {
      const frameWindow = activeFrameRef.current?.contentWindow
      if (event.origin !== window.location.origin || frameWindow == null || event.source !== frameWindow) {
        return
      }
      const message: unknown = event.data
      if (message == null || typeof message !== 'object' || Array.isArray(message)) {
        return
      }
      const data = message as Record<string, unknown>
      if (data.type !== 'kk-studio.ide.native-action' || data.viewId !== binding.viewId
        || data.bindingEpoch !== binding.bindingEpoch || pendingCommands.current.has(binding.viewId)) {
        return
      }
      const allowedKeys = data.action === 'open-folder'
        ? ['type', 'viewId', 'bindingEpoch', 'action', 'directory']
        : ['type', 'viewId', 'bindingEpoch', 'action']
      const keys = Object.keys(data)
      if (keys.length !== allowedKeys.length || keys.some((key) => !allowedKeys.includes(key))) {
        return
      }
      switch (data.action) {
        case 'open-folder': {
          if (!isIdeDirectory(data.directory)) {
            return
          }
          const { viewId } = binding
          pendingCommands.current.add(viewId)
          setDirectoryError(null)
          void onChangeDirectory(viewId, data.directory).then((result) => {
            if (!result.ok && selectionRef.current === selection
              && isSameBinding(viewsRef.current[viewId], binding)) {
              setDirectoryError({ binding, message: result.message })
            }
          }).finally(() => pendingCommands.current.delete(viewId))
          return
        }
        case 'new-window': {
          if (selectedEnvironment?.ready === true) {
            onOpenNewView(binding.environmentId)
          }
          return
        }
        case 'close-workspace': {
          void handleClose(binding)
        }
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  })

  function handlePanelKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Escape' && !event.defaultPrevented && !event.repeat
      && !event.nativeEvent.isComposing && event.keyCode !== 229 && forceCloseViewId == null) {
      event.preventDefault()
      event.stopPropagation()
      onCollapse()
    }
  }

  const viewBusy = selectedView?.status === 'reconciling' || selectedView?.status === 'binding'
  return (
    <aside
      id="ide-panel"
      className="ide-overlay-panel"
      data-testid="ide-overlay-panel"
      tabIndex={-1}
      hidden={!isOpen}
      inert={!isOpen ? true : undefined}
      onKeyDown={handlePanelKeyDown}
      aria-label={t('ai.ide.title')}
    >
      <header className="ide-toolbar" data-testid="ide-toolbar">
        <Select
          compact
          className="ide-environment-selector"
          value={selectedEnvironmentId ?? ''}
          options={environmentOptions}
          placeholder={t('ai.ide.environmentSelector')}
          aria-label={t('ai.ide.environmentSelector')}
          onChange={onSelectEnvironment}
        />
        <nav className="ide-workspace-tabs" data-testid="ide-tab-strip" aria-label={t('ai.ide.workspaces')}>
          {openedViews.filter((view) => view.environmentId === selectedEnvironmentId).map((view) => {
            const directory = view.directory
            const label = directory?.split(/[\\/]/).filter(Boolean).at(-1) ?? directory ?? t('ai.ide.workspace')
            return (
              <div key={view.viewId} className="ide-workspace-tab" data-testid="ide-tab" data-ide-view={view.viewId}>
                <Button
                  variant="ghost"
                  size="compact"
                  aria-current={view.viewId === selectedViewId ? 'page' : undefined}
                  title={directory ?? label}
                  onClick={() => onSelectView(view.viewId)}
                >
                  <span className="ide-workspace-tab-label">{label}</span>
                </Button>
                <Button
                  variant="ghost"
                  size="compact"
                  onClick={() => { void handleClose(view) }}
                  loading={view.closeInFlight === true}
                  disabled={view.status !== 'ready' || view.bindingEpoch == null}
                  aria-label={`${t('ai.ide.close')} · ${label}`}
                  title={t('ai.ide.close')}
                  data-testid="ide-close-btn"
                >
                  <X size={14} aria-hidden="true" />
                </Button>
              </div>
            )
          })}
          {selectedEnvironmentId != null ? (
            <Button
              variant="ghost"
              size="compact"
              className="ide-open-workspace"
              data-testid="ide-open-workspace-btn"
              aria-label={t('ai.ide.openWorkspace')}
              title={t('ai.ide.openWorkspace')}
              disabled={selectedEnvironment?.ready !== true}
              onClick={() => onOpenNewView(selectedEnvironmentId)}
            >
              +
            </Button>
          ) : null}
        </nav>
        <Button
          variant="ghost"
          size="compact"
          className="ide-toolbar-icon"
          onClick={onCollapse}
          aria-label={t('ai.ide.collapse')}
          title={t('ai.ide.collapse')}
          data-testid="ide-collapse-btn"
        >
          <PanelRightClose className="ide-collapse-desktop" size={14} aria-hidden="true" />
          <ArrowLeft className="ide-collapse-mobile" size={14} aria-hidden="true" />
          <span className="ide-toolbar-action-label">{t('ai.ide.collapse')}</span>
        </Button>
      </header>
      <IdePanelStatusBanners
        storageUnavailable={storageUnavailable}
        recoveryError={recoveryError}
        sessionStatus={sessionStatus}
        connectionState={connectionState}
        selectedView={selectedView}
        selectedEnvironmentId={selectedEnvironmentId}
        viewBusy={viewBusy}
        claimError={selectedView?.errorMessage}
        closeError={selectedView?.closeError}
        terminalError={selectedView?.terminalError}
        onOpenNewView={onOpenNewView}
        onRetryClaim={onRetryClaim}
        onDismissCloseError={onDismissCloseError}
        commandMessage={commandMessage}
      />
      {directoryError != null && selectedView != null && isSameBinding(selectedView, directoryError.binding) ? (
        <div className="ide-panel-error-banner" role="alert">
          <span>{commandMessage(directoryError.message, 'ai.ide.changeDirectoryFailed')}</span>
          <Button variant="ghost" size="compact" onClick={() => setDirectoryError(null)}>{t('ai.ide.dismiss')}</Button>
        </div>
      ) : null}
      <div className="ide-panel-body" data-testid="ide-panel-body">
        {selectedView?.entryUrl == null && viewBusy ? (
          <div className="ide-panel-state" data-testid="ide-opening-state">
            <LoadingSpinner />
            <p>{t(selectedView?.status === 'reconciling' ? 'ai.ide.reconciling' : 'ai.ide.loading')}</p>
          </div>
        ) : null}
        <div className="ide-global-view-stack">
          {openedViews.map((view) => {
            const visible = isOpen && view.viewId === selectedViewId && view.environmentId === selectedEnvironmentId
            const entryUrl = view.entryUrl
            return (
              <div
                className="ide-global-view"
                key={view.viewId}
                data-ide-environment={view.environmentId}
                data-ide-view={view.viewId}
                data-testid="ide-view-layer"
                hidden={!visible}
                inert={!visible ? true : undefined}
              >
                {entryUrl != null ? (
                  <>
                    {!view.iframeLoaded ? (
                      <div className="ide-panel-state" data-testid="ide-iframe-loading">
                        <LoadingSpinner />
                        <p>{t('ai.ide.loading')}</p>
                      </div>
                    ) : null}
                    <iframe
                      ref={visible ? activeFrameRef : undefined}
                      className="ide-panel-iframe"
                      data-testid="ide-iframe"
                      data-ide-environment={view.environmentId}
                      data-ide-view={view.viewId}
                      title={`${t('ai.ide.title')} · ${environments.find(({ id }) => id === view.environmentId)?.name ?? view.environmentId}`}
                      src={entryUrl}
                      sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads allow-modals"
                      allow="clipboard-read; clipboard-write"
                      onLoad={() => onIframeLoaded(view.viewId, entryUrl)}
                    />
                  </>
                ) : null}
              </div>
            )
          })}
        </div>
      </div>
      {forceCloseViewId != null && forceClose != null ? (
        <ConfirmActionModal
          className="ide-confirm-modal"
          modal={{
            title: t('ai.ide.forceCloseTitle'),
            description: t(forceClose.reason === 'control-unavailable'
              ? 'ai.ide.forceCloseControlUnavailableMessage'
              : 'ai.ide.forceCloseUnknownMessage'),
            confirmLabel: t('ai.ide.discardAndClose'),
            tone: 'danger',
            onConfirm: () => { void handleClose(forceClose.binding, true) },
          }}
          pending={discarding}
          onClose={() => { if (!discarding) setForceClose(null) }}
        />
      ) : null}
    </aside>
  )
}
