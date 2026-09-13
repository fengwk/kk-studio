import { formatTimestamp } from '@/features/ai/environment/environment-utils'
import { useI18n } from '@/shared/i18n'
import type {
  EnvironmentInventoryDTO,
  EnvironmentSkillDTO,
} from '@/shared/api/contracts/ai-environment'

export interface EnvironmentInventorySectionProps {
  inventory?: EnvironmentInventoryDTO
  skills: EnvironmentSkillDTO[]
  loading?: boolean
  error?: string | null
}

export function EnvironmentInventorySection({
  inventory,
  skills,
  loading,
  error,
}: EnvironmentInventorySectionProps) {
  const { t, locale } = useI18n()

  return (
    <section className="env-mgmt-section" aria-labelledby="env-section-inventory">
      <div className="env-mgmt-section-header">
        <div>
          <h3 id="env-section-inventory">{t('ai.environment.inventory.title')}</h3>
          <span className="env-mgmt-badge-count">{skills.length}</span>
        </div>
      </div>

      {loading ? (
        <div className="state-block">{t('ai.environment.inventory.loading')}</div>
      ) : error ? (
        <div className="state-block error">{error}</div>
      ) : (
        <div className="env-inventory-content">
          <div className="env-inventory-grid">
            <div className="env-inventory-meta-card">
              <span className="lbl">{t('ai.environment.inventory.sourceSetVersion')}</span>
              <span className="val">{inventory?.sourceSetVersion ?? '—'}</span>
            </div>
            <div className="env-inventory-meta-card">
              <span className="lbl">{t('ai.environment.inventory.appliedSourceSetVersion')}</span>
              <span className="val">{inventory?.appliedSourceSetVersion ?? '—'}</span>
            </div>
            <div className="env-inventory-meta-card">
              <span className="lbl">{t('ai.environment.inventory.operatingSystem')}</span>
              <span className="val">{inventory?.operatingSystem ?? '—'}</span>
            </div>
            <div className="env-inventory-meta-card">
              <span className="lbl">{t('ai.environment.inventory.timeZone')}</span>
              <span className="val">{inventory?.timeZone ?? '—'}</span>
            </div>
            <div className="env-inventory-meta-card">
              <span className="lbl">{t('ai.environment.inventory.reportedAt')}</span>
              <span className="val">
                {formatTimestamp(inventory?.reportedAt, locale) || '—'}
              </span>
            </div>
            <div className="env-inventory-meta-card">
              <span className="lbl">{t('ai.environment.inventory.rootPath')}</span>
              <span className="val" title={inventory?.rootPath ?? ''}>
                {inventory?.rootPath || '—'}
              </span>
            </div>
            {inventory?.note && (
              <div className="env-inventory-meta-card env-grid-span-full">
                <span className="lbl">{t('ai.environment.inventory.note')}</span>
                <span className="val">{inventory.note}</span>
              </div>
            )}
          </div>

          <div className="env-inventory-skills-section">
            <h4 className="env-subsection-title">
              {t('ai.environment.inventory.persistedSkills')}
              <span className="env-skills-count">({skills.length})</span>
            </h4>

            {skills.length === 0 ? (
              <p className="env-empty-hint">{t('ai.environment.inventory.noSkills')}</p>
            ) : (
              <div className="env-skills-table-wrap">
                <table className="env-skills-table">
                  <thead>
                    <tr>
                      <th>{t('ai.environment.inventory.skillName')}</th>
                      <th>{t('ai.environment.inventory.description')}</th>
                      <th>{t('ai.environment.inventory.sourceId')}</th>
                      <th>{t('ai.environment.inventory.baseDirectory')}</th>
                      <th>{t('ai.environment.inventory.contentRevision')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {skills.map((skill) => (
                      <tr key={`${skill.sourceId}-${skill.name}`}>
                        <td>
                          <strong>{skill.name}</strong>
                        </td>
                        <td>{skill.description || '—'}</td>
                        <td>
                          <code>{skill.sourceId}</code>
                        </td>
                        <td>
                          <code title={skill.baseDirectory}>{skill.baseDirectory}</code>
                        </td>
                        <td>
                          <code title={skill.contentRevision}>
                            {skill.contentRevision ? skill.contentRevision.slice(0, 12) : '—'}
                          </code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
