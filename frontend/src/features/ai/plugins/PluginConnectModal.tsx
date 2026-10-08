import { useLayoutEffect, useRef, useState, type ComponentPropsWithRef } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { Select } from '@/shared/ui/controls/Select'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { pluginsService } from '@/shared/api/plugins-service'
import { useI18n } from '@/shared/i18n'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'
import './plugin-connect.css'

interface PluginConnectModalProps {
  plugin: PluginDTO
  onClose: () => void
  onSuccess: () => void
}

export function PluginConnectModal(props: PluginConnectModalProps) {
  // 换插件即换实例，旧请求的生命周期不能进入新弹层。
  return <PluginConnection key={props.plugin.pluginId} {...props} />
}

function PluginConnection({
  plugin,
  onClose,
  onSuccess,
}: PluginConnectModalProps) {
  const { t } = useI18n()
  const candidates = plugin.authKind?.regionCandidates ?? ['CN']
  const [selectedRegion, setSelectedRegion] = useState(plugin.region || candidates[0] || 'CN')
  const [callbackUrl, setCallbackUrl] = useState('')
  const [prepareError, setPrepareError] = useState<string | null>(null)
  const [completeError, setCompleteError] = useState<string | null>(null)
  const [loginOpened, setLoginOpened] = useState(false)

  const inputRef = useRef<HTMLInputElement>(null)
  // React 19 将 ref 当普通 prop；共享输入会通过原生属性透传给 input。
  const inputRefProps: ComponentPropsWithRef<'input'> = { ref: inputRef }
  const mountedRef = useRef(false)
  // 同一事件批次内也禁止重复提交或跨阶段并行，直到 mutation settled。
  const busyRef = useRef(false)

  useLayoutEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  const prepareMutation = useMutation({
    mutationFn: (region: string) =>
      pluginsService.prepareAuth(plugin.pluginId, { region }),
    retry: false,
    onSuccess: (data) => {
      if (!mountedRef.current) return
      setPrepareError(null)
      if (data.loginUrl) {
        window.open(data.loginUrl, '_blank', 'noopener,noreferrer')
        setLoginOpened(true)
      }
    },
    onError: (err: unknown) => {
      if (!mountedRef.current) return
      setPrepareError(err instanceof Error ? err.message : String(err))
    },
    onSettled: () => { busyRef.current = false },
  })

  const completeMutation = useMutation({
    mutationFn: (url: string) =>
      pluginsService.completeAuth(plugin.pluginId, { callbackUrl: url }),
    retry: false, // 同一 callback 请求不自动重试
    onSuccess: () => {
      if (!mountedRef.current) return
      onSuccess()
    },
    onError: (err: unknown) => {
      if (!mountedRef.current) return
      setCompleteError(err instanceof Error ? err.message : String(err))
    },
    onSettled: () => { busyRef.current = false },
  })

  const pending = prepareMutation.isPending || completeMutation.isPending
  const isKeyUnavailable = plugin.status === 'KEY_UNAVAILABLE'

  function handleOpenLogin() {
    if (pending || busyRef.current || isKeyUnavailable) return
    busyRef.current = true
    setPrepareError(null)
    prepareMutation.mutate(selectedRegion)
  }

  function handleComplete(event: React.FormEvent) {
    event.preventDefault()
    if (pending || busyRef.current || isKeyUnavailable) return
    const trimmed = callbackUrl.trim()
    if (!trimmed) {
      return
    }
    busyRef.current = true
    // 在请求开始前即清空 DOM 和受控草稿；失败也不恢复凭据。
    if (inputRef.current) inputRef.current.value = ''
    setCallbackUrl('')
    setCompleteError(null)
    completeMutation.mutate(trimmed)
  }

  function handleClose() {
    if (!pending && !busyRef.current) onClose()
  }

  return (
    <Dialog
      className="resource-modal-card"
      title={t('plugins.connectDialog.title', { name: plugin.name })}
      onClose={handleClose}
      pending={pending}
    >
        <div className="modal-body">
          {isKeyUnavailable ? (
            <div className="form-error-banner" role="alert">
              {t('plugins.connectDialog.keyUnavailableHint')}
            </div>
          ) : null}

          {prepareError ? (
            <div className="form-error-banner" role="alert">
              {prepareError}
            </div>
          ) : null}

          {completeError ? (
            <div className="form-error-banner" role="alert">
              {completeError}
            </div>
          ) : null}

          {/* 步骤 1：选择区域并获取登录链接 */}
          <div className="form-group plugin-connect-step">
            <FieldLabel required>{t('plugins.connectDialog.step1')}</FieldLabel>
            <div className="plugin-connect-login">
              <div className="plugin-connect-region">
                <Select
                  aria-label={t('plugins.region')}
                  value={selectedRegion}
                  disabled={isKeyUnavailable || pending}
                  options={candidates.map((candidate) => ({ value: candidate, label: candidate }))}
                  onChange={(region) => {
                    if (!pending && !busyRef.current && !isKeyUnavailable) setSelectedRegion(region)
                  }}
                />
              </div>
              <Button
                disabled={isKeyUnavailable || pending}
                onClick={handleOpenLogin}
              >
                <ExternalLink size={16} aria-hidden="true" />
                {prepareMutation.isPending ? t('plugins.connectDialog.preparing') : t('plugins.connectDialog.openLogin')}
              </Button>
            </div>
          </div>

          {/* 步骤 2：粘贴回调 Deep Link */}
          <form onSubmit={handleComplete} noValidate>
            <div className="form-group">
              <FieldLabel required>{t('plugins.connectDialog.step2')}</FieldLabel>
              <TextInput
                {...inputRefProps}
                type="password"
                aria-label={t('plugins.connectDialog.step2')}
                autoComplete="off"
                data-testid="plugin-callback-input"
                value={callbackUrl}
                disabled={isKeyUnavailable || pending}
                placeholder={t('plugins.connectDialog.callbackPlaceholder')}
                onChange={(e) => {
                  if (!pending && !busyRef.current && !isKeyUnavailable) setCallbackUrl(e.target.value)
                }}
                required
              />
              <span className="inline-hint plugin-connect-hint">
                {loginOpened
                  ? t('plugins.connectDialog.afterLoginHint')
                  : t('plugins.connectDialog.beforeLoginHint')}
              </span>
            </div>

            <div className="modal-footer plugin-connect-footer">
              <Button
                variant="inline"
                onClick={handleClose}
                disabled={pending}
              >
                {t('plugins.connectDialog.cancel')}
              </Button>
              <Button
                type="submit"
                disabled={isKeyUnavailable || !callbackUrl.trim() || pending}
              >
                {completeMutation.isPending ? t('plugins.connectDialog.completing') : t('plugins.connectDialog.complete')}
              </Button>
            </div>
          </form>
        </div>
    </Dialog>
  )
}
