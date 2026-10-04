import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type {
  EnvironmentCardDTO,
  EnvironmentInstallConfigDTO,
  InstallOperatingSystem,
} from '@/shared/api/contracts/ai-environment'
import { environmentService } from '@/shared/api/environment-service'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { Select } from '@/shared/ui/console/Select'
import { Checkbox } from '@/shared/ui/console/Checkbox'
import { Toast } from '@/shared/ui/console/Toast'
import { useI18n, type InterpolationValues } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { copyTextToClipboard } from './clipboard'
import {
  InstallConfigError,
  detectInstallOS,
  generateInstallCommand,
  generateUninstallCommand,
  validateInstallConfig,
} from './install-command'
import './environment-install.css'

type Translate = (key: string, values?: InterpolationValues) => string

/** 元数据读取失败：传输错误可重试；已保存设置无效可重试或改用默认设置。 */
type LoadError =
  | { kind: 'metadata'; message: string }
  | { kind: 'invalid'; field: string }

interface Props {
  environment: EnvironmentCardDTO
  uninstall?: boolean
  onClose: () => void
}

/** 只携带字段路径，避免把英文内部错误直接展示给用户。 */
function fieldMessage(t: Translate, field: string): string {
  if (field === 'daemon.studioUrl') return t('ai.environment.install.invalidOrigin')
  if (field === 'installConfig.javaHome') return t('ai.environment.install.invalidJavaHome')
  if (field === 'daemon.note') return t('ai.environment.install.invalidNote')
  if (field === 'daemon.bashExecutable') return t('ai.environment.install.invalidBash')
  if (field.startsWith('daemon.lsp')) return t('ai.environment.install.invalidLsp')
  if (field.endsWith('operatingSystem')) return t('ai.environment.install.invalidOs')
  return t('ai.environment.install.invalidField', { field })
}

const blankToNull = (value: string): string | null => (value.trim() ? value : null)

/** 已保存设置 → 表单字段；仅在加载成功或用户选择默认设置时应用。 */
function formFromConfig(config: EnvironmentInstallConfigDTO) {
  return {
    os: config.operatingSystem,
    studioUrl: config.daemon.studioUrl,
    javaHome: config.javaHome ?? '',
    bashExecutable: config.daemon.bashExecutable ?? '',
    note: config.daemon.note ?? '',
    lspEnabled: config.daemon.lsp != null,
    servers: JSON.stringify(config.daemon.lsp?.servers ?? {}, null, 2),
  }
}

