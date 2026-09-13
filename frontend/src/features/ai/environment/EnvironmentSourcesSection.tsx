import { Plus, RefreshCw, Pencil, Trash2, Download } from 'lucide-react'
import { formatTimestamp } from '@/features/ai/environment/environment-utils'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentSkillSourceDTO } from '@/shared/api/contracts/ai-environment'

export interface EnvironmentSourcesSectionProps {
  sources: EnvironmentSkillSourceDTO[]
  loading?: boolean
  error?: string | null
  onAdd: () => void
  onEdit: (source: EnvironmentSkillSourceDTO) => void
  onDelete: (source: EnvironmentSkillSourceDTO) => void
  onAction: (source: EnvironmentSkillSourceDTO, action: 'REFRESH' | 'INSTALL' | 'UPDATE') => void
}

export function EnvironmentSourcesSection({
  sources,
  loading,
  error,
  onAdd,
  onEdit,
  onDelete,
  onAction,
}: EnvironmentSourcesSectionProps) {
  const { t, locale } = useI18n()

  return (
    <section className="env-mgmt-section" aria-labelledby="env-section-sources">
      <div className="env-mgmt-section-header">
        <div>
          <h3 id="env-section-sources">{t('ai.environment.sources.title')}</h3>
          <span className="env-mgmt-badge-count">{sources.length}</span>
        </div>
        <button type="button" className="btn-secondary btn-sm" onClick={onAdd}>
          <Plus aria-hidden="true" />
          {t('ai.environment.sources.create')}
        </button>
      </div>

      {loading ? (
        <div className="state-block">{t('ai.environment.sources.loading')}</div>
      ) : error ? (
        <div className="state-block error">{error}</div>
      ) : sources.length === 0 ? (
        <div className="state-block">{t('ai.environment.sources.empty')}</div>
      ) : (
        <div className="env-sources-list">
          {sources.map((source) => {
            const isPath = source.type === 'path'
            const isGit = source.type === 'git'
            const isDefault = source.defaultSource === true
            const hasAppliedRevision = Boolean(source.appliedRevision && source.appliedRevision.trim())
            const isGitAligned =
              hasAppliedRevision
              && source.appliedVersion != null
              && source.appliedVersion === source.version
            const statusClass = `status-pill is-${(source.status || 'unknown').toLowerCase()}`

            return (
              <article key={source.sourceId} className="env-source-card">
                <div className="env-source-card-header">
                  <div className="env-source-card-badges">
                    <span className="meta-chip is-type">{source.type.toUpperCase()}</span>
                    {isDefault && (
                      <span className="meta-chip is-default">
                        {t('ai.environment.sources.defaultBadge')}
                      </span>
                    )}
                    <span className={statusClass}>{source.status}</span>
                  </div>
                  <code className="env-source-id" title={source.sourceId}>
                    {source.sourceId}
                  </code>
                </div>

                <div className="env-source-config">
                  {isPath && source.path && (
                    <div className="env-meta-row">
                      <span className="lbl">{t('ai.environment.sources.path')}</span>
                      <span className="val" title={source.path}>
                        {source.path}
                      </span>
                    </div>
                  )}

                  {isGit && (
                    <>
                      <div className="env-meta-row">
                        <span className="lbl">{t('ai.environment.sources.gitUrl')}</span>
                        <span className="val" title={source.gitUrl ?? ''}>
                          {source.gitUrl}
                        </span>
                      </div>
                      {source.gitRef && (
                        <div className="env-meta-row">
                          <span className="lbl">{t('ai.environment.sources.gitRef')}</span>
                          <span className="val">{source.gitRef}</span>
                        </div>
                      )}
                      {source.scanPath && (
                        <div className="env-meta-row">
                          <span className="lbl">{t('ai.environment.sources.scanPath')}</span>
                          <span className="val">{source.scanPath}</span>
                        </div>
                      )}
                    </>
                  )}
                </div>

                <div className="env-source-applied-facts">
                  <div className="env-fact-item">
                    <span className="lbl">{t('ai.environment.sources.version')}</span>
                    <span className="val">{source.version}</span>
                  </div>
                  <div className="env-fact-item">
                    <span className="lbl">{t('ai.environment.sources.appliedVersion')}</span>
                    <span className="val">{source.appliedVersion ?? '—'}</span>
                  </div>
                  <div className="env-fact-item">
                    <span className="lbl">{t('ai.environment.sources.appliedRevision')}</span>
                    <span className="val">
                      {source.appliedRevision ? (
                        <code title={source.appliedRevision}>
                          {source.appliedRevision.slice(0, 12)}
                        </code>
                      ) : (
                        '—'
                      )}
                    </span>
                  </div>
                  <div className="env-fact-item">
                    <span className="lbl">{t('ai.environment.sources.lastAppliedAt')}</span>
                    <span className="val">
                      {formatTimestamp(source.lastAppliedAt, locale) || '—'}
                    </span>
                  </div>
                </div>

                {source.lastErrorMessage && (
                  <div className="env-diag-error" role="alert">
                    {source.lastErrorCode ? <strong>[{source.lastErrorCode}] </strong> : null}
                    {source.lastErrorMessage}
                  </div>
                )}

                {Array.isArray(source.diagnostics) && source.diagnostics.length > 0 && (
                  <div className="env-diag-list">
                    <span className="lbl">{t('ai.environment.sources.diagnostics')}</span>
                    <ul>
                      {source.diagnostics.map((diag, idx) => (
                        <li key={idx}>
                          {diag.location ? <code>{diag.location}: </code> : null}
                          <span>{diag.message}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="env-source-actions">
                  {isPath && (
                    <button
                      type="button"
                      className="action-enter-btn"
                      aria-label={`${t('ai.environment.action.refresh')} ${source.sourceId}`}
                      onClick={() => onAction(source, 'REFRESH')}
                    >
                      <RefreshCw aria-hidden="true" />
                      {t('ai.environment.action.refresh')}
                    </button>
                  )}

                  {isGit && !hasAppliedRevision && (
                    <button
                      type="button"
                      className="action-enter-btn"
                      aria-label={`${t('ai.environment.action.install')} ${source.sourceId}`}
                      onClick={() => onAction(source, 'INSTALL')}
                    >
                      <Download aria-hidden="true" />
                      {t('ai.environment.action.install')}
                    </button>
                  )}

                  {isGit && hasAppliedRevision && (
                    <>
                      {isGitAligned && (
                        <button
                          type="button"
                          className="action-enter-btn"
                          aria-label={`${t('ai.environment.action.refresh')} ${source.sourceId}`}
                          onClick={() => onAction(source, 'REFRESH')}
                        >
                          <RefreshCw aria-hidden="true" />
                          {t('ai.environment.action.refresh')}
                        </button>
                      )}
                      <button
                        type="button"
                        className="action-enter-btn"
                        aria-label={`${t('ai.environment.action.update')} ${source.sourceId}`}
                        onClick={() => onAction(source, 'UPDATE')}
                      >
                        <Download aria-hidden="true" />
                        {t('ai.environment.action.update')}
                      </button>
                    </>
                  )}

                  <button
                    type="button"
                    className="action-enter-btn"
                    aria-label={`${t('ai.environment.sources.edit')} ${source.sourceId}`}
                    onClick={() => onEdit(source)}
                  >
                    <Pencil aria-hidden="true" />
                    {t('ai.catalog.action.edit')}
                  </button>

                  <button
                    type="button"
                    className="action-enter-btn danger"
                    aria-label={`${t('ai.environment.sources.delete')} ${source.sourceId}`}
                    onClick={() => onDelete(source)}
                  >
                    <Trash2 aria-hidden="true" />
                    {t('ai.catalog.action.delete')}
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
