import { useLayoutEffect, useRef, useState, type ComponentPropsWithRef } from 'react'
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
  const candidates =
    plugin.authKind?.type === 'DEEP_LINK'
      ? plugin.authKind.regionCandidates
      : []

  const [selectedRegion, setSelectedRegion] = useState(
    plugin.region && candidates.includes(plugin.region)
      ? plugin.region
      : candidates[0] || '',
  )
  const [authAttemptId, setAuthAttemptId] = useState<string | null>(null)
  const [callbackUrl, setCallbackUrl] = useState('')
  const [prepareError, setPrepareError] = useState<string | null>(null)
  const [completeError, setCompleteError] = useState<string | null>(null)
  const [loginOpened, setLoginOpened] = useState(false)
  const [preparePending, setPreparePending] = useState(false)
  const [completePending, setCompletePending] = useState(false)

  const inputRef = useRef<HTMLInputElement>(null)
  const inputRefProps: ComponentPropsWithRef<'input'> = { ref: inputRef }
  const mountedRef = useRef(false)
  const busyRef = useRef(false)

  useLayoutEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      // 卸载时立即销毁所有临时敏感内存状态，绝不进入全局缓存
      setAuthAttemptId(null)
      setCallbackUrl('')
    }
  }, [])

  const pending = preparePending || completePending
  const hasNoCandidates = candidates.length === 0

  async function handleOpenLogin() {
    if (pending || busyRef.current || hasNoCandidates || !selectedRegion) {
      return
    }
    busyRef.current = true
    setPreparePending(true)
    setPrepareError(null)
    // 开始新 prepare 时清空旧 nonce，绝不复活旧 attemptId
    setAuthAttemptId(null)

    try {
      const data = await pluginsService.prepareAuth(plugin.pluginId, { region: selectedRegion })
      if (!mountedRef.current) return
      setAuthAttemptId(data.authAttemptId)
      if (data.loginUrl) {
        window.open(data.loginUrl, '_blank', 'noopener,noreferrer')
        setLoginOpened(true)
      }
    } catch (err) {
      if (!mountedRef.current) return
      setPrepareError(err instanceof Error ? err.message : String(err))
      setAuthAttemptId(null)
    } finally {
      if (mountedRef.current) {
        setPreparePending(false)
      }
      busyRef.current = false
    }
  }

  async function handleComplete(event: React.FormEvent) {
    event.preventDefault()
    if (pending || busyRef.current) return
    const trimmed = callbackUrl.trim()
    if (!trimmed) {
      return
    }
    if (!authAttemptId) {
      setCompleteError(t('plugins.connectDialog.beforeLoginHint'))
      return
    }

    const currentAttemptId = authAttemptId
    busyRef.current = true
    setCompletePending(true)
    setCompleteError(null)
    // complete 消费该 nonce，一旦尝试提交立即作废旧 attemptId，防止重试使用已污染 nonce
    setAuthAttemptId(null)
    // 提交后立即清字段，不在屏幕常驻
    setCallbackUrl('')

    try {
      await pluginsService.completeAuth(plugin.pluginId, {
        authAttemptId: currentAttemptId,
        callbackUrl: trimmed,
      })
      if (!mountedRef.current) return
      onSuccess()
    } catch (err) {
      if (!mountedRef.current) return
      setCompleteError(err instanceof Error ? err.message : String(err))
      setCallbackUrl('')
      inputRef.current?.focus()
    } finally {
      if (mountedRef.current) {
        setCompletePending(false)
      }
      busyRef.current = false
    }
  }

  return (
    <Dialog
      title={t('plugins.connectDialog.title', { name: plugin.name })}
      onClose={() => {
        if (!pending) {
          setAuthAttemptId(null)
          setCallbackUrl('')
          onClose()
        }
      }}
      pending={pending}
      className="plugin-connect-modal"
    >
      <div className="plugin-connect-body">

        {hasNoCandidates ? (
          <div className="callout callout-warning" role="alert">
            {t('plugins.noRegionsAvailable')}
          </div>
        ) : null}

        {candidates.length > 1 ? (
          <div className="form-group">
            <FieldLabel>{t('plugins.region')}</FieldLabel>
            <Select
              value={selectedRegion}
              options={candidates.map((c) => ({ value: c, label: c }))}
              onChange={(next) => {
                setSelectedRegion(next)
                setAuthAttemptId(null)
                setLoginOpened(false)
              }}
              disabled={pending}
              aria-label={t('plugins.region')}
            />
          </div>
        ) : null}

        {/* 第一步：打开官方登录页面 */}
        <div className="plugin-connect-step">
          <div className="step-badge">1</div>
          <div className="step-content">
            <p className="step-desc">
              {t('plugins.connectDialog.step1')}
            </p>
            <Button
              type="button"
              variant="ghost"
              className="btn-outline"
              disabled={pending || hasNoCandidates || !selectedRegion}
              onClick={handleOpenLogin}
              aria-label={
                preparePending
                  ? t('plugins.connectDialog.preparing')
                  : t('plugins.connectDialog.openLogin')
              }
            >
              <ExternalLink size={14} aria-hidden="true" style={{ marginRight: 6 }} />
              {preparePending
                ? t('plugins.connectDialog.preparing')
                : t('plugins.connectDialog.openLogin')}
            </Button>
            {prepareError ? (
              <span className="inline-hint danger" role="alert">
                {prepareError}
              </span>
            ) : null}
          </div>
        </div>

        {/* 第二步：填入回跳 URL 并完成连接 */}
        <form onSubmit={handleComplete} className="plugin-connect-step">
          <div className="step-badge">2</div>
          <div className="step-content">
            <p className="step-desc">
              {t('plugins.connectDialog.step2')}
            </p>
            <div className="form-group">
              <FieldLabel required>{t('plugins.connectDialog.step2')}</FieldLabel>
              <TextInput
                {...inputRefProps}
                type="password"
                autoComplete="off"
                data-testid="plugin-callback-input"
                aria-label={t('plugins.connectDialog.step2')}
                placeholder={t('plugins.connectDialog.callbackPlaceholder')}
                value={callbackUrl}
                disabled={pending}
                onChange={(e) => {
                  setCallbackUrl(e.target.value)
                  setCompleteError(null)
                }}
              />
              <span className="inline-hint">
                {loginOpened
                  ? t('plugins.connectDialog.afterLoginHint')
                  : t('plugins.connectDialog.beforeLoginHint')}
              </span>
              {completeError ? (
                <span className="inline-hint danger" role="alert">
                  {completeError}
                </span>
              ) : null}
            </div>

            <div className="modal-footer plugin-connect-footer">
              <Button
                type="button"
                variant="ghost"
                disabled={pending}
                onClick={() => {
                  setAuthAttemptId(null)
                  setCallbackUrl('')
                  onClose()
                }}
              >
                {t('plugins.connectDialog.cancel')}
              </Button>
              <Button
                type="submit"
                variant="primary"
                disabled={
                  pending ||
                  !callbackUrl.trim() ||
                  !authAttemptId
                }
              >
                {completePending
                  ? t('plugins.connectDialog.completing')
                  : t('plugins.connectDialog.complete')}
              </Button>
            </div>
          </div>
        </form>
      </div>
    </Dialog>
  )
}
