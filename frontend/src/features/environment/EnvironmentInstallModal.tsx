import { useEffect, useRef, useState } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import { Select } from '@/shared/ui/controls/Select'
import { Toast } from '@/shared/ui/feedback/Toast'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n } from '@/shared/i18n'
import { copyTextToClipboard } from '@/shared/lib/clipboard'
import { isConflictError } from '@/shared/api/client'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { environmentService } from '@/features/environment/environment-service'
import {
  INSTALL_OPERATING_SYSTEMS,
  detectInstallOS,
  generateInstallCommand,
  generateUninstallCommand,
  type InstallOperatingSystem,
} from './install-command'
import './environment-install.css'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'

/** 选择器的操作系统取值来自生成契约；名称只属于界面展示。 */
const OPERATING_SYSTEM_LABELS: Record<InstallOperatingSystem, string> = {
  linux: 'Linux',
  macos: 'macOS',
  windows: 'Windows',
}
const OPERATING_SYSTEM_OPTIONS = INSTALL_OPERATING_SYSTEMS.map((value) => ({
  value,
  label: OPERATING_SYSTEM_LABELS[value],
}))

export interface EnvironmentInstallModalProps {
  environment: EnvironmentDTO
  uninstall?: boolean
  onClose: () => void
}

/**
 * 安装/卸载命令弹窗只选择目标 OS，然后签发一次性安装 code 或直接生成按卡片卸载命令。
 * 卡片配置不在弹窗内编辑；安装脚本由服务端在兑换 code 时写入完整配置与新 token。
 */
export function EnvironmentInstallModal({
  environment,
  uninstall = false,
  onClose,
}: EnvironmentInstallModalProps) {
  const { t } = useI18n()
  const [os, setOS] = useState<InstallOperatingSystem>(() => detectInstallOS(navigator.platform))
  const [version, setVersion] = useState(environment.version)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [feedback, setFeedback] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const active = useRef(true)
  const busy = useRef(false)
  const disabled = pending

  useEffect(() => {
    return () => {
      active.current = false
    }
  }, [])

  async function copyCommand(command: string) {
    if (!active.current) return
    const copied = await copyTextToClipboard(command)
    if (!active.current) return
    if (!copied) {
      setError(t('ai.environment.install.clipboardFailed'))
      return
    }
    setFeedback(
      t(
        uninstall
          ? 'ai.environment.install.uninstallCopied'
          : 'ai.environment.install.installCopied',
      ),
    )
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy.current || disabled) return
    busy.current = true
    setPending(true)
    setError(null)
    setFeedback(null)
    try {
      const downloadOrigin = window.location.origin
      if (uninstall) {
        await copyCommand(
          generateUninstallCommand(downloadOrigin, environment.id, os),
        )
        return
      }
      const issued = await environmentService.createInstallCode(environment.id, version)
      if (!active.current) return
      await copyCommand(
        generateInstallCommand(downloadOrigin, environment.id, os, issued.code),
      )
    } catch (err) {
      if (!active.current) return
      if (!uninstall && isConflictError(err)) {
        setConflict(presentConflict(err))
        return
      }
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      busy.current = false
      if (active.current) setPending(false)
    }
  }

  /** 冲突刷新只更新期望版本；OS 选择保持不变，重新签发安装 code。 */
  async function refreshVersion() {
    try {
      const fresh = await environmentService.getEnvironment(environment.id)
      if (!active.current) return
      setVersion(fresh.version)
      setConflict(null)
    } catch (err) {
      if (!active.current) return
      setConflict(null)
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <Dialog
      className="environment-install-modal modal-touch-target"
      title={
        t(uninstall ? 'ai.environment.install.uninstallTitle' : 'ai.environment.install.title')
      }
      pending={pending}
      onClose={onClose}
    >
      <form className="modal-card-form" onSubmit={(event) => void submit(event)}>
        <div className="modal-body">
          <p className="modal-body-text">
            {t(
              uninstall
                ? 'ai.environment.install.uninstallNotice'
                : 'ai.environment.install.notice',
            )}
          </p>
          {!uninstall && (
            <p className="field-help install-notice-detail">
              {t('ai.environment.install.noticeDetail')}
            </p>
          )}
          <div className="form-group">
            <span>{t('ai.environment.install.os')}</span>
            <Select
              aria-label={t('ai.environment.install.os')}
              value={os}
              onChange={(value) => setOS(value as InstallOperatingSystem)}
              disabled={disabled}
              options={OPERATING_SYSTEM_OPTIONS}
            />
          </div>
          {error && (
            <p className="field-error" role="alert">
              {error}
            </p>
          )}
          {!uninstall && (
            <p className="field-help install-credential-note">
              {t('ai.environment.install.credentialNote')}
            </p>
          )}
        </div>
        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose}>
            {t('ai.environment.close')}
          </Button>
          <Button type="submit" disabled={disabled}>
            {t(
              uninstall
                ? 'ai.environment.install.copyUninstall'
                : 'ai.environment.install.copyInstall',
            )}
          </Button>
        </div>
      </form>
      {feedback && <Toast message={feedback} onDismiss={() => setFeedback(null)} />}
      <ConflictPresenter
        className="modal-touch-target"
        conflict={conflict}
        onClose={() => setConflict(null)}
        onRefresh={() => {
          void refreshVersion()
        }}
      />
    </Dialog>
  )
}
