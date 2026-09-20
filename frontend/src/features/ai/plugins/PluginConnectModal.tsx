import { useEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { pluginsService } from '@/shared/api/plugins-service'
import { useI18n } from '@/shared/i18n'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'

export function PluginConnectModal({
  plugin,
  onClose,
  onSuccess,
}: {
  plugin: PluginDTO
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useI18n()
  const candidates = plugin.authKind?.regionCandidates ?? ['CN']
  const [selectedRegion, setSelectedRegion] = useState(plugin.region || candidates[0] || 'CN')
  const [callbackUrl, setCallbackUrl] = useState('')
  const [prepareError, setPrepareError] = useState<string | null>(null)
  const [completeError, setCompleteError] = useState<string | null>(null)
  const [loginOpened, setLoginOpened] = useState(false)

  // Generation fence: 防止关闭/重开弹窗后的迟到响应覆盖新状态
  const generationRef = useRef(0)

  useEffect(() => {
    generationRef.current += 1
  }, [])

  const prepareMutation = useMutation({
    mutationFn: (region: string) =>
      pluginsService.prepareAuth(plugin.pluginId, { region }),
    onSuccess: (data) => {
      setPrepareError(null)
      if (data.loginUrl) {
        window.open(data.loginUrl, '_blank', 'noopener,noreferrer')
        setLoginOpened(true)
      }
    },
    onError: (err: unknown) => {
      setPrepareError(err instanceof Error ? err.message : String(err))
    },
  })

  const completeMutation = useMutation({
    mutationFn: async (url: string) => {
      const currentGen = generationRef.current
      try {
        const res = await pluginsService.completeAuth(plugin.pluginId, { callbackUrl: url })
        if (generationRef.current !== currentGen) {
          return
        }
        return res
      } finally {
        // 提交后无论成功失败，立即清空密码输入，不保留在内存或组件状态中
        setCallbackUrl('')
      }
    },
    retry: false, // 同一 callback 请求不自动重试
    onSuccess: () => {
      onSuccess()
    },
    onError: (err: unknown) => {
      setCompleteError(err instanceof Error ? err.message : String(err))
    },
  })

  function handleOpenLogin() {
    setPrepareError(null)
    prepareMutation.mutate(selectedRegion)
  }

  function handleComplete(event: React.FormEvent) {
    event.preventDefault()
    const trimmed = callbackUrl.trim()
    if (!trimmed) {
      return
    }
    setCallbackUrl('')
    setCompleteError(null)
    completeMutation.mutate(trimmed)
  }

  const isKeyUnavailable = plugin.status === 'KEY_UNAVAILABLE'

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card resource-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={t('plugins.connectDialog.title', { name: plugin.name })}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader
          title={t('plugins.connectDialog.title', { name: plugin.name })}
          onClose={onClose}
        />
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
          <div className="form-group" style={{ marginBottom: '16px' }}>
            <FieldLabel required>{t('plugins.connectDialog.step1')}</FieldLabel>
            <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginTop: '6px' }}>
              <select
                value={selectedRegion}
                disabled={isKeyUnavailable || prepareMutation.isPending}
                onChange={(e) => setSelectedRegion(e.target.value)}
                style={{ flex: '1', padding: '6px 10px', borderRadius: '4px' }}
                aria-label={t('plugins.region')}
              >
                {candidates.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="btn-primary"
                disabled={isKeyUnavailable || prepareMutation.isPending}
                onClick={handleOpenLogin}
              >
                <ExternalLink size={16} aria-hidden="true" style={{ marginRight: '4px' }} />
                {prepareMutation.isPending ? '...' : t('plugins.connectDialog.openLogin')}
              </button>
            </div>
          </div>

          {/* 步骤 2：粘贴回调 Deep Link */}
          <form onSubmit={handleComplete} noValidate>
            <div className="form-group">
              <FieldLabel required>{t('plugins.connectDialog.step2')}</FieldLabel>
              <input
                type="password"
                autoComplete="off"
                data-testid="plugin-callback-input"
                value={callbackUrl}
                disabled={isKeyUnavailable || completeMutation.isPending}
                placeholder={t('plugins.connectDialog.callbackPlaceholder')}
                onChange={(e) => setCallbackUrl(e.target.value)}
                required
              />
              <span className="inline-hint" style={{ marginTop: '4px', display: 'block' }}>
                {loginOpened
                  ? 'Please copy the callback deep link from browser after login.'
                  : 'Open login page first, then paste the callback link here.'}
              </span>
            </div>

            <div className="modal-footer" style={{ marginTop: '20px', padding: 0 }}>
              <button
                type="button"
                className="ghost-inline-btn"
                onClick={onClose}
                disabled={completeMutation.isPending}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="btn-primary"
                disabled={isKeyUnavailable || !callbackUrl.trim() || completeMutation.isPending}
              >
                {completeMutation.isPending ? '...' : t('plugins.connectDialog.complete')}
              </button>
            </div>
          </form>
        </div>
      </div>
    </ModalBackdrop>
  )
}
