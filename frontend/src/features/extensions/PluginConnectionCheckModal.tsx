import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { useI18n, type AppLocale } from '@/shared/i18n'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import type { PluginDTO } from '@/shared/api/contracts/ai-plugin'
import {
  pluginConnectionCheckExpected,
  pluginConnectionCheckIsStale,
  type PluginConnectionCheckRun,
} from '@/features/extensions/plugin-connection-check'
import './plugin-connection-check.css'

interface PluginConnectionCheckModalProps {
  /** 当前列表中的同名 Plugin：其已应用坐标用于判断本次结果是否仍然对应当前配置。 */
  plugin: PluginDTO
  run: PluginConnectionCheckRun
  onClose: () => void
}

/** 后端 Instant 的既有 wire 约定是 epoch seconds；展示时按同一约定换算，不做其它缩放。 */
function formatCheckedAt(value: Exclude<InstantTimestamp, null>, locale: AppLocale): string {
  const date = new Date(typeof value === 'number' ? value * 1000 : value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(locale)
}

/**
 * 显式连接检查结果：只呈现本次检查时刻与三个类型化维度，并把结果与当前配置的坐标关系讲清楚。
 * 认证维度只陈述本次凭据是否被接受，浏览器会话恒为未知——绝不含糊成“已登录”或远端健康。
 */
export function PluginConnectionCheckModal({
  plugin,
  run,
  onClose,
}: PluginConnectionCheckModalProps) {
  const { t, locale } = useI18n()
  const pending = run.phase === 'pending'
  // 结果只在仍与调用方当前所见已应用坐标一致时才作为当前结果展示。
  const stale = pluginConnectionCheckIsStale(run, pluginConnectionCheckExpected(plugin))
  const result = run.phase === 'done' && !stale ? run.result : null

  return (
    <Dialog
      className="resource-modal-card plugin-check-modal"
      title={t('plugins.connectionCheck.title', { name: plugin.name })}
      pending={pending}
      onClose={onClose}
    >
      <div className="modal-body">
        <p className="settings-hint">{t('plugins.connectionCheck.description')}</p>

        <div className="plugin-check-facts">
          <div className="plugin-check-fact">
            <span className="plugin-check-fact-label">
              {t('plugins.connectionCheck.coordinates')}
            </span>
            <span className="plugin-check-fact-value" data-testid="plugin-check-coordinates">
              {`Rev ${run.expectedRevision}`}
            </span>
          </div>
          {result ? (
            <div className="plugin-check-fact">
              <span className="plugin-check-fact-label">
                {t('plugins.connectionCheck.checkedAt')}
              </span>
              <span className="plugin-check-fact-value" data-testid="plugin-check-checked-at">
                {formatCheckedAt(result.checkedAt, locale)}
              </span>
            </div>
          ) : null}
        </div>

        {pending ? (
          <p className="inline-hint" role="status" data-testid="plugin-check-pending">
            {t('plugins.connectionCheck.pending')}
          </p>
        ) : null}

        {run.phase === 'conflict' ? (
          <div className="plugin-check-conflict" role="alert" data-testid="plugin-check-conflict">
            {t('plugins.connectionCheck.conflict')}
          </div>
        ) : null}

        {run.phase === 'mismatch' ? (
          <div className="form-error-banner" role="alert" data-testid="plugin-check-mismatch">
            {t('plugins.connectionCheck.mismatch')}
          </div>
        ) : null}

        {run.phase === 'failed' ? (
          <div className="form-error-banner" role="alert" data-testid="plugin-check-failed">
            {run.errorMessage ?? t('plugins.connectionCheck.failed')}
          </div>
        ) : null}

        {stale ? (
          <div className="plugin-check-stale" role="status" data-testid="plugin-check-stale">
            {t('plugins.connectionCheck.stale')}
          </div>
        ) : null}

        {result ? (
          <dl className="plugin-check-dimensions">
            <div className="plugin-check-dimension" data-dimension="reachability">
              <dt>{t('plugins.connectionCheck.reachability')}</dt>
              <dd data-testid="plugin-check-reachability">
                {t(`plugins.connectionCheck.reachability.${result.reachability}`)}
              </dd>
            </div>
            <div className="plugin-check-dimension" data-dimension="authentication">
              <dt>{t('plugins.connectionCheck.authentication')}</dt>
              <dd data-testid="plugin-check-authentication">
                {t(`plugins.connectionCheck.authentication.${result.authentication}`)}
              </dd>
            </div>
            <div className="plugin-check-dimension" data-dimension="browserSession">
              <dt>{t('plugins.connectionCheck.browserSession')}</dt>
              <dd data-testid="plugin-check-browser-session">
                {t(`plugins.connectionCheck.browserSession.${result.browserSession}`)}
              </dd>
            </div>
          </dl>
        ) : null}
      </div>

      <div className="modal-footer">
        <Button
          variant="ghost"
          data-autofocus
          data-testid="plugin-check-close"
          onClick={onClose}
          disabled={pending}
        >
          {t('shared.close')}
        </Button>
      </div>
    </Dialog>
  )
}