export function EnvironmentInstallModal({ environment, uninstall = false, onClose }: Props) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const savedOS = environment.installConfig?.operatingSystem
  const [os, setOS] = useState<InstallOperatingSystem>(savedOS ?? detectInstallOS(navigator.platform))
  const [studioUrl, setStudioUrl] = useState(window.location.origin)
  const [javaHome, setJavaHome] = useState('')
  const [bashExecutable, setBashExecutable] = useState('')
  const [note, setNote] = useState('')
  const [lspEnabled, setLspEnabled] = useState(false)
  const [servers, setServers] = useState('{}')
  const [version, setVersion] = useState(environment.version)
  const [loadKey, setLoadKey] = useState(0)
  const [loading, setLoading] = useState(!uninstall)
  const [loadError, setLoadError] = useState<LoadError | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const active = useRef(false)
  const busy = useRef(false)
  const disabled = pending || loading || loadError !== null

  useEffect(() => {
    active.current = true
    let cancelled = false
    if (uninstall) {
      setLoading(false)
      return () => {
        cancelled = true
        active.current = false
      }
    }
    setLoading(true)
    setLoadError(null)
    environmentService
      .getEnvironment(environment.id)
      .then(card => {
        if (cancelled) return
        setVersion(card.version)
        const config = card.installConfig
        if (config != null) {
          try {
            const form = formFromConfig(validateInstallConfig(config))
            setOS(form.os)
            setStudioUrl(form.studioUrl)
            setJavaHome(form.javaHome)
            setBashExecutable(form.bashExecutable)
            setNote(form.note)
            setLspEnabled(form.lspEnabled)
            setServers(form.servers)
          } catch (err) {
            // Invalid persisted settings must be repaired, not masked as defaults.
            setLoadError({
              kind: 'invalid',
              field: err instanceof InstallConfigError ? err.field : 'installConfig',
            })
            setLoading(false)
            return
          }
        }
        setLoading(false)
      })
      .catch(err => {
        if (cancelled) return
        setLoadError({ kind: 'metadata', message: err instanceof Error ? err.message : String(err) })
        setLoading(false)
      })
    return () => {
      cancelled = true
      active.current = false
    }
  }, [environment.id, uninstall, loadKey])

  /** 只刷新 CAS 版本，绝不覆盖用户草稿。 */
  async function rebaseVersion() {
    try {
      const fresh = await environmentService.getEnvironment(environment.id)
      if (active.current) setVersion(fresh.version)
    } catch (err) {
      if (active.current) setError(err instanceof Error ? err.message : String(err))
    }
  }

  /** 显式放弃无效的已保存设置，改用 origin/OS 默认值；保留刚读取到的版本。 */
  function useDefaultSettings() {
    const fallback = savedOS ?? detectInstallOS(navigator.platform)
    setOS(fallback)
    setStudioUrl(window.location.origin)
    setJavaHome('')
    setBashExecutable('')
    setNote('')
    setLspEnabled(false)
    setServers('{}')
    setLoadError(null)
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy.current || disabled) return
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
        let parsedLsp: EnvironmentInstallConfigDTO['daemon']['lsp']
        if (lspEnabled) {
          try {
            parsedLsp = { servers: JSON.parse(servers) }
          } catch {
            throw new InstallConfigError('daemon.lsp.servers')
          }
        }
        const installConfig = validateInstallConfig({
          operatingSystem: os,
          javaHome: blankToNull(javaHome),
          daemon: {
            studioUrl,
            bashExecutable: blankToNull(bashExecutable),
            note: blankToNull(note),
            lsp: parsedLsp,
          },
        })
        const savedCard = await environmentService.saveInstallConfig(
          environment.id,
          version,
          installConfig,
        )
        saved = true
        void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
        if (!active.current) return
        setVersion(savedCard.version)
        if (savedCard.installConfig == null) throw new InstallConfigError('installConfig')
        const token = await environmentService.getRegistrationToken(environment.id)
        if (!active.current) return
        if (token.version !== savedCard.version) {
          // Saved settings exist, but the environment changed while reading the credential.
          setError(t('ai.environment.install.savedStale'))
          await rebaseVersion()
          return
        }
        command = generateInstallCommand(savedCard.installConfig, token.registrationToken)
      }
      if (!active.current) return
      const copied = await copyTextToClipboard(command)
      if (!active.current) return
      if (!copied) {
        setError(
          saved
            ? t('ai.environment.install.savedCopyFailed')
            : t('ai.environment.install.clipboardFailed'),
        )
        return
      }
      setFeedback(
        t(uninstall ? 'ai.environment.install.uninstallCopied' : 'ai.environment.install.savedCopied'),
      )
    } catch (err) {
      if (!active.current) return
      // Once the PUT succeeded, any later failure is a generation/copy problem, not a draft problem.
      if (isConflictError(err) && !saved) setConflict(presentConflict(err))
      else if (saved) setError(t('ai.environment.install.savedCopyFailed'))
      else if (err instanceof InstallConfigError) setError(fieldMessage(t, err.field))
      else setError(err instanceof Error ? err.message : String(err))
    } finally {
      busy.current = false
      if (active.current) setPending(false)
    }
  }

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card environment-install-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t(uninstall ? 'ai.environment.uninstall' : 'ai.environment.install')}
        onMouseDown={event => event.stopPropagation()}
      >
        <ModalHeader
          title={`${t(uninstall ? 'ai.environment.uninstall' : 'ai.environment.install')} · ${environment.name}`}
          onClose={onClose}
        />
        <form onSubmit={event => void submit(event)}>
          <div className="modal-body">
            <p className="confirm-modal-description">
              {t(uninstall ? 'ai.environment.install.uninstallNotice' : 'ai.environment.install.notice')}
            </p>
            {loading && !loadError && <p role="status">{t('ai.environment.loading')}</p>}
            {loadError && (
              <div className="field-error" role="alert">
                <p>
                  {loadError.kind === 'metadata'
                    ? t('ai.environment.install.metadataFailed', { message: loadError.message })
                    : t('ai.environment.install.invalidSaved', {
                        reason: fieldMessage(t, loadError.field),
                      })}
                </p>
                <div className="install-load-actions">
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => setLoadKey(key => key + 1)}
                  >
                    {t('ai.environment.install.reload')}
                  </button>
                  {loadError.kind === 'invalid' && (
                    <button type="button" className="ghost-btn" onClick={useDefaultSettings}>
                      {t('ai.environment.install.useDefaults')}
                    </button>
                  )}
                </div>
              </div>
            )}
            <div className="form-group">
              <span>{t('ai.environment.install.os')}</span>
              <Select
                aria-label={t('ai.environment.install.os')}
                value={os}
                onChange={value => setOS(value as InstallOperatingSystem)}
                disabled={disabled}
                options={[
                  { value: 'linux', label: 'Linux' },
                  { value: 'macos', label: 'macOS' },
                  { value: 'windows', label: 'Windows' },
                ]}
              />
            </div>
            {!uninstall && (
              <>
                <div className="install-field">
                  <label className="form-group">
                    <span>{t('ai.environment.install.origin')}</span>
                    <input
                      value={studioUrl}
                      onChange={event => setStudioUrl(event.target.value)}
                      aria-describedby="install-origin-help"
                      disabled={disabled}
                      required
                    />
                  </label>
                  <p id="install-origin-help" className="field-help">
                    {t('ai.environment.install.originHelp')}
                  </p>
                </div>
                <details>
                  <summary>{t('ai.environment.install.optional')}</summary>
                  <label className="form-group">
                    <span>Java home (JDK 21)</span>
                    <input
                      value={javaHome}
                      onChange={event => setJavaHome(event.target.value)}
                      disabled={disabled}
                    />
                  </label>
                  <label className="form-group">
                    <span>{t('ai.environment.install.bash')}</span>
                    <input
                      value={bashExecutable}
                      onChange={event => setBashExecutable(event.target.value)}
                      disabled={disabled}
                    />
                  </label>
                  <label className="form-group">
                    <span>{t('ai.environment.install.note')}</span>
                    <input
                      value={note}
                      onChange={event => setNote(event.target.value)}
                      maxLength={512}
                      disabled={disabled}
                    />
                  </label>
                  <Checkbox
                    checked={lspEnabled}
                    onChange={setLspEnabled}
                    disabled={disabled}
                    label={t('ai.environment.install.lsp')}
                  />
                  {lspEnabled && (
                    <div className="install-field">
                      <label className="form-group">
                        <span>LSP servers (JSON)</span>
                        <textarea
                          rows={8}
                          value={servers}
                          onChange={event => setServers(event.target.value)}
                          aria-describedby="install-lsp-help"
                          disabled={disabled}
                          spellCheck={false}
                        />
                      </label>
                      <p id="install-lsp-help" className="field-help">
                        {t('ai.environment.install.lspHelp')}
                      </p>
                    </div>
                  )}
                </details>
              </>
            )}
            {error && (
              <p className="field-error" role="alert">
                {error}
              </p>
            )}
          </div>
          <div className="modal-footer">
            <button type="button" className="ghost-btn" onClick={onClose}>
              {t('ai.environment.close')}
            </button>
            <button type="submit" className="btn-primary" disabled={disabled}>
              {t(uninstall ? 'ai.environment.install.copyUninstall' : 'ai.environment.install.saveCopy')}
            </button>
          </div>
        </form>
        {feedback && <Toast message={feedback} onDismiss={() => setFeedback(null)} />}
        <ConflictPresenter
          conflict={conflict}
          onClose={() => setConflict(null)}
          onRefresh={() => {
            setConflict(null)
            void queryClient.invalidateQueries({ queryKey: queryKeys.environments.all })
            void rebaseVersion()
          }}
        />
      </div>
    </ModalBackdrop>
  )
}
