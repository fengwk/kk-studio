import { useQuery } from '@tanstack/react-query'
import { Mono } from '@/shared/ui/data/Mono'
import { useI18n } from '@/shared/i18n'
import { environmentRuntimeQueryOptions } from '@/features/environment/environment-runtime-query'
import {
  environmentProxyText,
  presentEnvironmentConfig,
} from '@/features/environment/environment-utils'
import { EnvironmentConfigStatusBadge } from '@/features/environment/EnvironmentConfigStatusBadge'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'

export interface EnvironmentConfigurationSectionProps {
  environment: EnvironmentDTO
}

/** 管理面配置摘要：与连接状态分开，并展示当前 owner 运行快照的已应用版本与失败规则。 */
export function EnvironmentConfigurationSection({
  environment,
}: EnvironmentConfigurationSectionProps) {
  const { t } = useI18n()
  const runtimeQuery = useQuery(environmentRuntimeQueryOptions(environment))
  const view = presentEnvironmentConfig(
    environment,
    runtimeQuery.isError ? undefined : runtimeQuery.data,
  )

  return (
    <section className="env-mgmt-section" aria-label={t('ai.environment.config.title')}>
      <div className="env-mgmt-section-header">
        <div>
          <h3 className="env-mgmt-section-title">{t('ai.environment.config.title')}</h3>
          <EnvironmentConfigStatusBadge view={view} />
        </div>
      </div>

      <div className="env-host-grid">
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.config.cardVersion')}</span>
          <Mono className="val">{environment.version}</Mono>
        </div>
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.config.appliedVersion')}</span>
          <Mono className="val">{view.appliedVersion ?? '—'}</Mono>
        </div>
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.config.proxy')}</span>
          <Mono className="val">{environmentProxyText(view, t('ai.environment.config.proxyDirect'))}</Mono>
        </div>
        {view.error ? (
          <div className="env-host-meta-card">
            <span className="lbl">{t('ai.environment.config.error')}</span>
            <span className="val">
              {view.error.code}
              {view.error.field ? ` · ${view.error.field}` : ''}
            </span>
          </div>
        ) : null}
      </div>

      {runtimeQuery.isError ? (
        <p className="field-help" role="status">
          {t('ai.environment.runtime.loadFailed')}
        </p>
      ) : null}
      <p className="field-help">{t('ai.environment.config.hotUpdateHelp')}</p>
    </section>
  )
}
