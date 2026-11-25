import { useContext, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { PaneRendererProps } from '@/features/workspace/pane-target'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { environmentDisplayStatus } from '@/features/environment/environment-utils'
import { useIdeWorkspace, type VsCodeTarget } from './ide-workspace-context'
import { IdePaneCloseContext } from './ide-pane-close'
import { IdePanelStatusBanners } from './IdePanelStatusBanners'
import { isSameBinding, type IdeViewState } from './ide-view-state'
import { isIdeDirectory } from './ide-session-state'
import './vscode-pane.css'

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

export function VsCodePaneRenderer(props: PaneRendererProps<VsCodeTarget>) {
  return <Workbench key={props.target.viewId} {...props} />
}

function Workbench({ target, openBeside, replace }: PaneRendererProps<VsCodeTarget>) {
  const { t } = useI18n()
  const ide = useIdeWorkspace()
  const closeHost = useContext(IdePaneCloseContext)
  const { viewId, environmentId } = target
  const view = ide.views[viewId]
  const environment = ide.environments.find(({ id }) => id === environmentId)
  const frame = useRef<HTMLIFrameElement>(null)
  const latest = useRef(ide)
  const closeDecision = useRef<((discard: boolean) => void) | null>(null)
  const commandPending = useRef(false)
  const [force, setForce] = useState<{ view: IdeViewState; unavailable: boolean } | null>(null)
  const [directoryError, setDirectoryError] = useState<{ view: IdeViewState; message?: string } | null>(null)
  useLayoutEffect(() => { latest.current = ide }, [ide])
  useEffect(() => {
    let mounted = true
    queueMicrotask(() => { if (mounted) latest.current.ensureView(environmentId, viewId) })
    return () => { mounted = false }
  }, [environmentId, viewId, ide.sessionStatus])
  useEffect(() => () => {
    closeDecision.current?.(false)
    latest.current.releaseFrame(viewId)
  }, [viewId])

  function commandMessage(code: string | undefined, fallback: TranslationKey) {
    return code == null ? t(fallback) : COMMAND_MESSAGES[code] ? t(COMMAND_MESSAGES[code]) : code
  }

  function completeDecision(discard: boolean) {
    closeDecision.current?.(discard)
    closeDecision.current = null
    setForce(null)
  }

  const forceStillValid = force != null && isSameBinding(view, force.view)
  useEffect(() => {
    if (force != null && !forceStillValid) completeDecision(false)
  }, [force, forceStillValid])

  useLayoutEffect(() => {
    async function close(): Promise<boolean> {
      const binding = latest.current.views[viewId]
      if (binding == null) return true
      if (commandPending.current) return false
      commandPending.current = true
      try {
        const result = await latest.current.closeEnvironment(viewId, false)
        if (result.ok || !result.requiresForceConfirmation || !isSameBinding(latest.current.views[viewId], binding)) {
          return result.ok
        }
        const discard = await new Promise<boolean>((resolve) => {
          closeDecision.current = resolve
          setForce({ view: binding, unavailable: result.message === 'close-control-unavailable' })
        })
        if (!discard || !isSameBinding(latest.current.views[viewId], binding)) return false
        return (await latest.current.closeEnvironment(viewId, true)).ok
      } finally {
        commandPending.current = false
      }
    }
    const unregister = closeHost?.register(viewId, close)
    function onMessage(event: MessageEvent) {
      const binding = latest.current.views[viewId]
      if (binding?.status !== 'ready' || binding.environmentId !== environmentId || binding.closeInFlight
        || commandPending.current || binding.entryUrl == null || event.origin !== window.location.origin
        || frame.current?.contentWindow == null || event.source !== frame.current.contentWindow) return
      const data: unknown = event.data
      if (data == null || typeof data !== 'object' || Array.isArray(data)) return
      const message = data as Record<string, unknown>
      if (message.type !== 'kk-studio.ide.native-action' || message.viewId !== viewId
        || message.bindingEpoch !== binding.bindingEpoch) return
      const keys = message.action === 'open-folder'
        ? ['type', 'viewId', 'bindingEpoch', 'action', 'directory'] : ['type', 'viewId', 'bindingEpoch', 'action']
      if (Object.keys(message).length !== keys.length || keys.some((key) => !Object.hasOwn(message, key))) return
      if (message.action === 'open-folder' && isIdeDirectory(message.directory)) {
        commandPending.current = true
        setDirectoryError(null)
        void latest.current.changeDirectory(viewId, message.directory).then((result) => {
          if (!result.ok && isSameBinding(latest.current.views[viewId], binding)) {
            setDirectoryError({ view: binding, message: result.message })
          }
        }).finally(() => { commandPending.current = false })
      } else if (message.action === 'new-window') {
        const current = latest.current.environments.find(({ id }) => id === environmentId)
        if (current != null && environmentDisplayStatus(current) === 'READY') {
          openBeside({ kind: 'vscode', environmentId, viewId: createUuid() })
        }
      } else if (message.action === 'close-workspace') {
        void close().then((ok) => { if (ok) closeHost?.remove(viewId) })
      }
    }
    window.addEventListener('message', onMessage)
    return () => { unregister?.(); window.removeEventListener('message', onMessage) }
  }, [closeHost, viewId, environmentId, openBeside])

  const entryUrl = view?.entryUrl
  const busy = view?.status === 'reconciling' || view?.status === 'binding'
  return (
    <div className="vscode-pane">
      <IdePanelStatusBanners
        storageUnavailable={ide.storageUnavailable}
        recoveryError={ide.recoveryError}
        sessionStatus={ide.sessionStatus}
        connectionState={ide.connectionState}
        selectedView={view ?? null}
        selectedEnvironmentId={environmentId}
        viewBusy={busy}
        claimError={view?.errorMessage}
        terminalError={view?.terminalError}
        onOpenNewView={() => {
          if (environment != null && environmentDisplayStatus(environment) === 'READY') {
            replace({ kind: 'vscode', environmentId, viewId: createUuid() })
          }
        }}
        onRetryClaim={() => ide.retryClaim(viewId)}
        onDismissCloseError={ide.dismissCloseError}
        commandMessage={commandMessage}
      />
      {directoryError != null && isSameBinding(view, directoryError.view) && (
        <div className="ide-panel-error-banner" role="alert">
          {commandMessage(directoryError.message, 'ai.ide.changeDirectoryFailed')}
          <Button variant="secondary" size="compact" onClick={() => setDirectoryError(null)}>{t('ai.ide.dismiss')}</Button>
        </div>
      )}
      <div className="vscode-workbench">
        {(busy && entryUrl == null || entryUrl != null && !view?.iframeLoaded) && (
          <div className="vscode-loading" role="status"><LoadingSpinner />{t('ai.ide.loading')}</div>
        )}
        {entryUrl != null && (
          <iframe
            ref={frame}
            data-testid="ide-iframe"
            data-ide-view={viewId}
            title={`${t('ai.ide.title')} · ${environment?.name ?? environmentId}`}
            src={entryUrl}
            sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads allow-modals"
            allow="clipboard-read; clipboard-write"
            onLoad={() => ide.handleIframeLoaded(viewId, entryUrl)}
          />
        )}
      </div>
      {forceStillValid && (
        <ConfirmActionModal
          modal={{
            title: t('ai.ide.forceCloseTitle'),
            description: t(force.unavailable ? 'ai.ide.forceCloseControlUnavailableMessage' : 'ai.ide.forceCloseUnknownMessage'),
            confirmLabel: t('ai.ide.discardAndClose'),
            tone: 'danger',
            onConfirm: () => completeDecision(true),
          }}
          pending={false}
          onClose={() => completeDecision(false)}
        />
      )}
    </div>
  )
}
