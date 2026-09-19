import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

export interface EnvironmentHostSectionProps {
  environment: EnvironmentCardDTO
}

export function EnvironmentHostSection({ environment }: EnvironmentHostSectionProps) {
  const { t } = useI18n()

  return (
    <section className="env-mgmt-section" aria-label={t('ai.environment.runtime.title')}>
      <div className="env-mgmt-section-header">
        <h3 className="env-mgmt-section-title">{t('ai.environment.runtime.title')}</h3>
      </div>

      <div className="env-host-grid">
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.runtime.operatingSystem')}</span>
          <span className="val">{environment.operatingSystem ?? '—'}</span>
        </div>
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.runtime.timeZone')}</span>
          <span className="val">{environment.timeZone ?? '—'}</span>
        </div>
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.runtime.rootPath')}</span>
          <span className="val mono">{environment.rootPath ?? '—'}</span>
        </div>
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.lastSeen')}</span>
          <span className="val">{environment.lastSeen ?? '—'}</span>
        </div>
        <div className="env-host-meta-card">
          <span className="lbl">{t('ai.environment.runtime.note')}</span>
          <span className="val">{environment.note ?? '—'}</span>
        </div>
      </div>
    </section>
  )
}
