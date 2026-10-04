import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { EnvironmentCardDTO, EnvironmentInstallConfigDTO, InstallOperatingSystem } from '@/shared/api/contracts/ai-environment'
import { environmentService } from '@/shared/api/environment-service'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { copyTextToClipboard } from './clipboard'
import { detectInstallOS, generateInstallCommand, generateUninstallCommand, validateInstallConfig } from './install-command'
import './environment-install.css'

interface Props {
  environment: EnvironmentCardDTO
  uninstall?: boolean
  onClose: () => void
}

export function EnvironmentInstallModal({ environment, uninstall = false, onClose }: Props) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [os, setOS] = useState<InstallOperatingSystem>(detectInstallOS(navigator.platform))
  const [studioUrl, setStudioUrl] = useState(window.location.origin)
  const [javaHome, setJavaHome] = useState('')
  const [bashExecutable, setBashExecutable] = useState('')
  const [note, setNote] = useState('')
  const [lspEnabled, setLspEnabled] = useState(false)
  const [servers, setServers] = useState('{}')
  const [version, setVersion] = useState(environment.version)
  const [loading, setLoading] = useState(!uninstall)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const active = useRef(false)
  const busy = useRef(false)

  useEffect(() => {
    active.current = true
    let cancelled = false
    if (!uninstall) {
      // Always read current metadata on opening, not a potentially stale list projection.
      void environmentService.getEnvironment(environment.id).then(card => {
        if (cancelled) return
        setVersion(card.version)
        const config = card.installConfig
        if (config != null) {
          validateInstallConfig(config) // Invalid persisted settings must not become defaults.
          setOS(config.operatingSystem)
          setStudioUrl(config.daemon.studioUrl)
          setJavaHome(config.javaHome ?? '')
          setBashExecutable(config.daemon.bashExecutable ?? '')
          setNote(config.daemon.note ?? '')
          setLspEnabled(config.daemon.lsp != null)
          setServers(JSON.stringify(config.daemon.lsp?.servers ?? {}, null, 2))
        }
        setLoading(false)
      }).catch(err => {
        if (cancelled) return
        setError(err instanceof Error ? err.message : String(err))
        // Keep generation disabled until metadata is successfully reloaded.
      })
    }
    return () => { cancelled = true; active.current = false }
  }, [environment.id, uninstall])

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy.current || loading) return
    busy.current = true
    setPending(true)
    setError(null)
    setFeedback(null)
    let saved = false
    try {
      let command: string
      if (uninstall) {
        command = generateUninstallCommand(os)
      } else {
        if (servers.length > 65536 && lspEnabled) throw new Error(t('ai.environment.install.invalidLsp'))
        let parsed: EnvironmentInstallConfigDTO['daemon']['lsp']
        try { parsed = lspEnabled ? { servers: JSON.parse(servers) } : undefined }
        catch { throw new Error(t('ai.environment.install.invalidLsp')) }
        const installConfig = validateInstallConfig({
          operatingSystem: os, javaHome,
          daemon: { studioUrl, bashExecutable, note, lsp: parsed },
        })
        const card = await environmentService.saveInstallConfig(environment.id, version, installConfig)
        saved = true
        void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
        if (!active.current) return
        setVersion(card.version)
        if (!card.installConfig) throw new Error('Missing saved installConfig')
        const token = await environmentService.getRegistrationToken(environment.id)
        if (!active.current) return
        command = generateInstallCommand(card.installConfig, token.registrationToken)
      }
      if (!active.current) return
      const ok = await copyTextToClipboard(command)
      if (!active.current) return
      if (!ok) throw new Error(t('ai.environment.install.clipboardFailed'))
      setFeedback(t(uninstall ? 'ai.environment.install.uninstallCopied' : 'ai.environment.install.savedCopied'))
    } catch (err) {
      if (!active.current) return
      if (isConflictError(err) && !saved) setConflict(presentConflict(err))
      else setError(saved
        ? t('ai.environment.install.savedCopyFailed')
        : err instanceof Error ? err.message : String(err))
    } finally {
      busy.current = false
      if (active.current) setPending(false)
    }
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div className="modal-card environment-install-modal" role="dialog" aria-modal="true"
        aria-label={t(uninstall ? 'ai.environment.uninstall' : 'ai.environment.install')}
        onMouseDown={event => event.stopPropagation()}>
        <ModalHeader title={`${t(uninstall ? 'ai.environment.uninstall' : 'ai.environment.install')} · ${environment.name}`} onClose={onClose} />
        <form onSubmit={event => void submit(event)}>
          <div className="modal-body">
            <p className="confirm-modal-description">{t(uninstall ? 'ai.environment.install.uninstallNotice' : 'ai.environment.install.notice')}</p>
            {loading && !error && <p role="status">{t('ai.environment.loading')}</p>}
            <label className="form-group">
              <span>{t('ai.environment.install.os')}</span>
              <select value={os} onChange={event => setOS(event.target.value as InstallOperatingSystem)} disabled={pending || loading}>
                <option value="linux">Linux</option><option value="macos">macOS</option><option value="windows">Windows</option>
              </select>
            </label>
            {!uninstall && <>
              <label className="form-group">
                <span>{t('ai.environment.install.origin')}</span>
                <input value={studioUrl} onChange={event => setStudioUrl(event.target.value)} disabled={pending || loading} required />
              </label>
              <details>
                <summary>{t('ai.environment.install.optional')}</summary>
                <label className="form-group"><span>Java home (JDK 21)</span><input value={javaHome} onChange={event => setJavaHome(event.target.value)} disabled={pending || loading} /></label>
                <label className="form-group"><span>{t('ai.environment.install.bash')}</span><input value={bashExecutable} onChange={event => setBashExecutable(event.target.value)} disabled={pending || loading} /></label>
                <label className="form-group"><span>{t('ai.environment.install.note')}</span><input value={note} onChange={event => setNote(event.target.value)} maxLength={512} disabled={pending || loading} /></label>
                <label className="form-group"><span><input type="checkbox" checked={lspEnabled} onChange={event => setLspEnabled(event.target.checked)} disabled={pending || loading} /> {t('ai.environment.install.lsp')}</span></label>
                {lspEnabled && <label className="form-group"><span>LSP servers (JSON)</span><textarea rows={8} value={servers} onChange={event => setServers(event.target.value)} disabled={pending || loading} spellCheck={false} /></label>}
              </details>
            </>}
            {error && <p className="field-error" role="alert">{error}</p>}
            {feedback && <p role="status">{feedback}</p>}
          </div>
          <div className="modal-footer">
            <button type="button" className="ghost-btn" onClick={onClose}>{t('ai.environment.close')}</button>
            <button type="submit" className="btn-primary" disabled={pending || loading}>{t(uninstall ? 'ai.environment.install.copyUninstall' : 'ai.environment.install.saveCopy')}</button>
          </div>
        </form>
        <ConflictPresenter conflict={conflict} onClose={() => setConflict(null)} onRefresh={() => {
          // Rebase only the version. Never replace a user's draft on CAS conflict.
          void environmentService.getEnvironment(environment.id).then(card => {
            if (!active.current) return
            setVersion(card.version)
            setConflict(null)
            void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
          }).catch(err => { if (active.current) setError(err instanceof Error ? err.message : String(err)) })
        }} />
      </div>
    </ModalBackdrop>
  )
}
